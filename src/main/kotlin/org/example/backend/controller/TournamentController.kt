package org.example.backend.controller

import org.example.backend.model.CreateTournamentRequest
import org.example.backend.model.TournamentDetailDto
import org.example.backend.model.TournamentSummaryDto
import org.example.backend.service.TournamentService
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.net.URI
import java.util.UUID

@RestController
@RequestMapping("/api/tournaments")
class TournamentController(private val service: TournamentService) {

    /** Consultation publique (spec : rôle Visiteur). */
    @GetMapping
    fun list(): List<TournamentSummaryDto> = service.list()

    @GetMapping("/{id}")
    fun get(@PathVariable id: UUID): ResponseEntity<TournamentDetailDto> =
        service.get(id)?.let { ResponseEntity.ok(it) } ?: ResponseEntity.notFound().build()

    /** Création — réservée aux comptes authentifiés via Keycloak. */
    @PostMapping
    fun create(
        @RequestBody req: CreateTournamentRequest,
        @AuthenticationPrincipal jwt: Jwt,
    ): ResponseEntity<TournamentSummaryDto> {
        val created = service.create(
            req = req,
            keycloakId = jwt.subject,
            pseudo = jwt.getClaimAsString("preferred_username") ?: "Organisateur",
            email = jwt.getClaimAsString("email"),
        )
        return ResponseEntity.created(URI.create("/api/tournaments/${created.id}")).body(created)
    }
}
