package org.example.backend.service

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.example.backend.database.enums.MatchStatus
import org.example.backend.database.enums.PhaseType
import org.example.backend.database.tables.records.MatchesRecord
import org.example.backend.database.tables.records.PhasesRecord
import org.example.backend.database.tables.records.TournamentsRecord
import org.example.backend.error.ErreurMetier
import org.example.backend.model.JobDto
import org.example.backend.repository.BracketRepository
import org.example.backend.repository.JoueurRow
import org.example.backend.repository.ParticipantRow
import org.example.backend.repository.RegistrationInfo
import org.example.backend.repository.RegistrationRepository
import org.example.backend.repository.TournamentRepository
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Contrat du payload d'export, tel que `worker/src/tasks/export_excel.rs` le
 * désérialise : clés en **snake_case**, et trois statuts seulement.
 *
 * Ces tests existent parce que le bouton « Exporter » du frontend n'était relié à
 * rien : le service savait soumettre un export, mais aucune route ne l'exposait.
 * Un contrat inter-service non testé est un contrat qu'on découvre en production
 * — c'est déjà arrivé sur ce projet avec le camelCase du callback Pub/Sub.
 */
class ExportServiceTest {

    private val tournaments = mockk<TournamentRepository>(relaxed = true)
    private val bracket = mockk<BracketRepository>(relaxed = true)
    private val jobs = mockk<JobService>(relaxed = true)
    private val inscriptions = mockk<RegistrationRepository>(relaxed = true)
    private val droits = mockk<Droits>(relaxed = true)
    private val service = ExportService(tournaments, bracket, inscriptions, jobs, droits)

    private val appelant = UUID.randomUUID()
    private val tournamentId = UUID.randomUUID()
    private val phaseId = UUID.randomUUID()
    private val regA = UUID.randomUUID()
    private val regB = UUID.randomUUID()

    private fun stub(teamSize: Int? = 5, gabarit: String? = null, participants: List<ParticipantRow> = deuxEquipes()) {
        every { tournaments.findById(tournamentId) } returns
            TournamentsRecord(id = tournamentId, name = "PA Major", teamSize = teamSize, fileTemplate = gabarit)
        every { tournaments.findFirstPhase(tournamentId) } returns
            PhasesRecord(
                id = phaseId,
                tournamentId = tournamentId,
                game = "cs2",
                position = 1,
                type = PhaseType.single_elim,
            )
        every { tournaments.findActiveParticipants(tournamentId) } returns participants
        every { bracket.findRegistrationInfo(tournamentId) } returns mapOf(
            regA to RegistrationInfo(regA, "Alpha", 1),
            regB to RegistrationInfo(regB, "Bravo", 2),
        )
        every { bracket.findScores(phaseId) } returns emptyMap()
        every { bracket.findPhaseMatches(phaseId) } returns emptyList()
        every { tournaments.findParticipantPlayers(tournamentId) } returns mapOf(
            regA to listOf(JoueurRow("alice", "Diamant"), JoueurRow("bob", null)),
            regB to listOf(JoueurRow("carol", "Platine")),
        )
        every { inscriptions.findFinalRanks(tournamentId) } returns emptyMap()
    }

    private fun deuxEquipes() = listOf(
        ParticipantRow(regA, "Alpha", 1),
        ParticipantRow(regB, "Bravo", 2),
    )

    private fun match(p1: UUID? = regA, p2: UUID? = regB, round: Int = 1, status: MatchStatus = MatchStatus.pending) =
        MatchesRecord(
            id = UUID.randomUUID(),
            phaseId = phaseId,
            round = round,
            position = 1,
            participant1Id = p1,
            participant2Id = p2,
            status = status,
        )

    @Test
    fun `le payload respecte le contrat snake_case du worker`() {
        stub()
        every { bracket.findPhaseMatches(phaseId) } returns listOf(match())
        val matches = slot<List<Map<String, Any?>>>()
        every {
            jobs.submitTournamentExport(any(), any(), any(), capture(matches), any())
        } returns JobDto(id = UUID.randomUUID(), type = "team_export", status = "processing")

        service.soumettre(tournamentId, appelant, true)

        val m = matches.captured.single()
        // Les noms de clés sont le contrat : un camelCase ici et le worker
        // rejette le message (déjà vécu sur le callback Pub/Sub).
        assertEquals(setOf("round", "team_a", "team_b", "score_a", "score_b", "status"), m.keys)
        assertEquals("Alpha", m["team_a"])
        assertEquals("Bravo", m["team_b"])
        assertEquals(1, m["round"])
        assertNull(m["score_a"])
    }

    @Test
    fun `un score enregistre est transmis`() {
        stub()
        val m = match(status = MatchStatus.finished)
        every { bracket.findPhaseMatches(phaseId) } returns listOf(m)
        every { bracket.findScores(phaseId) } returns mapOf(m.id!! to (2 to 1))
        val matches = slot<List<Map<String, Any?>>>()
        every {
            jobs.submitTournamentExport(any(), any(), any(), capture(matches), any())
        } returns JobDto(id = UUID.randomUUID(), type = "team_export", status = "processing")

        service.soumettre(tournamentId, appelant, true)

        val transmis = matches.captured.single()
        assertEquals(2, transmis["score_a"])
        assertEquals(1, transmis["score_b"])
        assertEquals("finished", transmis["status"])
    }

    @Test
    fun `un match a slot vide n'est pas exporte`() {
        // Bye, ou vainqueur encore inconnu : rien à écrire dans un tableur.
        stub()
        every { bracket.findPhaseMatches(phaseId) } returns listOf(match(p2 = null))
        val matches = slot<List<Map<String, Any?>>>()
        every {
            jobs.submitTournamentExport(any(), any(), any(), capture(matches), any())
        } returns JobDto(id = UUID.randomUUID(), type = "team_export", status = "processing")

        service.soumettre(tournamentId, appelant, true)

        assertTrue(matches.captured.isEmpty())
    }

    @Test
    fun `seuls les trois statuts connus du worker sont emis`() {
        // `disputed` et `forfeited` n'ont pas d'équivalent : les rendre
        // « finished » mentirait sur un score absent.
        stub()
        val connus = setOf("pending", "in_progress", "finished")
        MatchStatus.entries.forEach { statut ->
            every { bracket.findPhaseMatches(phaseId) } returns listOf(match(status = statut))
            val matches = slot<List<Map<String, Any?>>>()
            every {
                jobs.submitTournamentExport(any(), any(), any(), capture(matches), any())
            } returns JobDto(id = UUID.randomUUID(), type = "team_export", status = "processing")

            service.soumettre(tournamentId, appelant, true)

            assertTrue(
                matches.captured.single()["status"] in connus,
                "statut émis inconnu du worker pour $statut",
            )
        }
    }

    @Test
    fun `les joueurs de chaque equipe sont exportes`() {
        // Sans eux, le classeur ne contenait qu'une colonne « Équipe » — le
        // premier export réel l'a montré.
        stub()
        val teams = slot<List<Map<String, Any?>>>()
        every {
            jobs.submitTournamentExport(any(), any(), capture(teams), any(), any())
        } returns JobDto(id = UUID.randomUUID(), type = "team_export", status = "processing")

        service.soumettre(tournamentId, appelant, true)

        val alpha = teams.captured.first { it["name"] == "Alpha" }

        @Suppress("UNCHECKED_CAST")
        val joueurs = alpha["players"] as List<Map<String, Any?>>
        assertEquals(listOf("alice", "bob"), joueurs.map { it["username"] })
        // La clé `rank` est attendue par le worker (colonne « Rang ») ; elle reste
        // vide tant que le rang n'est pas persisté — mieux qu'une valeur inventée.
        assertTrue(joueurs.all { it.containsKey("rank") })
        // Le rang en jeu vient du fichier importé, désormais persisté ; vide quand
        // il n'a jamais été renseigné, jamais inventé.
        assertEquals(listOf("Diamant", ""), joueurs.map { it["rank"] })
    }

    @Test
    fun `une equipe sans joueur connu reste exportee`() {
        // Une équipe importée d'un fichier Excel n'est pas encore matérialisée en
        // base : elle n'a aucun membre. L'omettre serait pire que l'exporter nue.
        stub()
        every { tournaments.findParticipantPlayers(tournamentId) } returns emptyMap()
        val teams = slot<List<Map<String, Any?>>>()
        every {
            jobs.submitTournamentExport(any(), any(), capture(teams), any(), any())
        } returns JobDto(id = UUID.randomUUID(), type = "team_export", status = "processing")

        service.soumettre(tournamentId, appelant, true)

        assertEquals(2, teams.captured.size)
        assertEquals(emptyList<Any>(), teams.captured.first()["players"])
    }

    @Test
    fun `le gabarit vient du tournoi, pas de la taille d'equipe`() {
        // Régression : le gabarit était déduit de `teamSize == 11`. Un football à
        // 7 recevait donc les colonnes esport. Il est désormais une donnée du
        // tournoi — ici un tournoi à 7 joueurs déclaré football.
        stub(teamSize = 7, gabarit = "football_11v11")
        val type = slot<String>()
        every {
            jobs.submitTournamentExport(capture(type), any(), any(), any(), any())
        } returns JobDto(id = UUID.randomUUID(), type = "team_export", status = "processing")

        service.soumettre(tournamentId, appelant, true)

        assertEquals("football_11v11", type.captured)
    }

    @Test
    fun `sans gabarit declare, l'esport reste le defaut`() {
        // Les tournois créés avant la colonne n'ont pas de gabarit : ils ne doivent
        // pas devenir inexportables pour autant.
        stub(teamSize = 5, gabarit = null)
        val type = slot<String>()
        every {
            jobs.submitTournamentExport(capture(type), any(), any(), any(), any())
        } returns JobDto(id = UUID.randomUUID(), type = "team_export", status = "processing")

        service.soumettre(tournamentId, appelant, true)

        assertEquals("esport_5v5", type.captured)
    }

    @Test
    fun `un tournoi sans participant confirme n'est pas exporte`() {
        stub(participants = emptyList())

        val erreur = assertFailsWith<ErreurMetier.Conflit> { service.soumettre(tournamentId, appelant, true) }
        assertEquals("Aucun participant confirmé à exporter", erreur.message)
        verify(exactly = 0) { jobs.submitTournamentExport(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `un tournoi introuvable est signale`() {
        every { tournaments.findById(tournamentId) } returns null

        assertFailsWith<ErreurMetier.Introuvable> { service.soumettre(tournamentId, appelant, true) }
    }
}
