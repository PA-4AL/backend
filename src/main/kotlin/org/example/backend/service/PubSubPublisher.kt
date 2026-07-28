package org.example.backend.service

import com.google.auth.oauth2.GoogleCredentials
import org.example.backend.config.PubSubProperties
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import org.springframework.web.server.ResponseStatusException
import java.util.Base64

/**
 * Publication d'un message sur un topic Pub/Sub, via l'API REST.
 *
 * Choix : l'API REST + `google-auth-library` plutôt que le client
 * `google-cloud-pubsub`, qui embarque toute la pile gRPC/Netty (~40 Mo de
 * dépendances) pour un unique appel de publication. Ici il n'y a qu'un POST
 * signé par les identifiants par défaut de l'environnement (ADC) : sur Cloud Run,
 * l'identité d'exécution du service, sans aucune clé à gérer.
 */
@Component
class PubSubPublisher(private val props: PubSubProperties) {

    private val log = LoggerFactory.getLogger(javaClass)
    private val rest = RestClient.create()

    // Les identifiants sont résolus paresseusement : sans ADC (poste de dev),
    // le démarrage de l'application ne doit pas échouer.
    private val credentials: GoogleCredentials by lazy {
        GoogleCredentials.getApplicationDefault()
            .createScoped("https://www.googleapis.com/auth/pubsub")
    }

    val enabled: Boolean get() = props.enabled

    /** Publie `json` sur le topic des demandes et renvoie l'identifiant du message. */
    fun publishDemand(json: String): String {
        if (!props.enabled) {
            throw ResponseStatusException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "Traitement asynchrone indisponible : messagerie non configurée",
            )
        }

        val body = mapOf(
            "messages" to listOf(
                mapOf("data" to Base64.getEncoder().encodeToString(json.toByteArray())),
            ),
        )

        return try {
            credentials.refreshIfExpired()
            val response = rest.post()
                .uri("https://pubsub.googleapis.com/v1/{topic}:publish", props.topicDemands)
                .header("Authorization", "Bearer ${credentials.accessToken.tokenValue}")
                .body(body)
                .retrieve()
                .body(PublishResponse::class.java)

            val id = response?.messageIds?.firstOrNull()
                ?: throw IllegalStateException("Pub/Sub n'a retourné aucun identifiant de message")
            log.info("Demande publiée sur {} (message {})", props.topicDemands, id)
            id
        } catch (e: ResponseStatusException) {
            throw e
        } catch (e: Exception) {
            log.error("Publication Pub/Sub en échec sur {}", props.topicDemands, e)
            throw ResponseStatusException(
                HttpStatus.BAD_GATEWAY,
                "Impossible de transmettre la demande au worker",
                e,
            )
        }
    }

    data class PublishResponse(val messageIds: List<String>? = null)
}
