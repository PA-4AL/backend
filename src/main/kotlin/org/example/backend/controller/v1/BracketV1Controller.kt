package org.example.backend.controller.v1

import org.example.backend.model.BracketDto
import org.example.backend.model.GenerateBracketRequest
import org.example.backend.model.ScoreRequest
import org.example.backend.model.SwapSlotsRequest
import org.example.backend.repository.RegistrationRepository
import org.example.backend.service.BracketService
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
 * Pas de chemin de classe : ce controller couvre deux familles de routes
 * (`/tournaments/…/bracket` et `/matches/…/score`). Le `@RequestMapping` de classe
 * ne porte donc que la version, héritée par toutes les méthodes.
 * Préfixe `/api/v1` appliqué par `WebMvcConfig`.
 *
 * **Deux niveaux d'autorisation**, et les deux sont nécessaires :
 * `@PreAuthorize` vérifie le rôle porté par le jeton, le service vérifie que
 * l'appelant est organisateur **de ce tournoi précis**. Le rôle seul autorisait
 * n'importe quel organisateur à modifier le tournoi d'un autre.
 */
@RestController
@RequestMapping(version = "1+")
class BracketV1Controller(private val service: BracketService, private val registrations: RegistrationRepository) {

    /** Consultation publique du bracket (spec : Visiteur). */
    @GetMapping("/tournaments/{id}/bracket")
    fun get(@PathVariable id: UUID): BracketDto = service.getBracket(id)

    /** Génération / re-génération de l'arbre. */
    @PostMapping("/tournaments/{id}/bracket/generate")
    @PreAuthorize("hasAnyRole('organizer', 'admin')")
    fun generate(
        @AuthenticationPrincipal jwt: Jwt,
        @PathVariable id: UUID,
        @RequestBody(required = false) req: GenerateBracketRequest?,
    ): BracketDto = service.generate(id, currentUserId(jwt), estAdmin(jwt), req?.format)

    /**
     * Échange deux emplacements de l'arbre — placement manuel des participants.
     *
     * Un emplacement est un match et un slot (1 ou 2), tel qu'affiché à l'écran.
     * Échanger avec un emplacement vide déplace le participant.
     */
    @PostMapping("/tournaments/{id}/bracket/swap")
    @PreAuthorize("hasAnyRole('organizer', 'admin')")
    fun swap(
        @AuthenticationPrincipal jwt: Jwt,
        @PathVariable id: UUID,
        @RequestBody req: SwapSlotsRequest,
    ): BracketDto = service.echangerEmplacements(
        req.fromMatchId,
        req.fromSlot,
        req.toMatchId,
        req.toSlot,
        currentUserId(jwt),
        estAdmin(jwt),
    )

    /**
     * Démarre un match : le passe en cours et publie l'annonce « Début du match ».
     *
     * Une action explicite, parce que **les matchs d'un même tour ne se jouent pas
     * en même temps** : les annoncer tous dès qu'ils deviennent jouables serait
     * faux.
     */
    @PostMapping("/matches/{matchId}/start")
    @PreAuthorize("hasAnyRole('organizer', 'admin')")
    fun demarrer(@AuthenticationPrincipal jwt: Jwt, @PathVariable matchId: UUID): BracketDto =
        service.demarrerMatch(matchId, currentUserId(jwt), estAdmin(jwt))

    /** Saisie du score d'un match, avec propagation du vainqueur. */
    @PostMapping("/matches/{matchId}/score")
    @PreAuthorize("hasAnyRole('organizer', 'admin')")
    fun reportScore(
        @AuthenticationPrincipal jwt: Jwt,
        @PathVariable matchId: UUID,
        @RequestBody score: ScoreRequest,
    ): BracketDto = service.reportScore(
        matchId,
        score.scoreA,
        score.scoreB,
        currentUserId(jwt),
        estAdmin(jwt),
    )

    /** Identifiant interne de l'utilisateur, rattaché à son compte Keycloak. */
    private fun currentUserId(jwt: Jwt): UUID = registrations.upsertUserByKeycloak(
        jwt.subject,
        jwt.getClaimAsString("preferred_username") ?: "Joueur",
        jwt.getClaimAsString("email"),
    )

    /**
     * L'administrateur plateforme passe outre le contrôle de propriété : c'est son
     * rôle de modération globale, et il doit pouvoir intervenir sur un tournoi
     * abandonné par son organisateur.
     */
    private fun estAdmin(jwt: Jwt): Boolean =
        (jwt.getClaimAsMap("realm_access")?.get("roles") as? List<*>)?.contains("admin") == true
}
