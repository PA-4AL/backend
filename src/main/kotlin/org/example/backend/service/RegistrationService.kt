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
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime
import java.util.UUID

@Service
class RegistrationService(
    private val repo: RegistrationRepository,
    private val tournaments: TournamentRepository,
    private val teams: TeamRepository,
    private val droits: Droits,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    private companion object {
        /**
         * Fenêtre de check-in appliquée quand le tournoi n'en précise pas.
         *
         * Une heure : assez pour laisser arriver les équipes, assez court pour que
         * la présence signalée veuille encore dire quelque chose au coup d'envoi.
         */
        const val DEFAUT_FENETRE_CHECK_IN = 60
    }

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

    /**
     * Inscriptions à traiter par l'appelant.
     *
     * Un organisateur ne voit que celles de **ses** tournois : la version
     * précédente exposait toutes celles de la plateforme, qu'il pouvait donc aussi
     * valider. L'administrateur les voit toutes, c'est son rôle de modération.
     */
    fun pending(callerId: UUID, estAdmin: Boolean): List<PendingRegistrationDto> = repo
        .listPending(if (estAdmin) null else tournaments.idsOrganisesPar(callerId))
        .map {
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
    fun addManual(tournamentId: UUID, name: String, callerId: UUID, estAdmin: Boolean): ParticipantDto {
        val tournament = requireOpen(tournamentId)
        droits.exigerOrganisateur(tournamentId, callerId, estAdmin)
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
    fun setSeed(registrationId: UUID, seed: Int?, callerId: UUID, estAdmin: Boolean) {
        if (seed != null && seed < 1) {
            throw ErreurMetier.Invalide("Le seed doit être ≥ 1")
        }
        val (tournamentId, _) = repo.findTournamentAndStatus(registrationId)
            ?: throw ErreurMetier.Introuvable("Inscription introuvable")
        droits.exigerOrganisateur(tournamentId, callerId, estAdmin)
        if (!repo.updateSeed(registrationId, seed)) {
            throw ErreurMetier.Introuvable("Inscription introuvable")
        }
    }

    /**
     * Check-in d'un participant, dans la fenêtre prévue.
     *
     * `check_in_window_minutes` n'était qu'un **libellé d'affichage** : rien
     * n'empêchait un check-in trois jours avant le départ, ni après le coup
     * d'envoi. Une fenêtre qui n'ouvre ni ne ferme rien n'est pas une règle.
     */
    @Transactional
    fun checkIn(registrationId: UUID, callerId: UUID, estAdmin: Boolean): ParticipantDto {
        val (tournamentId, statut) = repo.findTournamentAndStatus(registrationId)
            ?: throw ErreurMetier.Introuvable("Inscription introuvable")
        val tournoi = tournaments.findById(tournamentId)
            ?: throw ErreurMetier.Introuvable("Tournoi introuvable")
        droits.exigerOrganisateur(tournamentId, callerId, estAdmin)

        if (statut != RegistrationStatus.confirmed) {
            throw ErreurMetier.Conflit("Seule une inscription confirmée peut faire son check-in")
        }

        val depart = tournoi.startAt
            ?: throw ErreurMetier.Conflit("Le tournoi n'a pas de date de départ : le check-in ne peut pas s'ouvrir")
        val minutes = tournoi.checkInWindowMinutes ?: DEFAUT_FENETRE_CHECK_IN
        val ouverture = depart.minusMinutes(minutes.toLong())
        val maintenant = OffsetDateTime.now()

        if (maintenant.isBefore(ouverture)) {
            throw ErreurMetier.Conflit(
                "Le check-in ouvre $minutes minutes avant le départ",
            )
        }
        if (maintenant.isAfter(depart)) {
            throw ErreurMetier.Conflit("Le check-in est clos : le tournoi a commencé")
        }

        repo.updateStatus(registrationId, RegistrationStatus.checked_in)
        return dto(registrationId, "", RegistrationStatus.checked_in)
    }

    /** Validation par l'organisateur : pending/waitlist → confirmed. */
    @Transactional
    fun confirm(registrationId: UUID, callerId: UUID, estAdmin: Boolean) {
        val (tournamentId, _) = repo.findTournamentAndStatus(registrationId)
            ?: throw ErreurMetier.Introuvable("Inscription introuvable")
        droits.exigerOrganisateur(tournamentId, callerId, estAdmin)
        transition(
            registrationId,
            from = listOf(RegistrationStatus.pending, RegistrationStatus.waitlist),
            to = RegistrationStatus.confirmed,
        )
    }

    /**
     * Refus / désistement, avec **repêchage** de la liste d'attente.
     *
     * Une place libérée par un participant confirmé était perdue : la bascule ne
     * fonctionnait que dans un sens, personne ne sortait jamais de `waitlist`.
     * Le premier arrivé est repêché — l'ordre d'arrivée est le seul critère
     * défendable, et il est déjà celui qui a décidé de la mise en attente.
     */
    @Transactional
    fun reject(registrationId: UUID, callerId: UUID, estAdmin: Boolean) {
        val (tournamentId, statut) = repo.findTournamentAndStatus(registrationId)
            ?: throw ErreurMetier.Introuvable("Inscription introuvable")
        droits.exigerOrganisateur(tournamentId, callerId, estAdmin)

        transition(
            registrationId,
            from = listOf(RegistrationStatus.pending, RegistrationStatus.waitlist, RegistrationStatus.confirmed),
            to = RegistrationStatus.withdrawn,
        )

        // Seul le départ d'un participant qui occupait une place en libère une.
        // Un refus de `pending` ou de `waitlist` ne change rien au décompte.
        if (statut == RegistrationStatus.confirmed || statut == RegistrationStatus.checked_in) {
            repo.findPremierEnAttente(tournamentId)?.let { repeche ->
                repo.updateStatus(repeche, RegistrationStatus.confirmed)
                log.info("Inscription {} repêchée de la liste d'attente du tournoi {}", repeche, tournamentId)
            }
        }
    }

    private fun transition(id: UUID, from: List<RegistrationStatus>, to: RegistrationStatus) {
        val current = repo.findStatus(id)
            ?: throw ErreurMetier.Introuvable("Inscription introuvable")
        if (current !in from) {
            throw ErreurMetier.Conflit("Transition impossible depuis « ${current.literal} »")
        }
        repo.updateStatus(id, to)
    }
}
