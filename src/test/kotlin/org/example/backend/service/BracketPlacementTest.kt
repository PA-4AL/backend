package org.example.backend.service

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.example.backend.database.enums.MatchStatus
import org.example.backend.database.enums.PhaseType
import org.example.backend.database.enums.TournamentStatus
import org.example.backend.database.tables.records.MatchesRecord
import org.example.backend.database.tables.records.PhasesRecord
import org.example.backend.database.tables.records.TournamentsRecord
import org.example.backend.error.ErreurMetier
import org.example.backend.repository.BracketRepository
import org.example.backend.repository.ParticipantRow
import org.example.backend.repository.RegistrationRepository
import org.example.backend.repository.TournamentRepository
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Deux règles de placement, toutes deux nées d'un usage réel.
 *
 * 1. **Quand peut-on générer l'arbre ?** Le critère était le statut du tournoi,
 *    ce qui créait un cul-de-sac : un tournoi « en cours » dont l'arbre n'avait
 *    jamais été généré ne pouvait plus l'être, donc ne pouvait plus être joué.
 *    Le critère est désormais l'existence de résultats.
 * 2. **Peut-on déplacer les équipes à la main ?** Le seeding automatique ne
 *    convient pas toujours ; l'échange d'emplacements le permet, sans jamais
 *    invalider un match déjà joué.
 */
class BracketPlacementTest {

    private val tournaments = mockk<TournamentRepository>(relaxed = true)
    private val repo = mockk<BracketRepository>(relaxed = true)
    private val inscriptions = mockk<RegistrationRepository>(relaxed = true)

    // Droits mockés en relaxed : ils laissent passer, l'autorisation a ses
    // propres tests (DroitsTest). Ici on teste les règles du bracket.
    private val droits = mockk<Droits>(relaxed = true)
    private val service = BracketService(tournaments, repo, inscriptions, droits)

    private val appelant = UUID.randomUUID()
    private val phaseId = UUID.randomUUID()
    private val tournamentId = UUID.randomUUID()

    private fun match(
        id: UUID = UUID.randomUUID(),
        participant1Id: UUID? = null,
        participant2Id: UUID? = null,
        status: MatchStatus = MatchStatus.pending,
        winnerId: UUID? = null,
        phase: UUID = phaseId,
    ) = MatchesRecord(
        id = id,
        phaseId = phase,
        round = 1,
        position = 1,
        participant1Id = participant1Id,
        participant2Id = participant2Id,
        status = status,
        winnerId = winnerId,
    )

    private fun stubPhase(type: PhaseType = PhaseType.single_elim) {
        every { tournaments.findById(tournamentId) } returns
            TournamentsRecord(id = tournamentId, name = "T", status = TournamentStatus.ongoing)
        every { tournaments.findFirstPhase(tournamentId) } returns
            PhasesRecord(id = phaseId, tournamentId = tournamentId, game = "valorant", position = 1, type = type)
        every { repo.findPhaseTournamentId(phaseId) } returns tournamentId
        // Chaque insertion rend un identifiant distinct, sinon le câblage des
        // matchs entre eux serait indistinguable.
        every { repo.insertMatch(any(), any(), any(), any(), any(), any(), any()) } answers { UUID.randomUUID() }
    }

    private fun participants(n: Int) = (1..n).map {
        ParticipantRow(registrationId = UUID.randomUUID(), displayName = "Équipe $it", seed = it)
    }

    // ------------------------------------------------------------------ //
    // Quand peut-on générer ?
    // ------------------------------------------------------------------ //

    @Test
    fun `un tournoi en cours sans resultat peut encore etre genere`() {
        // Le cas exact rencontré en production : statut « ongoing », aucun match.
        // L'ancienne règle le refusait, rendant le tournoi injouable.
        stubPhase()
        every { repo.findScores(phaseId) } returns emptyMap()
        every { repo.findPhaseMatches(phaseId) } returns emptyList()
        every { tournaments.findActiveParticipants(tournamentId) } returns participants(4)

        service.generate(tournamentId, appelant, true, "single_elim")

        verify { repo.deletePhaseMatches(phaseId) }
    }

    @Test
    fun `un resultat deja saisi interdit la regeneration`() {
        stubPhase()
        val a = UUID.randomUUID()
        val b = UUID.randomUUID()
        every { repo.findScores(phaseId) } returns mapOf(UUID.randomUUID() to (2 to 1))
        every { repo.findPhaseMatches(phaseId) } returns
            listOf(match(participant1Id = a, participant2Id = b, winnerId = a))

        val erreur =
            assertFailsWith<ErreurMetier.Conflit> { service.generate(tournamentId, appelant, true, "single_elim") }
        assertEquals(
            "Des résultats ont déjà été saisis : régénérer l'arbre les effacerait",
            erreur.message,
        )
        verify(exactly = 0) { repo.deletePhaseMatches(any()) }
    }

    @Test
    fun `un bye deja resolu n'interdit pas la regeneration`() {
        // Piège : la génération marque les byes `finished` d'emblée. Un match
        // terminé n'est donc PAS la preuve qu'on a joué — sinon un tableau à
        // nombre impair de participants serait ingénérable une seconde fois.
        stubPhase()
        val seul = UUID.randomUUID()
        every { repo.findScores(phaseId) } returns emptyMap()
        every { repo.findPhaseMatches(phaseId) } returns listOf(
            match(participant1Id = seul, participant2Id = null, status = MatchStatus.finished, winnerId = seul),
        )
        every { tournaments.findActiveParticipants(tournamentId) } returns participants(5)

        service.generate(tournamentId, appelant, true, "single_elim")

        verify { repo.deletePhaseMatches(phaseId) }
    }

    // ------------------------------------------------------------------ //
    // Placement manuel
    // ------------------------------------------------------------------ //

    @Test
    fun `l'echange croise les deux participants`() {
        val a1 = UUID.randomUUID()
        val a2 = UUID.randomUUID()
        val b1 = UUID.randomUUID()
        val b2 = UUID.randomUUID()
        val m1 = match(participant1Id = a1, participant2Id = a2)
        val m2 = match(participant1Id = b1, participant2Id = b2)
        every { repo.findMatch(m1.id!!) } returns m1
        every { repo.findMatch(m2.id!!) } returns m2
        every { repo.findPhaseTournamentId(phaseId) } returns tournamentId
        every { repo.findPhaseMatches(phaseId) } returns emptyList()
        every { tournaments.findFirstPhase(tournamentId) } returns
            PhasesRecord(
                id = phaseId,
                tournamentId = tournamentId,
                game = "valorant",
                position = 1,
                type = PhaseType.single_elim,
            )

        service.echangerEmplacements(m1.id!!, 1, m2.id!!, 2, appelant, true)

        // a1 part en slot 2 du match 2, b2 arrive en slot 1 du match 1 ;
        // les deux autres occupants ne bougent pas.
        verify { repo.setParticipants(m1.id!!, b2, a2) }
        verify { repo.setParticipants(m2.id!!, b1, a1) }
    }

    @Test
    fun `echanger avec un emplacement vide deplace le participant`() {
        val a1 = UUID.randomUUID()
        val m1 = match(participant1Id = a1, participant2Id = null)
        val m2 = match(participant1Id = null, participant2Id = null)
        every { repo.findMatch(m1.id!!) } returns m1
        every { repo.findMatch(m2.id!!) } returns m2
        every { repo.findPhaseMatches(phaseId) } returns emptyList()
        every { tournaments.findFirstPhase(tournamentId) } returns
            PhasesRecord(
                id = phaseId,
                tournamentId = tournamentId,
                game = "valorant",
                position = 1,
                type = PhaseType.single_elim,
            )

        service.echangerEmplacements(m1.id!!, 1, m2.id!!, 1, appelant, true)

        verify { repo.setParticipants(m1.id!!, null, null) }
        verify { repo.setParticipants(m2.id!!, a1, null) }
    }

    @Test
    fun `l'echange dans un seul match n'ecrit qu'une fois`() {
        // Les deux slots vivent sur la même ligne : deux `setParticipants`
        // successifs écraseraient la première écriture.
        val a1 = UUID.randomUUID()
        val a2 = UUID.randomUUID()
        val m = match(participant1Id = a1, participant2Id = a2)
        every { repo.findMatch(m.id!!) } returns m
        every { repo.findPhaseMatches(phaseId) } returns emptyList()
        every { tournaments.findFirstPhase(tournamentId) } returns
            PhasesRecord(
                id = phaseId,
                tournamentId = tournamentId,
                game = "valorant",
                position = 1,
                type = PhaseType.single_elim,
            )

        service.echangerEmplacements(m.id!!, 1, m.id!!, 2, appelant, true)

        verify(exactly = 1) { repo.setParticipants(m.id!!, a2, a1) }
    }

    @Test
    fun `un match deja joue ne peut pas etre reorganise`() {
        val gagnant = UUID.randomUUID()
        val joue =
            match(
                participant1Id = gagnant,
                participant2Id = UUID.randomUUID(),
                status = MatchStatus.finished,
                winnerId = gagnant,
            )
        val libre = match(participant1Id = UUID.randomUUID())
        every { repo.findMatch(joue.id!!) } returns joue
        every { repo.findMatch(libre.id!!) } returns libre

        val erreur = assertFailsWith<ErreurMetier.Conflit> {
            service.echangerEmplacements(joue.id!!, 1, libre.id!!, 1, appelant, true)
        }
        assertEquals("Un match déjà joué ne peut pas être réorganisé", erreur.message)
        verify(exactly = 0) { repo.setParticipants(any(), any(), any()) }
    }

    @Test
    fun `un participant ne peut pas se retrouver deux fois dans le meme match`() {
        val equipe = UUID.randomUUID()
        val m1 = match(participant1Id = equipe, participant2Id = UUID.randomUUID())
        // `equipe` est déjà l'autre occupant du match d'arrivée.
        val m2 = match(participant1Id = UUID.randomUUID(), participant2Id = equipe)
        every { repo.findMatch(m1.id!!) } returns m1
        every { repo.findMatch(m2.id!!) } returns m2

        assertFailsWith<ErreurMetier.Conflit> {
            service.echangerEmplacements(m1.id!!, 1, m2.id!!, 1, appelant, true)
        }
        verify(exactly = 0) { repo.setParticipants(any(), any(), any()) }
    }

    @Test
    fun `deux emplacements vides ne s'echangent pas`() {
        val m1 = match()
        val m2 = match()
        every { repo.findMatch(m1.id!!) } returns m1
        every { repo.findMatch(m2.id!!) } returns m2

        assertFailsWith<ErreurMetier.Invalide> {
            service.echangerEmplacements(m1.id!!, 1, m2.id!!, 2, appelant, true)
        }
    }

    @Test
    fun `un slot hors de 1 ou 2 est refuse`() {
        assertFailsWith<ErreurMetier.Invalide> {
            service.echangerEmplacements(UUID.randomUUID(), 3, UUID.randomUUID(), 1, appelant, true)
        }
    }

    @Test
    fun `un echange entre deux phases differentes est refuse`() {
        val m1 = match(participant1Id = UUID.randomUUID())
        val m2 = match(participant1Id = UUID.randomUUID(), phase = UUID.randomUUID())
        every { repo.findMatch(m1.id!!) } returns m1
        every { repo.findMatch(m2.id!!) } returns m2

        assertFailsWith<ErreurMetier.Invalide> {
            service.echangerEmplacements(m1.id!!, 1, m2.id!!, 1, appelant, true)
        }
    }
}
