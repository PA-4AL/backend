package org.example.backend.service

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Régression : la publication échouait en production avec un 404 HTML de Google.
 *
 *     The requested URL /v1/projects%2Fpa-tournament-4al%2Ftopics%2Fpa-prod-demandes:publish
 *     was not found on this server.
 *
 * Le nom du topic était passé en **variable de gabarit** d'URI (`{topic}`), or
 * une variable de gabarit voit ses `/` encodés — et un nom de topic Pub/Sub est
 * un chemin complet. L'URL produite ne correspondait à aucune route.
 *
 * Ce défaut a survécu longtemps parce que **rien n'emprunte ce chemin sans une
 * action utilisateur** : l'import Excel n'avait jamais été lancé en production, et
 * la validation de la chaîne s'était faite en injectant un message avec `gcloud`,
 * ce qui ne teste que le sens worker → backend. Un test rend la vérification
 * gratuite et permanente.
 */
class PubSubPublisherTest {

    private val topic = "projects/pa-tournament-4al/topics/pa-prod-demandes"

    @Test
    fun `l'url conserve les separateurs du nom de topic`() {
        val uri = PubSubPublisher.uriDePublication(topic)

        assertEquals(
            "https://pubsub.googleapis.com/v1/projects/pa-tournament-4al/topics/pa-prod-demandes:publish",
            uri.toString(),
        )
        // Le symptôme exact du défaut, nommé pour qu'une régression soit lisible.
        assertFalse(uri.toString().contains("%2F"), "les / du nom de topic ont été encodés")
    }

    @Test
    fun `l'url cible bien la methode publish de l'api v1`() {
        val uri = PubSubPublisher.uriDePublication(topic)

        assertEquals("pubsub.googleapis.com", uri.host)
        assertTrue(uri.path.startsWith("/v1/"), "chemin inattendu : ${uri.path}")
        assertTrue(uri.toString().endsWith(":publish"))
    }

    @Test
    fun `un autre environnement produit une url coherente`() {
        // Le préfixe d'environnement fait partie du nom du topic : dev et prod ne
        // doivent pas se retrouver à publier au même endroit.
        val dev = PubSubPublisher.uriDePublication("projects/p/topics/pa-dev-demandes")

        assertTrue(dev.toString().endsWith("/v1/projects/p/topics/pa-dev-demandes:publish"))
    }
}
