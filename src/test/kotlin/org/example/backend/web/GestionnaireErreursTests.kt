package org.example.backend.web

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post

/**
 * Frontière entre le domaine et HTTP.
 *
 * Deux garanties à tenir, et une régression à empêcher :
 *
 * 1. une [org.example.backend.error.ErreurMetier] devient le bon statut HTTP ;
 * 2. **son message parvient au client** dans le champ `message` — c'est ce que lit
 *    `src/api/client.ts`. Avec l'ancien `ResponseStatusException`, ce message était
 *    perdu en route : Spring ne l'inclut pas sans `server.error.include-message`,
 *    et le frontend affichait donc des erreurs muettes.
 *
 * Ces tests chargent le contexte complet : ils nécessitent une base joignable,
 * fournie par le service PostgreSQL de la CI.
 */
@SpringBootTest
@AutoConfigureMockMvc
class GestionnaireErreursTests {

    @Autowired
    lateinit var mockMvc: MockMvc

    @Test
    fun `une erreur de validation devient 400 avec son message`() {
        // « Au moins un jeu est requis » est une règle du domaine
        // (TournamentService), levée en ErreurMetier.Invalide.
        mockMvc.post("/api/v1/tournaments") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"name":"Sans jeu","games":[]}"""
            with(jwt())
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.message") { value("Au moins un jeu est requis") }
            jsonPath("$.code") { value("INVALIDE") }
        }
    }

    @Test
    fun `le code fonctionnel est stable et distinct du statut`() {
        // Le client peut s'appuyer sur `code` sans dépendre du statut HTTP.
        mockMvc.post("/api/v1/tournaments") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"name":"Date impossible","games":[{"name":"Valorant"}],"startAt":"pas-une-date"}"""
            with(jwt())
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value("INVALIDE") }
            jsonPath("$.message") { value("Date de début invalide") }
        }
    }

    @Test
    fun `un refus de droits reste un 403 et non un 500`() {
        // Régression : le filet de sécurité attrapait AccessDeniedException et
        // transformait un refus légitime en erreur serveur.
        mockMvc.post("/api/v1/teams/import") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"tournamentType":"esport_5v5","fileBase64":"UEsDBBQ="}"""
            with(jwt()) // authentifié, mais sans le rôle organizer
        }.andExpect {
            status { isForbidden() }
            jsonPath("$.code") { value("NON_AUTORISE") }
        }
    }

    @Test
    fun `un corps JSON illisible reste un 400`() {
        // Exception propre à Spring MVC : elle doit garder son statut, pas finir
        // en 500 dans le filet de sécurité.
        mockMvc.post("/api/v1/tournaments") {
            contentType = MediaType.APPLICATION_JSON
            content = "{ceci n'est pas du json"
            with(jwt())
        }.andExpect { status { isBadRequest() } }
    }

    @Test
    fun `une erreur technique devient 503 sans exposer la cause`() {
        // La messagerie est désactivée dans le contexte de test : soumettre un
        // import déclenche une ErreurTechnique.ServiceIndisponible.
        mockMvc.post("/api/v1/teams/import") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"tournamentType":"esport_5v5","fileBase64":"UEsDBBQ="}"""
            // Le rôle est porté par une autorité : c'est ce que lit @PreAuthorize.
            with(jwt().authorities(SimpleGrantedAuthority("ROLE_organizer")))
        }.andExpect {
            status { isServiceUnavailable() }
            jsonPath("$.code") { value("SERVICE_INDISPONIBLE") }
        }
    }
}
