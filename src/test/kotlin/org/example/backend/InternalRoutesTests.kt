package org.example.backend

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post

/**
 * Routes internes — celles qui ne font PAS partie de l'API versionnée.
 *
 * `WebMvcConfig` préfixe `/api/{version}` à tout le paquet `controller` : le
 * callback Pub/Sub est donc volontairement dans le paquet `internal`. S'il
 * revenait dans `controller`, son chemin deviendrait `/api/{version}/internal/…`
 * et Pub/Sub ne trouverait plus l'endpoint — panne silencieuse de tout
 * l'import/export Excel, côté production uniquement.
 *
 * Comme les tests de versionnement, ceux-ci démarrent le contexte complet et
 * nécessitent une base joignable (fournie par le service PostgreSQL de la CI).
 */
@SpringBootTest
@AutoConfigureMockMvc
class InternalRoutesTests {

    @Autowired
    lateinit var mockMvc: MockMvc

    @Test
    fun `le callback Pub-Sub est servi hors versionnement`() {
        // 401 et non 404 : la route existe et sa chaîne de sécurité dédiée exige un
        // jeton OIDC. Un 404 signifierait que le préfixe /api/{version} s'applique.
        mockMvc.post("/internal/jobs/callback") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"message":{"data":"e30="}}"""
        }.andExpect { status { isUnauthorized() } }
    }

    @Test
    fun `le callback n'est pas exposé sous le préfixe de version`() {
        mockMvc.post("/api/v1/internal/jobs/callback") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"message":{"data":"e30="}}"""
        }.andExpect { status { isUnauthorized() } } // filtré par la sécurité, pas routé
    }

    @Test
    fun `les sondes de sante sont publiques`() {
        // Lues par Cloud Run et par le smoke test de la pipeline de déploiement :
        // elles doivent répondre avant toute authentification.
        mockMvc.get("/actuator/health").andExpect { status { isOk() } }
        mockMvc.get("/actuator/health/readiness").andExpect { status { isOk() } }
    }

    @Test
    fun `les autres endpoints actuator restent fermés`() {
        mockMvc.get("/actuator/env").andExpect { status { isUnauthorized() } }
        mockMvc.get("/actuator/beans").andExpect { status { isUnauthorized() } }
    }
}
