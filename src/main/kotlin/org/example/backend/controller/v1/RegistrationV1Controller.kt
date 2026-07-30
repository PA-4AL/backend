package org.example.backend.controller.v1

import org.example.backend.model.ParticipantDto
import org.example.backend.model.PendingRegistrationDto
import org.example.backend.repository.RegistrationRepository
import org.example.backend.service.RegistrationService
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

data class AddParticipantRequest(val name: String)
data class SetSeedRequest(val seed: Int?)
data class RegisterTeamRequest(val teamId: UUID)

/**
 * Pas de chemin de classe : ce controller couvre `/tournaments/…` et
 * `/registrations/…`. Préfixe `/api/v1` appliqué par `WebMvcConfig`.
 */
@RestController
@RequestMapping(version = "1+")
class RegistrationV1Controller(
    private val service: RegistrationService,
    private val registrations: RegistrationRepository,
) {

    /** Liste publique des participants d'un tournoi. */
    @GetMapping("/tournaments/{id}/participants")
    fun participants(@PathVariable id: UUID): List<ParticipantDto> = service.participants(id)

    /** Inscription solo de l'utilisateur connecté. */
    @PostMapping("/tournaments/{id}/register")
    fun register(@PathVariable id: UUID, @AuthenticationPrincipal jwt: Jwt): ResponseEntity<ParticipantDto> {
        val created = service.register(
            tournamentId = id,
            keycloakId = jwt.subject,
            pseudo = jwt.getClaimAsString("preferred_username") ?: "Joueur",
            email = jwt.getClaimAsString("email"),
        )
        return ResponseEntity.status(HttpStatus.CREATED).body(created)
    }

    /** Inscription d'une équipe par son capitaine. */
    @PostMapping("/tournaments/{id}/register-team")
    fun registerTeam(
        @PathVariable id: UUID,
        @RequestBody body: RegisterTeamRequest,
        @AuthenticationPrincipal jwt: Jwt,
    ): ResponseEntity<ParticipantDto> {
        val created = service.registerTeam(
            tournamentId = id,
            teamId = body.teamId,
            keycloakId = jwt.subject,
            pseudo = jwt.getClaimAsString("preferred_username") ?: "Joueur",
            email = jwt.getClaimAsString("email"),
        )
        return ResponseEntity.status(HttpStatus.CREATED).body(created)
    }

    /** Ajout manuel d'un participant par l'organisateur (joueur sans compte). */
    @PostMapping("/tournaments/{id}/participants")
    @PreAuthorize("hasAnyRole('organizer', 'admin')")
    fun addManual(
        @AuthenticationPrincipal jwt: Jwt,
        @PathVariable id: UUID,
        @RequestBody body: AddParticipantRequest,
    ): ResponseEntity<ParticipantDto> = ResponseEntity.status(HttpStatus.CREATED)
        .body(service.addManual(id, body.name, currentUserId(jwt), estAdmin(jwt)))

    /** Inscriptions à traiter — celles de SES tournois, toutes pour un admin. */
    @GetMapping("/registrations/pending")
    @PreAuthorize("hasAnyRole('organizer', 'admin')")
    fun pending(@AuthenticationPrincipal jwt: Jwt): List<PendingRegistrationDto> =
        service.pending(currentUserId(jwt), estAdmin(jwt))

    /** Seeding manuel avant génération du bracket. */
    @PostMapping("/registrations/{id}/seed")
    @PreAuthorize("hasAnyRole('organizer', 'admin')")
    fun setSeed(
        @AuthenticationPrincipal jwt: Jwt,
        @PathVariable id: UUID,
        @RequestBody body: SetSeedRequest,
    ): ResponseEntity<Void> {
        service.setSeed(id, body.seed, currentUserId(jwt), estAdmin(jwt))
        return ResponseEntity.noContent().build()
    }

    @PostMapping("/registrations/{id}/confirm")
    @PreAuthorize("hasAnyRole('organizer', 'admin')")
    fun confirm(@AuthenticationPrincipal jwt: Jwt, @PathVariable id: UUID): ResponseEntity<Void> {
        service.confirm(id, currentUserId(jwt), estAdmin(jwt))
        return ResponseEntity.noContent().build()
    }

    @PostMapping("/registrations/{id}/reject")
    @PreAuthorize("hasAnyRole('organizer', 'admin')")
    fun reject(@AuthenticationPrincipal jwt: Jwt, @PathVariable id: UUID): ResponseEntity<Void> {
        service.reject(id, currentUserId(jwt), estAdmin(jwt))
        return ResponseEntity.noContent().build()
    }

    /** Check-in d'un participant, dans la fenêtre prévue par le tournoi. */
    @PostMapping("/registrations/{id}/check-in")
    @PreAuthorize("hasAnyRole('organizer', 'admin')")
    fun checkIn(@AuthenticationPrincipal jwt: Jwt, @PathVariable id: UUID): ParticipantDto =
        service.checkIn(id, currentUserId(jwt), estAdmin(jwt))

    /** Identifiant interne de l'utilisateur, rattaché à son compte Keycloak. */
    private fun currentUserId(jwt: Jwt): UUID = registrations.upsertUserByKeycloak(
        jwt.subject,
        jwt.getClaimAsString("preferred_username") ?: "Joueur",
        jwt.getClaimAsString("email"),
    )

    /** L'administrateur plateforme passe outre le contrôle de propriété. */
    private fun estAdmin(jwt: Jwt): Boolean =
        (jwt.getClaimAsMap("realm_access")?.get("roles") as? List<*>)?.contains("admin") == true
}
