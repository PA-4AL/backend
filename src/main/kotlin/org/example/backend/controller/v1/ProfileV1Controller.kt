package org.example.backend.controller.v1

import org.example.backend.model.AddGameAccountRequest
import org.example.backend.model.GameAccountDto
import org.example.backend.model.ProfileDto
import org.example.backend.model.UpdateProfileRequest
import org.example.backend.service.ProfileService
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/** Préfixe `/api/v1` appliqué par `WebMvcConfig` — ne pas l'écrire ici. */
@RestController
@RequestMapping("/me", version = "1+")
class ProfileV1Controller(private val service: ProfileService) {

    @GetMapping
    fun me(@AuthenticationPrincipal jwt: Jwt): ProfileDto =
        service.profile(jwt.subject, pseudo(jwt), jwt.getClaimAsString("email"))

    /** Mise à jour du pseudo et/ou de la photo de profil. */
    @PatchMapping
    fun update(@AuthenticationPrincipal jwt: Jwt, @RequestBody body: UpdateProfileRequest): ProfileDto =
        service.updateProfile(jwt.subject, pseudo(jwt), jwt.getClaimAsString("email"), body.pseudo, body.avatarUrl)

    @PostMapping("/game-accounts")
    fun addGameAccount(
        @AuthenticationPrincipal jwt: Jwt,
        @RequestBody body: AddGameAccountRequest,
    ): ResponseEntity<GameAccountDto> = ResponseEntity.status(HttpStatus.CREATED).body(
        service.addGameAccount(jwt.subject, pseudo(jwt), jwt.getClaimAsString("email"), body.game, body.identifier),
    )

    @DeleteMapping("/game-accounts/{id}")
    fun deleteGameAccount(@AuthenticationPrincipal jwt: Jwt, @PathVariable id: UUID): ResponseEntity<Void> {
        service.deleteGameAccount(jwt.subject, pseudo(jwt), jwt.getClaimAsString("email"), id)
        return ResponseEntity.noContent().build()
    }

    private fun pseudo(jwt: Jwt): String = jwt.getClaimAsString("preferred_username") ?: "Joueur"
}
