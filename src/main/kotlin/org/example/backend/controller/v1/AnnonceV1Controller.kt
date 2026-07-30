package org.example.backend.controller.v1

import org.example.backend.model.AnnonceDto
import org.example.backend.model.MesAnnoncesDto
import org.example.backend.repository.RegistrationRepository
import org.example.backend.service.AnnonceService
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * Annonces d'un tournoi (spec : suivi en direct).
 *
 * Le préfixe `/api/v1` est appliqué par `WebMvcConfig`. Le flux en direct passe
 * par la WebSocket `/ws/annonces?tournoi=<id>`, hors DispatcherServlet.
 */
@RestController
@RequestMapping(version = "1+")
class AnnonceV1Controller(private val service: AnnonceService, private val registrations: RegistrationRepository) {

    /**
     * Annonces d'un tournoi — **publiques**, comme le bracket qu'elles commentent.
     * Les restreindre n'aurait pas de sens : elles ne disent rien de plus qu'un
     * spectateur ne lit déjà sur l'arbre.
     */
    @GetMapping("/tournaments/{id}/announcements")
    fun duTournoi(@PathVariable id: UUID): List<AnnonceDto> = service.duTournoi(id)

    /**
     * Cloche du lecteur : les annonces des tournois où il est engagé.
     *
     * C'est ici que se joue le ciblage « organisateur et joueurs, pas les
     * administrateurs » : un admin n'est pas abonné aux tournois qu'il n'organise
     * pas — il en recevrait des centaines et n'en lirait aucune.
     */
    @GetMapping("/announcements")
    fun miennes(@AuthenticationPrincipal jwt: Jwt): MesAnnoncesDto {
        val (annonces, nonLues) = service.pourUtilisateur(currentUserId(jwt))
        return MesAnnoncesDto(annonces, nonLues)
    }

    /** Remet le compteur de non-lues à zéro. */
    @PostMapping("/announcements/seen")
    fun marquerLues(@AuthenticationPrincipal jwt: Jwt): ResponseEntity<Void> {
        service.marquerLues(currentUserId(jwt))
        return ResponseEntity.noContent().build()
    }

    private fun currentUserId(jwt: Jwt): UUID = registrations.upsertUserByKeycloak(
        jwt.subject,
        jwt.getClaimAsString("preferred_username") ?: "Joueur",
        jwt.getClaimAsString("email"),
    )
}
