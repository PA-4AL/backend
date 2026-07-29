package org.example.backend.internal

import org.example.backend.config.PubSubProperties
import org.example.backend.error.ErreurMetier
import org.example.backend.error.ErreurTechnique
import org.example.backend.model.PubSubPushEnvelope
import org.example.backend.model.WorkerResponse
import org.example.backend.service.JobService
import org.slf4j.LoggerFactory
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import tools.jackson.databind.ObjectMapper
import java.util.Base64

/**
 * Réception des réponses du worker, poussées par Pub/Sub.
 *
 * Chemin `/internal/v1/jobs/callback` : le 2e segment doit être une version
 * lisible. La résolution de version configurée par `WebMvcConfig`
 * (`usePathSegment(1)`) s'applique à TOUTES les routes servies par le
 * DispatcherServlet, pas seulement à celles sous /api — un `/internal/jobs/...`
 * fait échouer le parsing et renvoie 400 avant d'atteindre ce controller. Le
 * piège est invisible sans jeton : Spring Security répond 401 en amont (cf.
 * CallbackAuthenticatedTests).
 *
 * L'appelant est authentifié par un jeton OIDC signé par Google pour le compte
 * de service configuré dans l'abonnement push : la chaîne de sécurité
 * `/internal` (cf. [org.example.backend.config.SecurityConfig]) vérifie
 * l'émetteur et l'audience, et on contrôle ici l'identité exacte de l'appelant.
 *
 * Codes de retour : 2xx acquitte le message, 4xx l'abandonne (message
 * définitivement invalide), 5xx demande une nouvelle tentative à Pub/Sub. Un
 * message inexploitable ne doit donc jamais renvoyer 500, sinon il est rejoué
 * jusqu'à la file de rebut.
 */
@RestController
class JobCallbackController(
    private val jobs: JobService,
    private val props: PubSubProperties,
    private val mapper: ObjectMapper,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    @PostMapping("/internal/v1/jobs/callback")
    fun callback(@AuthenticationPrincipal jwt: Jwt?, @RequestBody envelope: PubSubPushEnvelope): ResponseEntity<Void> {
        verifyCaller(jwt)

        val encoded = envelope.message?.data
            ?: throw ErreurMetier.Invalide("Message sans données")

        val response = try {
            val json = String(Base64.getDecoder().decode(encoded))
            mapper.readValue(json, WorkerResponse::class.java)
        } catch (e: Exception) {
            // Message illisible : on l'acquitte pour ne pas boucler, mais on trace.
            log.error("Message de réponse illisible (id {}), abandonné", envelope.message.messageId, e)
            throw ErreurMetier.Invalide("Message illisible")
        }

        jobs.applyWorkerResponse(response)
        return ResponseEntity.noContent().build()
    }

    /**
     * Le compte de service attendu est celui configuré dans l'abonnement push.
     * Sans cette vérification, tout détenteur d'un jeton Google valide pourrait
     * écrire dans les jobs.
     */
    private fun verifyCaller(jwt: Jwt?) {
        val expected = props.pushServiceAccount
        if (expected.isBlank()) {
            // Configuration incomplète : on refuse plutôt que d'ouvrir l'endpoint.
            throw ErreurTechnique.ServiceIndisponible("Callback non configuré")
        }
        val email = jwt?.getClaimAsString("email")
        if (email != expected) {
            log.warn("Appel du callback refusé (email du jeton : {})", email)
            throw ErreurMetier.NonAutorise("Appelant non autorisé")
        }
    }
}
