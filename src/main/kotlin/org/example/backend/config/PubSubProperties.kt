package org.example.backend.config

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Configuration de la messagerie asynchrone avec le worker Rust.
 *
 * `enabled = false` (défaut en local) : les traitements Excel sont refusés
 * proprement au lieu de tenter un appel Pub/Sub sans identifiants.
 */
@ConfigurationProperties(prefix = "app.pubsub")
data class PubSubProperties(
    val enabled: Boolean = false,
    /** Projet GCP hébergeant les topics. */
    val projectId: String = "",
    /** Nom complet du topic des demandes : projects/<projet>/topics/<topic>. */
    val topicDemands: String = "",
    /**
     * Compte de service dont Pub/Sub signe les jetons OIDC des livraisons push,
     * et audience attendue dans ces jetons. Le callback rejette tout le reste.
     */
    val pushServiceAccount: String = "",
    val pushAudience: String = "",
)
