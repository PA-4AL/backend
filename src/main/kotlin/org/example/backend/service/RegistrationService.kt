package org.example.backend.service

import org.example.backend.database.enums.RegistrationStatus
import org.example.backend.database.enums.TournamentStatus
import org.example.backend.error.ErreurMetier
import org.example.backend.model.Display
import org.example.backend.model.ParticipantDto
import org.example.backend.model.PendingRegistrationDto
import org.example.backend.repository.RegistrationRepository
import org.example.backend.repository.TeamRepository
import org.example.backend.repository.TournamentRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
class RegistrationService(
    private val repo: RegistrationRepository,
    private val tournaments: TournamentRepository,
    private val teams: TeamRepository,
) {

    fun participants(tournamentId: UUID): List<ParticipantDto> = repo.listByTournament(tournamentId).map {
        ParticipantDto(
            registrationId = it.id.toString(),
            name = it.name,
            status = it.status.literal,
            seed = it.seed,
            registeredLabel = Display.relativeTime(it.createdAt),
            finalRank = it.finalRank,
        )
    }

    fun pending(): List<PendingRegistrationDto> = repo.listPending().map {
        PendingRegistrationDto(
            registrationId = it.id.toString(),
            participant = it.name,
            tournamentId = it.tournamentId.toString(),
            tournamentName = it.tournamentName,
            status = it.status.literal,
            registeredLabel = Display.relativeTime(it.createdAt),
        )
    }

    /** Inscription solo de l'utilisateur authentifié (spec §4.3). */
    @Transactional
    fun register(tournamentId: UUID, keycloakId: String, pseudo: String, email: String?): ParticipantDto {
        val tournament = requireOpen(tournamentId)
        if ((tournament.teamSize ?: 1) > 1) {
            throw ErreurMetier.Conflit(
                "Ce tournoi se joue en équipe (${tournament.teamSize}v${tournament.teamSize}) — inscris ton équipe",
            )
        }

        val userId = repo.upsertUserByKeycloak(keycloakId, pseudo, email)
        if (repo.existsForUser(tournamentId, userId)) {
            throw ErreurMetier.Conflit("Tu es déjà inscrit à ce tournoi")
        }

        val status = statusFor(tournamentId)
        val id = repo.insertSolo(tournamentId, userId, status)
        return dto(id, pseudo, status)
    }

    /** Inscription d'une équipe par son capitaine (spec §4.3). */
    @Transactional
    fun registerTeam(
        tournamentId: UUID,
        teamId: UUID,
        keycloakId: String,
        pseudo: String,
        email: String?,
    ): ParticipantDto {
        val tournament = requireOpen(tournamentId)
        val teamSize = tournament.teamSize ?: 1
        if (teamSize <= 1) {
            throw ErreurMetier.Conflit("Ce tournoi se joue en solo")
        }

        val team = teams.find(teamId)
            ?: throw ErreurMetier.Introuvable("Équipe introuvable")
        val callerId = repo.upsertUserByKeycloak(keycloakId, pseudo, email)
        if (!teams.isCaptain(teamId, callerId)) {
            throw ErreurMetier.NonAutorise("Seul le capitaine peut inscrire l'équipe")
        }
        if (repo.existsForTeam(tournamentId, teamId)) {
            throw ErreurMetier.Conflit("${team.name} est déjà inscrite")
        }
        val rosterSize = teams.members(teamId).count { it.role.literal != "substitute" }
        if (rosterSize < teamSize) {
            throw ErreurMetier.Conflit(
                "Roster incomplet : $rosterSize joueur(s) pour un format ${teamSize}v$teamSize",
            )
        }

        val status = statusFor(tournamentId)
        val id = repo.insertTeam(tournamentId, teamId, status)
        return dto(id, team.name, status)
    }

    /** Ajout manuel par l'organisateur (spec §4.3) : joueur fantôme en solo, équipe fantôme sinon. */
    @Transactional
    fun addManual(tournamentId: UUID, name: String): ParticipantDto {
        val tournament = requireOpen(tournamentId)
        val clean = name.trim()
        if (clean.isEmpty()) {
            throw ErreurMetier.Invalide("Le nom est obligatoire")
        }

        val status = statusFor(tournamentId)
        val id = if ((tournament.teamSize ?: 1) > 1) {
            repo.insertTeam(tournamentId, repo.insertGhostTeam(clean), status)
        } else {
            repo.insertSolo(tournamentId, repo.insertGhostUser(clean), status)
        }
        return dto(id, clean, status)
    }

    private fun requireOpen(tournamentId: UUID) = (
        tournaments.findById(tournamentId)
            ?: throw ErreurMetier.Introuvable("Tournoi introuvable")
        )
        .also {
            if (it.status !in listOf(
                    TournamentStatus.draft,
                    TournamentStatus.registration,
                    TournamentStatus.check_in,
                )
            ) {
                throw ErreurMetier.Conflit("Les inscriptions sont fermées")
            }
        }

    /** Liste d'attente quand le tournoi est complet (spec §4.3). */
    private fun statusFor(tournamentId: UUID): RegistrationStatus {
        val max = tournaments.findById(tournamentId)?.maxParticipants
        val active = tournaments.countParticipants(tournamentId)
        return if (max != null && active >= max) RegistrationStatus.waitlist else RegistrationStatus.confirmed
    }

    private fun dto(id: UUID, name: String, status: RegistrationStatus) = ParticipantDto(
        registrationId = id.toString(),
        name = name,
        status = status.literal,
        seed = null,
        registeredLabel = "À l'instant",
    )

    /** Seeding manuel (spec §4.2) : position de l'équipe dans le bracket. */
    fun setSeed(registrationId: UUID, seed: Int?) {
        if (seed != null && seed < 1) {
            throw ErreurMetier.Invalide("Le seed doit être ≥ 1")
        }
        if (!repo.updateSeed(registrationId, seed)) {
            throw ErreurMetier.Introuvable("Inscription introuvable")
        }
    }

    /** Validation par l'organisateur : pending/waitlist → confirmed. */
    fun confirm(registrationId: UUID) = transition(
        registrationId,
        from = listOf(RegistrationStatus.pending, RegistrationStatus.waitlist),
        to = RegistrationStatus.confirmed,
    )

    /** Refus / désistement. */
    fun reject(registrationId: UUID) = transition(
        registrationId,
        from = listOf(RegistrationStatus.pending, RegistrationStatus.waitlist, RegistrationStatus.confirmed),
        to = RegistrationStatus.withdrawn,
    )

    private fun transition(id: UUID, from: List<RegistrationStatus>, to: RegistrationStatus) {
        val current = repo.findStatus(id)
            ?: throw ErreurMetier.Introuvable("Inscription introuvable")
        if (current !in from) {
            throw ErreurMetier.Conflit("Transition impossible depuis « ${current.literal} »")
        }
        repo.updateStatus(id, to)
    }
}
