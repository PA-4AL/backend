package org.example.backend.controller.v1

import org.example.backend.model.CreateTournamentRequest
import org.example.backend.model.TournamentDetailDto
import org.example.backend.model.TournamentSummaryDto
import org.example.backend.repository.RegistrationRepository
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
import org.springframework.web.servlet.support.ServletUriComponentsBuilder
import java.util.UUID

/** Préfixe `/api/v1` appliqué par `WebMvcConfig` — ne pas l'écrire ici. */
@RestController
@RequestMapping("/tournaments", version = "1+")
class TournamentV1Controller(
    private val service: TournamentService,
    private val registrations: RegistrationRepository,
) {

    /**
     * Consultation publique (spec : rôle Visiteur), **annotée si l'on est connu**.
     *
     * Le jeton est optionnel : la route reste ouverte aux visiteurs. Quand il est
     * présent, chaque tournoi indique si le lecteur l'organise et s'il y participe,
     * ce qui permet à l'interface de séparer ses tournois de ceux où il peut encore
     * s'inscrire — sans recouper les inscriptions côté client.
     */
    @GetMapping
    fun list(@AuthenticationPrincipal jwt: Jwt?): List<TournamentSummaryDto> =
        service.list(jwt?.let { currentUserId(it) })

    @GetMapping("/{id}")
    fun get(@AuthenticationPrincipal jwt: Jwt?, @PathVariable id: UUID): ResponseEntity<TournamentDetailDto> =
        service.get(id, jwt?.let { currentUserId(it) })
            ?.let { ResponseEntity.ok(it) }
            ?: ResponseEntity.notFound().build()

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
        // Construit depuis la requête courante : l'en-tête reste juste quelle que
        // soit la version appelée.
        val location = ServletUriComponentsBuilder.fromCurrentRequestUri()
            .path("/{id}")
            .buildAndExpand(created.id)
            .toUri()
        return ResponseEntity.created(location).body(created)
    }

    /** Identifiant interne du lecteur, rattaché à son compte Keycloak. */
    private fun currentUserId(jwt: Jwt): UUID = registrations.upsertUserByKeycloak(
        jwt.subject,
        jwt.getClaimAsString("preferred_username") ?: "Joueur",
        jwt.getClaimAsString("email"),
    )
}
