package org.example.backend.service

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.example.backend.database.enums.RegistrationStatus
import org.example.backend.database.enums.TeamMemberRole
import org.example.backend.repository.RegistrationRepository
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals

/**
 * Matérialisation d'un import (spec §6.1.3 — joueurs fantômes).
 *
 * Avant, un import « réussi » n'écrivait que du JSON dans le job : aucune équipe,
 * aucun joueur, et le rang lu dans le fichier était perdu — d'où la colonne
 * « Rang » vide à l'export. Ces tests fixent le comportement attendu, et surtout
 * l'**idempotence** : Pub/Sub garantit au moins une livraison, un même résultat
 * peut donc être matérialisé deux fois.
 */
class ImportServiceTest {

    private val repo = mockk<RegistrationRepository>(relaxed = true)
    private val service = ImportService(repo)

    private val tournamentId = UUID.randomUUID()

    private fun equipe(nom: String, vararg joueurs: Pair<String, String?>) = mapOf(
        "name" to nom,
        "players" to joueurs.map { (pseudo, rang) -> mapOf("username" to pseudo, "rank" to rang) },
    )

    @Test
    fun `cree l'equipe, les joueurs fantomes et leurs rangs`() {
        val teamId = UUID.randomUUID()
        val alice = UUID.randomUUID()
        val bob = UUID.randomUUID()
        every { repo.findTeamByName("Les Renards") } returns null
        every { repo.insertGhostTeam("Les Renards") } returns teamId
        every { repo.findGhostUserByPseudo(any()) } returns null
        every { repo.insertGhostUser("alice") } returns alice
        every { repo.insertGhostUser("bob") } returns bob
        every { repo.existsForTeam(tournamentId, teamId) } returns false

        val bilan = service.materialiser(
            tournamentId,
            listOf(equipe("Les Renards", "alice" to "Diamant", "bob" to "Or")),
        )

        // Le rang du fichier atterrit enfin en base : c'est tout l'objet du correctif.
        verify { repo.attacherMembre(teamId, alice, TeamMemberRole.captain, "Diamant") }
        verify { repo.attacherMembre(teamId, bob, TeamMemberRole.member, "Or") }
        verify { repo.insertTeam(tournamentId, teamId, RegistrationStatus.confirmed) }
        assertEquals(1, bilan.equipesCreees)
        assertEquals(2, bilan.joueursCrees)
        assertEquals(1, bilan.inscriptions)
    }

    @Test
    fun `le premier joueur devient capitaine`() {
        // Le fichier ne désigne pas de capitaine, et une équipe sans capitaine ne
        // pourrait plus être administrée ensuite.
        val teamId = UUID.randomUUID()
        val premier = UUID.randomUUID()
        every { repo.findTeamByName(any()) } returns null
        every { repo.insertGhostTeam(any()) } returns teamId
        every { repo.findGhostUserByPseudo(any()) } returns null
        every { repo.insertGhostUser("carol") } returns premier
        every { repo.insertGhostUser("dave") } returns UUID.randomUUID()

        service.materialiser(null, listOf(equipe("Nova", "carol" to null, "dave" to null)))

        val role = slot<TeamMemberRole>()
        verify { repo.attacherMembre(teamId, premier, capture(role), null) }
        assertEquals(TeamMemberRole.captain, role.captured)
    }

    @Test
    fun `une seconde livraison ne duplique rien`() {
        // Cas réel : Pub/Sub redélivre le message. Sans recherche préalable par nom
        // et par pseudo, chaque redélivrance créerait des doublons.
        val teamId = UUID.randomUUID()
        val alice = UUID.randomUUID()
        every { repo.findTeamByName("Les Renards") } returns teamId
        every { repo.findGhostUserByPseudo("alice") } returns alice
        every { repo.existsForTeam(tournamentId, teamId) } returns true

        val bilan = service.materialiser(tournamentId, listOf(equipe("Les Renards", "alice" to "Diamant")))

        verify(exactly = 0) { repo.insertGhostTeam(any()) }
        verify(exactly = 0) { repo.insertGhostUser(any()) }
        verify(exactly = 0) { repo.insertTeam(any(), any(), any()) }
        // Le rattachement est rejoué : il met le rang à jour (upsert), sans échouer.
        verify { repo.attacherMembre(teamId, alice, TeamMemberRole.captain, "Diamant") }
        assertEquals(1, bilan.equipesExistantes)
        assertEquals(0, bilan.joueursCrees)
        assertEquals(0, bilan.inscriptions)
    }

    @Test
    fun `un import ne s'approprie pas le compte d'un joueur inscrit`() {
        // Régression : la recherche portait sur TOUS les utilisateurs. Importer un
        // fichier contenant le pseudo d'un joueur réellement inscrit rattachait son
        // compte à l'équipe importée, sans son consentement. Seuls les joueurs
        // fantômes — ceux que l'import a lui-même créés — sont réutilisables.
        val teamId = UUID.randomUUID()
        val fantome = UUID.randomUUID()
        every { repo.findTeamByName(any()) } returns null
        every { repo.insertGhostTeam(any()) } returns teamId
        // Le dépôt ne rend rien : un compte Keycloak portant ce pseudo existe,
        // mais il est invisible pour l'import.
        every { repo.findGhostUserByPseudo("alexandre") } returns null
        every { repo.insertGhostUser("alexandre") } returns fantome

        service.materialiser(null, listOf(equipe("Nova", "alexandre" to "Or")))

        // Un homonyme fantôme est créé, le compte réel n'est pas touché.
        verify { repo.insertGhostUser("alexandre") }
        verify { repo.attacherMembre(teamId, fantome, TeamMemberRole.captain, "Or") }
    }

    @Test
    fun `sans tournoi cible, les equipes sont creees mais pas inscrites`() {
        every { repo.findTeamByName(any()) } returns null
        every { repo.insertGhostTeam(any()) } returns UUID.randomUUID()
        every { repo.findGhostUserByPseudo(any()) } returns null
        every { repo.insertGhostUser(any()) } returns UUID.randomUUID()

        val bilan = service.materialiser(null, listOf(equipe("Nova", "carol" to "Platine")))

        verify(exactly = 0) { repo.insertTeam(any(), any(), any()) }
        assertEquals(1, bilan.equipesCreees)
        assertEquals(0, bilan.inscriptions)
    }

    @Test
    fun `un rang vide est stocke comme absent`() {
        // Une cellule vide du tableur ne doit pas devenir une chaîne vide en base :
        // « pas de rang » et « rang égal à rien » ne se distingueraient plus.
        val teamId = UUID.randomUUID()
        val userId = UUID.randomUUID()
        every { repo.findTeamByName(any()) } returns null
        every { repo.insertGhostTeam(any()) } returns teamId
        every { repo.findGhostUserByPseudo(any()) } returns null
        every { repo.insertGhostUser(any()) } returns userId

        service.materialiser(null, listOf(equipe("Nova", "carol" to "   ")))

        verify { repo.attacherMembre(teamId, userId, TeamMemberRole.captain, null) }
    }

    @Test
    fun `une equipe sans nom est ignoree sans faire echouer l'import`() {
        val bilan = service.materialiser(null, listOf(mapOf("name" to "  ", "players" to emptyList<Any>())))

        verify(exactly = 0) { repo.insertGhostTeam(any()) }
        assertEquals(0, bilan.equipesCreees)
    }

    @Test
    fun `un joueur sans pseudo est ignore mais l'equipe est creee`() {
        val teamId = UUID.randomUUID()
        every { repo.findTeamByName(any()) } returns null
        every { repo.insertGhostTeam(any()) } returns teamId

        val bilan = service.materialiser(null, listOf(equipe("Nova", "" to "Or")))

        verify(exactly = 0) { repo.attacherMembre(any(), any(), any(), any()) }
        assertEquals(1, bilan.equipesCreees)
        assertEquals(0, bilan.joueursRattaches)
    }
}
