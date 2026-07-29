package org.example.backend.service

import org.example.backend.database.enums.JobStatus
import org.example.backend.database.enums.JobType
import org.example.backend.error.ErreurMetier
import org.example.backend.model.ImportTeamsRequest
import org.example.backend.model.JobDto
import org.example.backend.model.TournamentFileType
import org.example.backend.model.WorkerRequest
import org.example.backend.model.WorkerResponse
import org.example.backend.repository.JobRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper
import java.util.UUID

/**
 * Traitements Excel délégués au worker Rust (spec §4.3).
 *
 * Enchaînement : on trace la demande dans `jobs`, on publie sur `topic-demandes`,
 * et le worker répond sur `topic-reponses` — livré par Pub/Sub en push sur
 * `/internal/jobs/callback`, qui appelle [applyWorkerResponse].
 *
 * Le nom du traitement diffère de part et d'autre : la base parle de
 * `team_import` / `team_export` (énumération `job_type`), le worker de
 * `import_excel` / `export_excel` (`task_type`). La correspondance est faite ici,
 * à un seul endroit.
 */
@Service
class JobService(
    private val jobs: JobRepository,
    private val publisher: PubSubPublisher,
    private val mapper: ObjectMapper,
    private val imports: ImportService,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    private companion object {
        const val TASK_IMPORT = "import_excel"
        const val TASK_EXPORT = "export_excel"

        /** Limite Pub/Sub : 10 Mo par message, base64 comprise. */
        const val MAX_PAYLOAD_BYTES = 9_000_000
    }

    /** Soumet un import d'équipes et renvoie le job créé (statut `pending`). */
    @Transactional
    fun submitTeamImport(request: ImportTeamsRequest, createdBy: UUID?): JobDto {
        TournamentFileType.from(request.tournamentType)
            ?: throw ErreurMetier.Invalide(
                "Type de tournoi inconnu : ${request.tournamentType} " +
                    "(attendu : ${TournamentFileType.entries.joinToString { it.literal }})",
            )

        if (request.fileBase64.isBlank()) {
            throw ErreurMetier.Invalide("Fichier absent")
        }
        if (request.fileBase64.length > MAX_PAYLOAD_BYTES) {
            throw ErreurMetier.TropVolumineux(
                "Fichier trop volumineux : la limite est de 10 Mo par message",
            )
        }

        // `tournament_id` est ignoré par le worker (serde ignore l'inconnu) mais
        // conservé dans le payload : c'est ainsi qu'il revient à la matérialisation,
        // qui n'a pas d'autre moyen de savoir où inscrire les équipes.
        val payload = mapOf(
            "tournament_type" to request.tournamentType,
            "file_base64" to request.fileBase64,
            "tournament_id" to request.tournamentId?.toString(),
        )
        return submit(JobType.team_import, TASK_IMPORT, payload, createdBy)
    }

    /** Soumet un export de l'état d'un tournoi (équipes et matchs déjà résolus). */
    @Transactional
    fun submitTournamentExport(
        tournamentType: String,
        tournamentName: String,
        teams: List<Map<String, Any?>>,
        matches: List<Map<String, Any?>>,
        createdBy: UUID?,
    ): JobDto {
        TournamentFileType.from(tournamentType)
            ?: throw ErreurMetier.Invalide(
                "Type de tournoi inconnu : $tournamentType",
            )

        val payload = mapOf(
            "tournament_type" to tournamentType,
            "tournament_name" to tournamentName,
            "teams" to teams,
            "matches" to matches,
        )
        return submit(JobType.team_export, TASK_EXPORT, payload, createdBy)
    }

    private fun submit(type: JobType, taskType: String, payload: Map<String, Any?>, createdBy: UUID?): JobDto {
        val jobId = jobs.create(type, mapper.writeValueAsString(payload), createdBy)

        // Le contrat snake_case est porté par les annotations de WorkerRequest :
        // une seule source de vérité pour la publication et la réception.
        val message = WorkerRequest(
            taskId = jobId.toString(),
            taskType = taskType,
            payload = payload,
        )
        publisher.publishDemand(mapper.writeValueAsString(message))
        jobs.markProcessing(jobId)

        log.info("Job {} soumis ({} → {})", jobId, type.literal, taskType)
        return get(jobId)
    }

    /**
     * Applique la réponse du worker. Idempotent : Pub/Sub garantit au moins une
     * livraison, le même message peut donc arriver deux fois.
     */
    @Transactional
    fun applyWorkerResponse(response: WorkerResponse) {
        val jobId = response.taskId?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            ?: throw ErreurMetier.Invalide("task_id absent ou invalide")

        val job = jobs.findById(jobId)
        if (job == null) {
            // Job inconnu : inutile de faire réessayer Pub/Sub indéfiniment.
            log.warn("Réponse reçue pour un job inconnu ({}), ignorée", jobId)
            return
        }
        if (job.status == JobStatus.done || job.status == JobStatus.failed) {
            log.info("Job {} déjà terminé ({}), réponse ignorée", jobId, job.status.literal)
            return
        }

        when (response.status) {
            "success" -> {
                response.data?.let { jobs.saveResult(jobId, mapper.writeValueAsString(it)) }
                // Un import doit devenir des données, pas seulement un JSON archivé
                // dans le job : c'est ici que naissent les équipes, les joueurs
                // fantômes (spec §6.1.3) et leurs rangs.
                if (job.type == JobType.team_import) {
                    materialiserImport(jobId, response.data)
                }
                jobs.markDone(jobId)
                log.info("Job {} terminé avec succès", jobId)
            }

            "error" -> {
                val message = listOfNotNull(
                    response.error?.code,
                    response.error?.message,
                ).joinToString(" : ").ifBlank { "Erreur inconnue du worker" }
                jobs.markFailed(jobId, message)
                log.warn(
                    "Job {} en échec après {} tentative(s) : {}",
                    jobId,
                    response.error?.attempts ?: 1,
                    message,
                )
            }

            else -> throw ErreurMetier.Invalide(
                "Statut de réponse inattendu : ${response.status}",
            )
        }
    }

    /**
     * Traduit le résultat d'un import en données réelles.
     *
     * Un échec de matérialisation **ne fait pas échouer le job** : le fichier a
     * bien été traité, et faire réessayer Pub/Sub relancerait le parsing sans
     * corriger la cause. L'erreur est journalisée et consignée dans le job, où
     * l'organisateur peut la lire.
     */
    private fun materialiserImport(jobId: UUID, data: Map<String, Any?>?) {
        @Suppress("UNCHECKED_CAST")
        val equipes = data?.get("teams") as? List<Map<String, Any?>>
        if (equipes.isNullOrEmpty()) {
            log.warn("Import {} sans équipe à matérialiser", jobId)
            return
        }
        val tournamentId = tournoiCible(jobId)
        runCatching { imports.materialiser(tournamentId, equipes) }
            .onFailure { e ->
                log.error("Matérialisation de l'import {} en échec", jobId, e)
                jobs.saveResult(
                    jobId,
                    mapper.writeValueAsString(
                        mapOf("materialisation" to mapOf("erreur" to (e.message ?: "échec inconnu"))),
                    ),
                )
            }
    }

    /** Tournoi visé par l'import, déposé dans le payload à la soumission. */
    private fun tournoiCible(jobId: UUID): UUID? {
        val payload = jobs.findById(jobId)?.payload ?: return null

        @Suppress("UNCHECKED_CAST")
        val map = runCatching {
            mapper.readValue(payload, Map::class.java) as Map<String, Any?>
        }.getOrDefault(emptyMap())
        return (map["tournament_id"] as? String)?.let { runCatching { UUID.fromString(it) }.getOrNull() }
    }

    fun get(id: UUID): JobDto {
        val job = jobs.findById(id)
            ?: throw ErreurMetier.Introuvable("Traitement introuvable")

        @Suppress("UNCHECKED_CAST")
        val payload = runCatching {
            mapper.readValue(job.payload, Map::class.java) as Map<String, Any?>
        }.getOrDefault(emptyMap())

        @Suppress("UNCHECKED_CAST")
        return JobDto(
            id = job.id,
            type = job.type.literal,
            status = job.status.literal,
            error = job.error,
            createdAt = job.createdAt,
            finishedAt = job.finishedAt,
            result = payload["result"] as? Map<String, Any?>,
        )
    }
}
