package org.example.backend.service

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.example.backend.database.enums.MatchStatus
import org.example.backend.database.enums.PhaseType
import org.example.backend.database.enums.TournamentStatus
import org.example.backend.database.tables.records.MatchesRecord
import org.example.backend.database.tables.records.PhasesRecord
import org.example.backend.repository.BracketRepository
import org.example.backend.repository.TournamentRepository
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Règles d'élimination directe (spec §4.2) : refus du match nul, non-rejouabilité
 * d'un match terminé, et propagation du vainqueur dans le bon slot du tour suivant.
 * Les dépôts sont mockés : aucune base de données n'est nécessaire.
 */
class BracketServiceTest {

    private val tournaments = mockk<TournamentRepository>(relaxed = true)
    private val repo = mockk<BracketRepository>(relaxed = true)
    private val service = BracketService(tournaments, repo)

    private val phaseId = UUID.randomUUID()
    private val tournamentId = UUID.randomUUID()

    private fun match(
        id: UUID = UUID.randomUUID(),
        position: Int = 1,
        nextMatchId: UUID? = null,
        participant1Id: UUID? = UUID.randomUUID(),
        participant2Id: UUID? = UUID.randomUUID(),
        status: MatchStatus = MatchStatus.pending,
    ) = MatchesRecord(
        id = id,
        phaseId = phaseId,
        round = 1,
        position = position,
        participant1Id = participant1Id,
        participant2Id = participant2Id,
        status = status,
        nextMatchId = nextMatchId,
    )

    private fun stubBracketRead() {
        every { tournaments.findFirstPhase(tournamentId) } returns
            PhasesRecord(
                id = phaseId,
                tournamentId = tournamentId,
                game = "valorant",
                position = 1,
                type = PhaseType.single_elim,
            )
        // Bracket vide en relecture : on ne teste ici que les effets d'écriture.
        every { repo.findPhaseMatches(phaseId) } returns emptyList()
        every { repo.findPhaseTournamentId(phaseId) } returns tournamentId
    }

    @Test
    fun `un score egal est refuse`() {
        val error = assertFailsWith<ResponseStatusException> {
            service.reportScore(UUID.randomUUID(), scoreA = 1, scoreB = 1)
        }
        assertEquals(HttpStatus.BAD_REQUEST, error.statusCode)
    }

    @Test
    fun `un match deja termine ne peut pas etre rejoue`() {
        val existing = match(status = MatchStatus.finished)
        every { repo.findMatch(existing.id!!) } returns existing

        val error = assertFailsWith<ResponseStatusException> {
            service.reportScore(existing.id!!, scoreA = 2, scoreB = 0)
        }
        assertEquals(HttpStatus.CONFLICT, error.statusCode)
    }

    @Test
    fun `un match sans les deux participants ne peut pas etre score`() {
        val existing = match(participant2Id = null)
        every { repo.findMatch(existing.id!!) } returns existing

        val error = assertFailsWith<ResponseStatusException> {
            service.reportScore(existing.id!!, scoreA = 2, scoreB = 0)
        }
        assertEquals(HttpStatus.CONFLICT, error.statusCode)
    }

    @Test
    fun `le vainqueur d'un match en position impaire remplit le slot 1 du tour suivant`() {
        val nextMatchId = UUID.randomUUID()
        val existing = match(position = 3, nextMatchId = nextMatchId)
        every { repo.findMatch(existing.id!!) } returns existing
        stubBracketRead()

        service.reportScore(existing.id!!, scoreA = 2, scoreB = 1)

        verify { repo.replaceScore(existing.id!!, 2, 1) }
        verify { repo.setResult(existing.id!!, existing.participant1Id!!, MatchStatus.finished) }
        verify { repo.fillSlot(nextMatchId, slot1 = true, registrationId = existing.participant1Id!!) }
        // Un match suivant existe → le tournoi est en cours, pas terminé.
        verify { repo.setTournamentStatus(tournamentId, TournamentStatus.ongoing) }
    }

    @Test
    fun `le vainqueur d'un match en position paire remplit le slot 2 du tour suivant`() {
        val nextMatchId = UUID.randomUUID()
        val existing = match(position = 4, nextMatchId = nextMatchId)
        every { repo.findMatch(existing.id!!) } returns existing
        stubBracketRead()

        service.reportScore(existing.id!!, scoreA = 0, scoreB = 3)

        verify { repo.fillSlot(nextMatchId, slot1 = false, registrationId = existing.participant2Id!!) }
    }

    @Test
    fun `la finale gagnee termine le tournoi`() {
        // Pas de next_match_id : c'est la finale (cycle de vie spec §4.1).
        val existing = match(nextMatchId = null)
        every { repo.findMatch(existing.id!!) } returns existing
        stubBracketRead()

        service.reportScore(existing.id!!, scoreA = 3, scoreB = 2)

        verify { repo.setTournamentStatus(tournamentId, TournamentStatus.finished) }
    }
}
