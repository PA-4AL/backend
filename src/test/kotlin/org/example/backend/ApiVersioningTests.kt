package org.example.backend

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get

/**
 * Contrat de versionnement de l'API — procédure dans `docs/API-VERSIONING.md`.
 *
 * Comme le smoke test, ces tests démarrent le contexte complet et nécessitent donc
 * une base joignable.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ApiVersioningTests {

    @Autowired
    lateinit var mockMvc: MockMvc

    @Test
    fun `la v1 sert les routes publiques`() {
        mockMvc.get("/api/v1/tournaments").andExpect { status { isOk() } }
    }

    @Test
    fun `les routes non versionnees ne sont plus servies`() {
        // 401 et non 404 : la chaîne de filtres Spring Security s'applique avant le
        // routage, et aucun matcher ne rend ce chemin public.
        mockMvc.get("/api/tournaments").andExpect { status { isUnauthorized() } }
    }

    @Test
    fun `le versionnement ne contourne pas la securite`() {
        mockMvc.get("/api/v1/teams/mine").andExpect { status { isUnauthorized() } }
        mockMvc.get("/api/v1/dashboard/kpis").andExpect { status { isUnauthorized() } }
    }

    @Test
    fun `une version non declaree n est pas exposee`() {
        // Aucun controller ni matcher pour la v2 aujourd'hui : ajouter une version
        // est un acte explicite (nouveau paquet + ligne de sécurité).
        mockMvc.get("/api/v2/tournaments").andExpect { status { isUnauthorized() } }
    }
}
