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
import org.example.backend.repository.ParticipantRow
import org.example.backend.repository.RegistrationInfo
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
    private val service = ExportService(tournaments, bracket, jobs)

    private val tournamentId = UUID.randomUUID()
    private val phaseId = UUID.randomUUID()
    private val regA = UUID.randomUUID()
    private val regB = UUID.randomUUID()

    private fun stub(teamSize: Int? = 5, participants: List<ParticipantRow> = deuxEquipes()) {
        every { tournaments.findById(tournamentId) } returns
            TournamentsRecord(id = tournamentId, name = "PA Major", teamSize = teamSize)
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

        service.soumettre(tournamentId, null)

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

        service.soumettre(tournamentId, null)

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

        service.soumettre(tournamentId, null)

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

            service.soumettre(tournamentId, null)

            assertTrue(
                matches.captured.single()["status"] in connus,
                "statut émis inconnu du worker pour $statut",
            )
        }
    }

    @Test
    fun `onze joueurs par equipe donnent le gabarit football`() {
        stub(teamSize = 11)
        val type = slot<String>()
        every {
            jobs.submitTournamentExport(capture(type), any(), any(), any(), any())
        } returns JobDto(id = UUID.randomUUID(), type = "team_export", status = "processing")

        service.soumettre(tournamentId, null)

        assertEquals("football_11v11", type.captured)
    }

    @Test
    fun `toute autre taille donne le gabarit esport`() {
        stub(teamSize = 5)
        val type = slot<String>()
        every {
            jobs.submitTournamentExport(capture(type), any(), any(), any(), any())
        } returns JobDto(id = UUID.randomUUID(), type = "team_export", status = "processing")

        service.soumettre(tournamentId, null)

        assertEquals("esport_5v5", type.captured)
    }

    @Test
    fun `un tournoi sans participant confirme n'est pas exporte`() {
        stub(participants = emptyList())

        val erreur = assertFailsWith<ErreurMetier.Conflit> { service.soumettre(tournamentId, null) }
        assertEquals("Aucun participant confirmé à exporter", erreur.message)
        verify(exactly = 0) { jobs.submitTournamentExport(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `un tournoi introuvable est signale`() {
        every { tournaments.findById(tournamentId) } returns null

        assertFailsWith<ErreurMetier.Introuvable> { service.soumettre(tournamentId, null) }
    }
}
