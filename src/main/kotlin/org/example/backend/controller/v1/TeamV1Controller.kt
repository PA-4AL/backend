package org.example.backend.controller.v1

import org.example.backend.model.AddMemberRequest
import org.example.backend.model.CreateTeamRequest
import org.example.backend.model.TeamDto
import org.example.backend.service.TeamService
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/** Préfixe `/api/v1` appliqué par `WebMvcConfig` — ne pas l'écrire ici. */
@RestController
@RequestMapping("/teams", version = "1+")
class TeamV1Controller(private val service: TeamService) {

    @GetMapping("/mine")
    fun mine(@AuthenticationPrincipal jwt: Jwt): List<TeamDto> = service.mine(jwt.subject, pseudo(jwt), email(jwt))

    @GetMapping("/{id}")
    fun get(@PathVariable id: UUID): TeamDto = service.get(id)

    @PostMapping
    fun create(@AuthenticationPrincipal jwt: Jwt, @RequestBody req: CreateTeamRequest): ResponseEntity<TeamDto> =
        ResponseEntity.status(HttpStatus.CREATED)
            .body(service.create(req, jwt.subject, pseudo(jwt), email(jwt)))

    @PostMapping("/{id}/members")
    fun addMember(
        @AuthenticationPrincipal jwt: Jwt,
        @PathVariable id: UUID,
        @RequestBody req: AddMemberRequest,
    ): TeamDto = service.addMember(id, req, jwt.subject, pseudo(jwt), email(jwt))

    @DeleteMapping("/{id}/members/{memberId}")
    fun removeMember(
        @AuthenticationPrincipal jwt: Jwt,
        @PathVariable id: UUID,
        @PathVariable memberId: UUID,
    ): TeamDto = service.removeMember(id, memberId, jwt.subject, pseudo(jwt), email(jwt))

    private fun pseudo(jwt: Jwt): String = jwt.getClaimAsString("preferred_username") ?: "Joueur"
    private fun email(jwt: Jwt): String? = jwt.getClaimAsString("email")
}
