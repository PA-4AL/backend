package org.example.backend.model

import java.time.OffsetDateTime
import java.util.UUID

/* ---------------------------------------------------------------------------
   Contrat de messages avec le worker Rust (worker/src/models.rs).
   Toute évolution doit rester synchronisée avec ce fichier.
   --------------------------------------------------------------------------- */

/** Message publié sur `topic-demandes` (backend → worker). */
data class WorkerRequest(val taskId: String, val taskType: String, val payload: Map<String, Any?>)

/** Message reçu depuis `topic-reponses` (worker → backend). */
data class WorkerResponse(
    val taskId: String? = null,
    val taskType: String? = null,
    /** "success" ou "error". */
    val status: String? = null,
    val data: Map<String, Any?>? = null,
    val error: WorkerError? = null,
)

data class WorkerError(val code: String? = null, val message: String? = null, val attempts: Int? = null)

/** Enveloppe d'une livraison push Pub/Sub. */
data class PubSubPushEnvelope(val message: PubSubPushMessage? = null, val subscription: String? = null)

data class PubSubPushMessage(
    /** Contenu du message, encodé en base64. */
    val data: String? = null,
    val messageId: String? = null,
    val publishTime: String? = null,
)

/* ---------------------------------------------------------------------------
   API exposée au frontend
   --------------------------------------------------------------------------- */

/** Les types de tournoi reconnus par les parseurs du worker (worker/src/parser/mod.rs). */
enum class TournamentFileType(val literal: String) {
    ESPORT_5V5("esport_5v5"),
    FOOTBALL_11V11("football_11v11"),
    ;

    companion object {
        fun from(value: String): TournamentFileType? = entries.firstOrNull { it.literal == value }
    }
}

data class ImportTeamsRequest(
    val tournamentType: String,
    /** Contenu du fichier .xlsx encodé en base64 (limite Pub/Sub : 10 Mo par message). */
    val fileBase64: String,
)

data class JobDto(
    val id: UUID,
    val type: String,
    val status: String,
    val error: String? = null,
    val createdAt: OffsetDateTime? = null,
    val finishedAt: OffsetDateTime? = null,
    /** Résultat du traitement une fois le job terminé (équipes importées, fichier exporté…). */
    val result: Map<String, Any?>? = null,
)
