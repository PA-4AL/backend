package org.example.backend.controller

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
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * Import et export Excel (spec §4.3) — traitements asynchrones délégués au
 * worker Rust. Les endpoints répondent immédiatement avec un job à suivre ; le
 * frontend interroge ensuite `GET /api/jobs/{id}`.
 */
@RestController
class JobController(private val jobs: JobService, private val registrations: RegistrationRepository) {

    @PostMapping("/api/teams/import")
    @PreAuthorize("hasAnyRole('organizer', 'admin')")
    fun importTeams(@AuthenticationPrincipal jwt: Jwt, @RequestBody body: ImportTeamsRequest): JobDto =
        jobs.submitTeamImport(body, currentUserId(jwt))

    @GetMapping("/api/jobs/{id}")
    fun status(@PathVariable id: UUID): JobDto = jobs.get(id)

    /** Identifiant interne de l'utilisateur, rattaché à son compte Keycloak. */
    private fun currentUserId(jwt: Jwt): UUID = registrations.upsertUserByKeycloak(
        jwt.subject,
        jwt.getClaimAsString("preferred_username") ?: "Joueur",
        jwt.getClaimAsString("email"),
    )
}
