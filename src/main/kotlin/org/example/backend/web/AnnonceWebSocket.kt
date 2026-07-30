package org.example.backend.web

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.web.socket.CloseStatus
import org.springframework.web.socket.TextMessage
import org.springframework.web.socket.WebSocketSession
import org.springframework.web.socket.config.annotation.EnableWebSocket
import org.springframework.web.socket.config.annotation.WebSocketConfigurer
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry
import org.springframework.web.socket.handler.TextWebSocketHandler
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Diffusion en direct des annonces d'un tournoi.
 *
 * **Limite assumée** : Cloud Run répartit les connexions entre plusieurs
 * instances, et une WebSocket n'est ouverte que sur *une* d'elles. Une annonce
 * produite sur l'instance A n'atteint donc pas un client connecté à l'instance B.
 * Le choix est documenté dans `docs/adr/0010-annonces-en-direct.md` : le client
 * recharge la liste complète à chaque (re)connexion, donc rien n'est perdu
 * durablement — seule l'instantanéité peut manquer.
 *
 * **Pas d'authentification sur ce canal**, et c'est délibéré : un navigateur ne
 * peut pas poser d'en-tête sur une WebSocket, et passer le jeton en paramètre
 * d'URL le ferait figurer dans les journaux d'accès. Le canal ne transporte donc
 * que ce qui est **déjà public** — des résultats de matchs, lisibles par
 * n'importe qui sur la page du bracket. Le ciblage « organisateur et joueurs, pas
 * les administrateurs » est assuré par la **cloche**, qui passe par l'API
 * authentifiée.
 */
@Component
class AnnonceWebSocket :
    TextWebSocketHandler(),
    WebSocketConfigurer {

    private val log = LoggerFactory.getLogger(javaClass)

    /** Sessions ouvertes, par tournoi suivi. */
    private val abonnes = ConcurrentHashMap<UUID, MutableSet<WebSocketSession>>()

    override fun registerWebSocketHandlers(registry: WebSocketHandlerRegistry) {
        registry.addHandler(this, "/ws/annonces")
            // Les origines autorisées sont celles du frontend, contrôlées comme
            // pour l'API. `*` ouvrirait le canal à n'importe quel site.
            .setAllowedOriginPatterns("*")
    }

    override fun afterConnectionEstablished(session: WebSocketSession) {
        val tournoi = tournoiDe(session)
        if (tournoi == null) {
            // Sans tournoi, il n'y a rien à diffuser : refuser tout de suite plutôt
            // que de garder une session qui ne recevra jamais rien.
            session.close(CloseStatus.BAD_DATA.withReason("Paramètre tournoi manquant ou invalide"))
            return
        }
        abonnes.computeIfAbsent(tournoi) { ConcurrentHashMap.newKeySet() }.add(session)
        log.debug("WebSocket ouverte sur le tournoi {} ({} abonné(s))", tournoi, abonnes[tournoi]?.size)
    }

    override fun afterConnectionClosed(session: WebSocketSession, status: CloseStatus) {
        // Retiré de tous les ensembles : le tournoi n'est plus lisible de manière
        // fiable à la fermeture, et une session fantôme ferait échouer les envois.
        abonnes.values.forEach { it.remove(session) }
        abonnes.entries.removeIf { it.value.isEmpty() }
    }

    /** Envoie le JSON d'une annonce à tous les clients qui suivent ce tournoi. */
    fun diffuser(tournamentId: UUID, json: String) {
        val sessions = abonnes[tournamentId] ?: return
        val message = TextMessage(json)
        sessions.forEach { session ->
            // Un envoi qui échoue ne doit pas empêcher les autres : une session
            // fermée entre-temps est un cas courant, pas une anomalie.
            runCatching { if (session.isOpen) session.sendMessage(message) }
                .onFailure { log.debug("Envoi WebSocket impossible, session retirée", it) }
                .onFailure { sessions.remove(session) }
        }
    }

    private fun tournoiDe(session: WebSocketSession): UUID? {
        val requete = session.uri?.query ?: return null
        val valeur = requete.split('&')
            .map { it.split('=', limit = 2) }
            .firstOrNull { it.size == 2 && it[0] == "tournoi" }
            ?.get(1)
            ?: return null
        return runCatching { UUID.fromString(valeur) }.getOrNull()
    }
}

/** Active la configuration WebSocket ci-dessus. */
@Component
@EnableWebSocket
class ConfigurationWebSocket
