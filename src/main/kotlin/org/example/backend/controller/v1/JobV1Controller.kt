package org.example.backend.controller.v1

import org.example.backend.model.ImportTeamsRequest
import org.example.backend.model.JobDto
import org.example.backend.repository.RegistrationRepository
import org.example.backend.service.JobService
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

/**
 * Import et export Excel (spec §4.3) — traitements asynchrones délégués au
 * worker Rust via Pub/Sub. Les endpoints répondent immédiatement avec un job à
 * suivre ; le frontend interroge ensuite `GET /api/v1/jobs/{id}`.
 *
 * Le préfixe `/api/{version}` est appliqué par `config/WebMvcConfig.kt` à tous
 * les controllers de ce paquet : les chemins déclarés ici sont donc nus.
 */
@RestController
@RequestMapping(version = "1+")
class JobV1Controller(private val jobs: JobService, private val registrations: RegistrationRepository) {

    @PostMapping("/teams/import")
    @PreAuthorize("hasAnyRole('organizer', 'admin')")
    fun importTeams(@AuthenticationPrincipal jwt: Jwt, @RequestBody body: ImportTeamsRequest): JobDto =
        jobs.submitTeamImport(body, currentUserId(jwt))

    @GetMapping("/jobs/{id}")
    fun status(@PathVariable id: UUID): JobDto = jobs.get(id)

    /** Identifiant interne de l'utilisateur, rattaché à son compte Keycloak. */
    private fun currentUserId(jwt: Jwt): UUID = registrations.upsertUserByKeycloak(
        jwt.subject,
        jwt.getClaimAsString("preferred_username") ?: "Joueur",
        jwt.getClaimAsString("email"),
    )
}
