package org.example.backend.controller

import org.example.backend.model.ParticipantDto
import org.example.backend.model.PendingRegistrationDto
import org.example.backend.service.RegistrationService
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

data class AddParticipantRequest(val name: String)
data class SetSeedRequest(val seed: Int?)
data class RegisterTeamRequest(val teamId: UUID)

@RestController
class RegistrationController(private val service: RegistrationService) {

    /** Liste publique des participants d'un tournoi. */
    @GetMapping("/api/tournaments/{id}/participants")
    fun participants(@PathVariable id: UUID): List<ParticipantDto> = service.participants(id)

    /** Inscription solo de l'utilisateur connecté. */
    @PostMapping("/api/tournaments/{id}/register")
    fun register(
        @PathVariable id: UUID,
        @AuthenticationPrincipal jwt: Jwt,
    ): ResponseEntity<ParticipantDto> {
        val created = service.register(
            tournamentId = id,
            keycloakId = jwt.subject,
            pseudo = jwt.getClaimAsString("preferred_username") ?: "Joueur",
            email = jwt.getClaimAsString("email"),
        )
        return ResponseEntity.status(HttpStatus.CREATED).body(created)
    }

    /** Inscription d'une équipe par son capitaine. */
    @PostMapping("/api/tournaments/{id}/register-team")
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
    @PostMapping("/api/tournaments/{id}/participants")
    fun addManual(
        @PathVariable id: UUID,
        @RequestBody body: AddParticipantRequest,
    ): ResponseEntity<ParticipantDto> =
        ResponseEntity.status(HttpStatus.CREATED).body(service.addManual(id, body.name))

    /** Inscriptions à traiter (organisateur). */
    @GetMapping("/api/registrations/pending")
    fun pending(): List<PendingRegistrationDto> = service.pending()

    /** Seeding manuel avant génération du bracket. */
    @PostMapping("/api/registrations/{id}/seed")
    fun setSeed(@PathVariable id: UUID, @RequestBody body: SetSeedRequest): ResponseEntity<Void> {
        service.setSeed(id, body.seed)
        return ResponseEntity.noContent().build()
    }

    @PostMapping("/api/registrations/{id}/confirm")
    fun confirm(@PathVariable id: UUID): ResponseEntity<Void> {
        service.confirm(id)
        return ResponseEntity.noContent().build()
    }

    @PostMapping("/api/registrations/{id}/reject")
    fun reject(@PathVariable id: UUID): ResponseEntity<Void> {
        service.reject(id)
        return ResponseEntity.noContent().build()
    }
}
