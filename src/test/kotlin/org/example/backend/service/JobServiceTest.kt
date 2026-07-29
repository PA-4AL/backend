package org.example.backend.service

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.example.backend.database.enums.JobStatus
import org.example.backend.database.enums.JobType
import org.example.backend.error.ErreurMetier
import org.example.backend.model.ImportTeamsRequest
import org.example.backend.model.WorkerError
import org.example.backend.model.WorkerResponse
import org.example.backend.repository.JobRepository
import org.example.backend.repository.JobRow
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Contrat de messages avec le worker Rust et cycle de vie d'un job.
 * Le dépôt et la publication sont mockés : ni base de données ni Pub/Sub.
 */
class JobServiceTest {

    private val jobs = mockk<JobRepository>(relaxed = true)
    private val publisher = mockk<PubSubPublisher>(relaxed = true)
    private val mapper = JsonMapper.builder().build()
    private val imports = mockk<ImportService>(relaxed = true)
    private val service = JobService(jobs, publisher, mapper, imports)

    private val jobId = UUID.randomUUID()

    private fun row(status: JobStatus, payload: String = "{}") = JobRow(
        id = jobId,
        type = JobType.team_import,
        status = status,
        payload = payload,
        error = null,
        fileUrl = null,
        createdAt = null,
        finishedAt = null,
    )

    @Test
    fun `un import publie le message attendu par le worker`() {
        every { jobs.create(any(), any(), any()) } returns jobId
        every { jobs.findById(jobId) } returns row(JobStatus.processing)
        val published = slot<String>()
        every { publisher.publishDemand(capture(published)) } returns "msg-1"

        service.submitTeamImport(
            ImportTeamsRequest(tournamentType = "esport_5v5", fileBase64 = "UEsDBBQ="),
            createdBy = null,
        )

        // Le worker attend task_id / task_type / payload en snake_case
        // (worker/src/models.rs) et le type de tâche import_excel.
        val message = mapper.readValue(published.captured, Map::class.java)
        assertEquals(jobId.toString(), message["task_id"])
        assertEquals("import_excel", message["task_type"])

        @Suppress("UNCHECKED_CAST")
        val payload = message["payload"] as Map<String, Any?>
        assertEquals("esport_5v5", payload["tournament_type"])
        assertEquals("UEsDBBQ=", payload["file_base64"])

        verify { jobs.create(JobType.team_import, any(), null) }
        verify { jobs.markProcessing(jobId) }
    }

    @Test
    fun `un type de tournoi inconnu est refuse avant publication`() {
        assertFailsWith<ErreurMetier.Invalide> {
            service.submitTeamImport(ImportTeamsRequest("quidditch_7v7", "UEsDBBQ="), null)
        }
        verify(exactly = 0) { publisher.publishDemand(any()) }
    }

    @Test
    fun `un fichier vide est refuse`() {
        assertFailsWith<ErreurMetier.Invalide> {
            service.submitTeamImport(ImportTeamsRequest("esport_5v5", "   "), null)
        }
    }

    @Test
    fun `un fichier au dela de la limite Pub-Sub est refuse`() {
        val erreur = assertFailsWith<ErreurMetier.TropVolumineux> {
            service.submitTeamImport(ImportTeamsRequest("esport_5v5", "A".repeat(9_000_001)), null)
        }
        assertTrue(erreur.message!!.contains("10 Mo"))
    }

    @Test
    fun `une reponse en succes enregistre le resultat et termine le job`() {
        every { jobs.findById(jobId) } returns row(JobStatus.processing)

        service.applyWorkerResponse(
            WorkerResponse(
                taskId = jobId.toString(),
                taskType = "import_excel",
                status = "success",
                data = mapOf("team_count" to 2, "player_count" to 10),
            ),
        )

        val result = slot<String>()
        verify { jobs.saveResult(jobId, capture(result)) }
        assertTrue(result.captured.contains("team_count"))
        verify { jobs.markDone(jobId) }
    }

    @Test
    fun `une reponse en erreur consigne le code et le message`() {
        every { jobs.findById(jobId) } returns row(JobStatus.processing)

        service.applyWorkerResponse(
            WorkerResponse(
                taskId = jobId.toString(),
                taskType = "import_excel",
                status = "error",
                error = WorkerError(code = "MISSING_COLUMN", message = "Colonne Équipe absente", attempts = 3),
            ),
        )

        val message = slot<String>()
        verify { jobs.markFailed(jobId, capture(message)) }
        assertTrue(message.captured.contains("MISSING_COLUMN"))
        assertTrue(message.captured.contains("Colonne Équipe absente"))
    }

    @Test
    fun `une reponse en double est ignoree`() {
        // Pub/Sub garantit AU MOINS une livraison : le même message peut arriver
        // deux fois, le traitement doit rester idempotent.
        every { jobs.findById(jobId) } returns row(JobStatus.done)

        service.applyWorkerResponse(
            WorkerResponse(taskId = jobId.toString(), status = "success", data = emptyMap()),
        )

        verify(exactly = 0) { jobs.markDone(any()) }
        verify(exactly = 0) { jobs.markFailed(any(), any()) }
    }

    @Test
    fun `une reponse sans task_id valide est rejetee`() {
        assertFailsWith<ErreurMetier.Invalide> {
            service.applyWorkerResponse(WorkerResponse(taskId = "pas-un-uuid", status = "success"))
        }
    }

    @Test
    fun `une reponse pour un job inconnu est acquittee sans erreur`() {
        // Renvoyer une erreur ferait rejouer le message jusqu'à la file de rebut.
        every { jobs.findById(jobId) } returns null

        service.applyWorkerResponse(
            WorkerResponse(taskId = jobId.toString(), status = "success", data = emptyMap()),
        )

        verify(exactly = 0) { jobs.markDone(any()) }
    }
}
