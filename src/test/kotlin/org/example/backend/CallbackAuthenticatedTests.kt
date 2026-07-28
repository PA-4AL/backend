package org.example.backend

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post
import java.util.Base64

/**
 * Le callback Pub/Sub vu depuis un appelant AUTHENTIFIÉ.
 *
 * Distinction essentielle avec InternalRoutesTests : sans jeton, Spring Security
 * répond 401 avant même que le DispatcherServlet ne résolve la version d'API. Le
 * chemin réellement emprunté par Pub/Sub n'est donc testé que si l'on présente un
 * jeton — ce que fait ce test.
 */
@SpringBootTest(
    properties = ["app.pubsub.push-service-account=pa-push@exemple.iam.gserviceaccount.com"],
)
@AutoConfigureMockMvc
class CallbackAuthenticatedTests {

    @Autowired
    lateinit var mockMvc: MockMvc

    private fun envelope(taskId: String): String {
        val payload = """{"task_id":"$taskId","task_type":"export_excel","status":"success","data":{}}"""
        val data = Base64.getEncoder().encodeToString(payload.toByteArray())
        return """{"message":{"data":"$data","messageId":"1"},"subscription":"projects/p/subscriptions/s"}"""
    }

    @Test
    fun `un appelant authentifie atteint bien le controller`() {
        // Job inconnu → 204 (idempotence). Un 400 signifierait que la requête n'a
        // jamais atteint le controller : c'est la signature d'un échec de résolution
        // de version d'API sur le 2e segment du chemin ("jobs").
        mockMvc.post("/internal/v1/jobs/callback") {
            contentType = MediaType.APPLICATION_JSON
            content = envelope("11111111-2222-3333-4444-555555555555")
            with(jwt().jwt { it.claim("email", "pa-push@exemple.iam.gserviceaccount.com") })
        }.andExpect { status { isNoContent() } }
    }

    @Test
    fun `un autre compte de service est refuse`() {
        mockMvc.post("/internal/v1/jobs/callback") {
            contentType = MediaType.APPLICATION_JSON
            content = envelope("11111111-2222-3333-4444-555555555555")
            with(jwt().jwt { it.claim("email", "intrus@exemple.iam.gserviceaccount.com") })
        }.andExpect { status { isForbidden() } }
    }
}
