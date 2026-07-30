package org.example.backend.service

import org.example.backend.database.enums.TeamMemberRole
import org.example.backend.error.ErreurMetier
import org.example.backend.model.AddMemberRequest
import org.example.backend.model.CreateTeamRequest
import org.example.backend.model.TeamDto
import org.example.backend.model.TeamMemberDto
import org.example.backend.repository.RegistrationRepository
import org.example.backend.repository.TeamRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
class TeamService(private val repo: TeamRepository, private val registrations: RegistrationRepository) {

    fun mine(keycloakId: String, pseudo: String, email: String?): List<TeamDto> {
        val userId = registrations.upsertUserByKeycloak(keycloakId, pseudo, email)
        return repo.listMine(userId).map { toDto(it.id, userId) }
    }

    fun get(teamId: UUID): TeamDto = repo.find(teamId)?.let { toDto(it.id) }
        ?: throw ErreurMetier.Introuvable("Équipe introuvable")

    @Transactional
    fun create(req: CreateTeamRequest, keycloakId: String, pseudo: String, email: String?): TeamDto {
        val name = req.name.trim()
        if (name.isEmpty()) {
            throw ErreurMetier.Invalide("Le nom de l'équipe est obligatoire")
        }
        val tag = req.tag?.trim()?.take(8)?.ifEmpty { null }
        val userId = registrations.upsertUserByKeycloak(keycloakId, pseudo, email)
        val team = repo.create(name, tag, userId)
        return toDto(team.id, userId)
    }

    /** Ajout au roster par le capitaine — pseudo existant ou joueur fantôme (spec §4.3). */
    @Transactional
    fun addMember(teamId: UUID, req: AddMemberRequest, keycloakId: String, pseudo: String, email: String?): TeamDto {
        val callerId = registrations.upsertUserByKeycloak(keycloakId, pseudo, email)
        requireCaptain(teamId, callerId)

        val memberPseudo = req.pseudo.trim()
        if (memberPseudo.isEmpty()) {
            throw ErreurMetier.Invalide("Le pseudo est obligatoire")
        }
        val role = TeamMemberRole.entries.firstOrNull { it.literal == req.role } ?: TeamMemberRole.member
        if (role == TeamMemberRole.captain) {
            throw ErreurMetier.Invalide("Une équipe n'a qu'un capitaine")
        }

        val userId = repo.findUserByPseudo(memberPseudo) ?: registrations.insertGhostUser(memberPseudo)
        if (repo.isMember(teamId, userId)) {
            throw ErreurMetier.Conflit("$memberPseudo est déjà dans l'équipe")
        }
        repo.addMember(teamId, userId, role)
        return toDto(teamId)
    }

    @Transactional
    fun removeMember(teamId: UUID, memberId: UUID, keycloakId: String, pseudo: String, email: String?): TeamDto {
        val callerId = registrations.upsertUserByKeycloak(keycloakId, pseudo, email)
        requireCaptain(teamId, callerId)
        if (memberId == callerId) {
            throw ErreurMetier.Invalide("Le capitaine ne peut pas se retirer lui-même")
        }
        if (!repo.removeMember(teamId, memberId)) {
            throw ErreurMetier.Introuvable("Membre introuvable")
        }
        return toDto(teamId)
    }

    private fun requireCaptain(teamId: UUID, userId: UUID) {
        if (repo.find(teamId) == null) {
            throw ErreurMetier.Introuvable("Équipe introuvable")
        }
        if (!repo.isCaptain(teamId, userId)) {
            throw ErreurMetier.NonAutorise("Seul le capitaine peut gérer le roster")
        }
    }

    /**
     * @param viewerId identifiant interne de l'appelant, quand il est connu — il
     *   permet de dire au frontend s'il est capitaine, plutôt que de le lui faire
     *   deviner à partir d'un pseudo.
     */
    private fun toDto(teamId: UUID, viewerId: UUID? = null): TeamDto {
        val team = repo.find(teamId)!!
        val membres = repo.members(teamId)
        return TeamDto(
            id = team.id.toString(),
            name = team.name,
            tag = team.tag,
            members = membres.map {
                TeamMemberDto(it.userId.toString(), it.pseudo, it.role.literal)
            },
            viewerIsCaptain = viewerId != null &&
                membres.any { it.userId == viewerId && it.role == TeamMemberRole.captain },
        )
    }
}
