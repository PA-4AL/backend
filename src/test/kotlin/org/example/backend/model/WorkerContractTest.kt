package org.example.backend.model

import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Contrat de sérialisation avec le worker Rust (`worker/src/models.rs`), qui
 * parle **snake_case**.
 *
 * Ce test existe parce que l'oubli côté réception a coûté un aller-retour en
 * production : le callback recevait bien les messages mais répondait 400
 * (`task_id` désérialisé à null), et Pub/Sub les rejouait jusqu'à la file de
 * rebut.
 */
class WorkerContractTest {

    private val mapper = JsonMapper.builder().build()

    @Test
    fun `une demande est publiee en snake_case`() {
        val json = mapper.writeValueAsString(
            WorkerRequest(
                taskId = "11111111-2222-3333-4444-555555555555",
                taskType = "import_excel",
                payload = mapOf("tournament_type" to "esport_5v5", "file_base64" to "UEsDBBQ="),
            ),
        )

        assertTrue(json.contains("\"task_id\""), "clé task_id absente : $json")
        assertTrue(json.contains("\"task_type\""), "clé task_type absente : $json")
        assertTrue(!json.contains("\"taskId\""), "camelCase publié : $json")
    }

    @Test
    fun `une reponse en snake_case est correctement relue`() {
        // Message tel que le worker le publie réellement.
        val json = """
            {
              "task_id": "11111111-2222-3333-4444-555555555555",
              "task_type": "export_excel",
              "status": "success",
              "data": { "file_name": "export.xlsx", "file_base64": "UEsDBBQ=" }
            }
        """.trimIndent()

        val response = mapper.readValue(json, WorkerResponse::class.java)

        assertEquals("11111111-2222-3333-4444-555555555555", response.taskId)
        assertEquals("export_excel", response.taskType)
        assertEquals("success", response.status)
        assertEquals("export.xlsx", response.data?.get("file_name"))
    }

    @Test
    fun `une reponse en erreur conserve le detail`() {
        val json = """
            {
              "task_id": "11111111-2222-3333-4444-555555555555",
              "task_type": "import_excel",
              "status": "error",
              "error": { "code": "MISSING_COLUMN", "message": "Colonne Équipe absente", "attempts": 3 }
            }
        """.trimIndent()

        val response = mapper.readValue(json, WorkerResponse::class.java)

        assertNotNull(response.error)
        assertEquals("MISSING_COLUMN", response.error?.code)
        assertEquals(3, response.error?.attempts)
    }

    @Test
    fun `l'enveloppe push de Pub-Sub est relue`() {
        val json = """
            {
              "message": {
                "data": "eyJ0YXNrX2lkIjoiYWJjIn0=",
                "messageId": "20779353353602978",
                "publishTime": "2026-07-28T18:15:03Z"
              },
              "subscription": "projects/p/subscriptions/s"
            }
        """.trimIndent()

        val envelope = mapper.readValue(json, PubSubPushEnvelope::class.java)

        assertEquals("20779353353602978", envelope.message?.messageId)
        assertNotNull(envelope.message?.data)
    }
}
