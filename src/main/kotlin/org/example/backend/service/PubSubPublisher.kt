package org.example.backend.service

import com.google.auth.oauth2.GoogleCredentials
import org.example.backend.config.PubSubProperties
import org.example.backend.error.ErreurTechnique
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import java.net.URI
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
            throw ErreurTechnique.ServiceIndisponible(
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
                .uri(uriDePublication(props.topicDemands))
                .header("Authorization", "Bearer ${credentials.accessToken.tokenValue}")
                .body(body)
                .retrieve()
                .body(PublishResponse::class.java)

            val id = response?.messageIds?.firstOrNull()
                ?: throw IllegalStateException("Pub/Sub n'a retourné aucun identifiant de message")
            log.info("Demande publiée sur {} (message {})", props.topicDemands, id)
            id
        } catch (e: ErreurTechnique) {
            throw e
        } catch (e: Exception) {
            // La cause est journalisée ici, mais n'est pas exposée au client :
            // c'est le gestionnaire d'erreurs qui décide de ce qui sort.
            log.error("Publication Pub/Sub en échec sur {}", props.topicDemands, e)
            throw ErreurTechnique.DependanceEnEchec("Pub/Sub", e)
        }
    }

    data class PublishResponse(val messageIds: List<String>? = null)

    companion object {
        /**
         * URL de publication d'un topic.
         *
         * Construite en [URI] et **non** par un gabarit `{topic}` : une variable
         * de gabarit voit ses `/` encodés en `%2F`, et le nom d'un topic Pub/Sub
         * est un chemin complet (`projects/…/topics/…`). L'URL produite ne
         * correspondait alors à aucune route de l'API, qui répondait un 404 HTML :
         *
         *     /v1/projects%2Fpa-tournament-4al%2Ftopics%2Fpa-prod-demandes:publish
         *
         * Le défaut est resté invisible longtemps parce que **rien n'empruntait
         * ce chemin en production** : l'import Excel n'avait jamais été lancé, et
         * la validation de la chaîne s'était faite en injectant un message avec
         * `gcloud`, ce qui ne teste que le sens worker → backend.
         */
        fun uriDePublication(topic: String): URI = URI.create("https://pubsub.googleapis.com/v1/$topic:publish")
    }
}
