package org.example.backend.model

import com.fasterxml.jackson.annotation.JsonProperty
import java.time.OffsetDateTime
import java.util.UUID

/* ---------------------------------------------------------------------------
   Contrat de messages avec le worker Rust (worker/src/models.rs).
   Toute évolution doit rester synchronisée avec ce fichier.
   --------------------------------------------------------------------------- */

/**
 * Message publié sur `topic-demandes` (backend → worker).
 *
 * Le worker attend du snake_case (`task_id`, `task_type`) : les annotations sont
 * la seule source de vérité de ce contrat, dans les deux sens.
 */
data class WorkerRequest(
    @param:JsonProperty("task_id") @get:JsonProperty("task_id")
    val taskId: String,
    @param:JsonProperty("task_type") @get:JsonProperty("task_type")
    val taskType: String,
    val payload: Map<String, Any?>,
)

/** Message reçu depuis `topic-reponses` (worker → backend). */
data class WorkerResponse(
    @param:JsonProperty("task_id") @get:JsonProperty("task_id")
    val taskId: String? = null,
    @param:JsonProperty("task_type") @get:JsonProperty("task_type")
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
    /**
     * Tournoi où inscrire les équipes importées.
     *
     * Facultatif : sans lui, les équipes et les joueurs sont bien créés mais
     * n'apparaissent dans aucun tournoi — donc pas dans un export, qui est
     * toujours celui d'un tournoi. C'est le comportement qu'avait l'import
     * jusqu'ici, faute de pouvoir désigner la cible.
     */
    val tournamentId: UUID? = null,
    /**
     * Correspondance « colonne logique → lettre Excel », ex.
     * `{"Équipe": "A", "Pseudo": "C", "Rang": "D"}`.
     *
     * Facultative. Sans elle, le worker retrouve les colonnes par leur en-tête,
     * ce qui impose au fichier de porter exactement les libellés attendus. Un
     * organisateur reçoit ses inscriptions dans le format de son choix : lui
     * demander de renommer ses colonnes avant d'importer est une friction
     * évitable dès lors qu'il peut désigner lesquelles utiliser.
     */
    val columns: Map<String, String>? = null,
    /**
     * La première ligne est-elle une ligne d'en-têtes ? Vrai par défaut.
     * N'a de sens qu'avec [columns] : sans correspondance explicite, l'en-tête
     * est le seul moyen d'identifier les colonnes.
     */
    val hasHeader: Boolean? = null,
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
