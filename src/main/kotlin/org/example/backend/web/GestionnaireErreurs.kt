package org.example.backend.web

import org.example.backend.error.ErreurMetier
import org.example.backend.error.ErreurTechnique
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.security.access.AccessDeniedException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.context.request.WebRequest
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler

/**
 * Frontière entre le domaine et HTTP.
 *
 * C'est **le seul endroit** du backend où une erreur métier devient un statut
 * HTTP. Les services lèvent des [ErreurMetier] ou des [ErreurTechnique] sans
 * jamais connaître HTTP (`docs/adr/0007-erreurs-metier-et-http.md`).
 *
 * Le corps renvoyé porte un champ `message` : c'est ce que lit le frontend
 * (`src/api/client.ts`) pour afficher l'erreur à l'utilisateur. Auparavant, avec
 * `ResponseStatusException`, le message était perdu en route — Spring ne l'inclut
 * pas sans `server.error.include-message`.
 */
@RestControllerAdvice
class GestionnaireErreurs : ResponseEntityExceptionHandler() {

    private val log = LoggerFactory.getLogger(javaClass)

    @ExceptionHandler(ErreurMetier::class)
    fun erreurMetier(erreur: ErreurMetier, requete: WebRequest): ProblemDetail {
        val statut = when (erreur) {
            is ErreurMetier.Introuvable -> HttpStatus.NOT_FOUND
            is ErreurMetier.Invalide -> HttpStatus.BAD_REQUEST
            is ErreurMetier.Conflit -> HttpStatus.CONFLICT
            is ErreurMetier.NonAutorise -> HttpStatus.FORBIDDEN
            is ErreurMetier.TropVolumineux -> HttpStatus.PAYLOAD_TOO_LARGE
        }
        // Une erreur métier est un cas de fonctionnement normal : niveau INFO, et
        // pas de pile d'appels dans les logs.
        log.info("Erreur métier ({}) : {}", statut.value(), erreur.message)
        return corps(statut, erreur.message, erreur.code())
    }

    @ExceptionHandler(ErreurTechnique::class)
    fun erreurTechnique(erreur: ErreurTechnique, requete: WebRequest): ProblemDetail {
        val statut = when (erreur) {
            is ErreurTechnique.ServiceIndisponible -> HttpStatus.SERVICE_UNAVAILABLE
            is ErreurTechnique.DependanceEnEchec -> HttpStatus.BAD_GATEWAY
        }
        // Celles-ci sont anormales : pile d'appels conservée pour le diagnostic.
        log.error("Erreur technique ({})", statut.value(), erreur)
        return corps(statut, erreur.message, erreur.code())
    }

    /**
     * Refus d'autorisation levé par un `@PreAuthorize`.
     *
     * Sans ce gestionnaire, l'exception traverse le DispatcherServlet et tombe
     * dans le filet de sécurité ci-dessous : un refus de droits légitime devenait
     * alors un **500**. Le filtre de traduction de Spring Security, lui, n'est
     * jamais atteint puisque l'advice intercepte avant.
     */
    @ExceptionHandler(AccessDeniedException::class)
    fun accesRefuse(erreur: AccessDeniedException, requete: WebRequest): ProblemDetail {
        log.info("Accès refusé sur {}", requete.getDescription(false))
        return corps(HttpStatus.FORBIDDEN, "Vous n'avez pas les droits nécessaires.", "NON_AUTORISE")
    }

    /**
     * Filet de sécurité pour l'imprévu. Le message n'est **pas** renvoyé au
     * client : il pourrait exposer un détail d'implémentation ou une requête SQL.
     *
     * Les exceptions propres à Spring MVC (corps illisible, méthode non
     * supportée, type de média refusé…) ne passent pas ici : elles sont traitées
     * par les gestionnaires plus spécifiques héritées de
     * [ResponseEntityExceptionHandler], qui leur rendent leur statut correct.
     */
    @ExceptionHandler(Exception::class)
    fun inattendue(erreur: Exception, requete: WebRequest): ProblemDetail {
        log.error("Erreur inattendue sur {}", requete.getDescription(false), erreur)
        return corps(
            HttpStatus.INTERNAL_SERVER_ERROR,
            "Une erreur inattendue est survenue.",
            "ERREUR_INTERNE",
        )
    }

    private fun corps(statut: HttpStatus, message: String?, code: String): ProblemDetail =
        ProblemDetail.forStatus(statut).apply {
            title = statut.reasonPhrase
            detail = message
            // `message` en plus de `detail` : c'est la clé que lit le frontend.
            setProperty("message", message ?: "")
            setProperty("code", code)
        }
}

/** Code fonctionnel stable, indépendant du statut HTTP, exploitable côté client. */
private fun ErreurMetier.code(): String = when (this) {
    is ErreurMetier.Introuvable -> "INTROUVABLE"
    is ErreurMetier.Invalide -> "INVALIDE"
    is ErreurMetier.Conflit -> "CONFLIT"
    is ErreurMetier.NonAutorise -> "NON_AUTORISE"
    is ErreurMetier.TropVolumineux -> "TROP_VOLUMINEUX"
}

private fun ErreurTechnique.code(): String = when (this) {
    is ErreurTechnique.ServiceIndisponible -> "SERVICE_INDISPONIBLE"
    is ErreurTechnique.DependanceEnEchec -> "DEPENDANCE_EN_ECHEC"
}
