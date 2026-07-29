package org.example.backend.service

import org.example.backend.database.enums.MatchStatus
import org.example.backend.database.enums.PhaseType
import org.example.backend.database.enums.TournamentStatus
import org.example.backend.database.tables.records.MatchesRecord
import org.example.backend.error.ErreurMetier
import org.example.backend.model.BracketDto
import org.example.backend.model.BracketMatchDto
import org.example.backend.model.BracketRoundDto
import org.example.backend.model.Display
import org.example.backend.model.MatchRowDto
import org.example.backend.model.SlotDto
import org.example.backend.model.TeamRefDto
import org.example.backend.repository.BracketRepository
import org.example.backend.repository.TournamentRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

@Service
class BracketService(private val tournaments: TournamentRepository, private val repo: BracketRepository) {

    private val zone = ZoneId.of("Europe/Paris")
    private val timeFmt = DateTimeFormatter.ofPattern("HH:mm")

    /* ------------------------------------------------------------
       Génération — élimination simple (spec §4.2)
       ------------------------------------------------------------ */

    /**
     * Ordre des seeds dans les slots du premier round (placement standard :
     * 1 affronte le plus bas, le 2 est à l'opposé du tableau…).
     * Ex. pour 8 : [1, 8, 4, 5, 2, 7, 3, 6].
     */
    private fun seedSlots(size: Int): List<Int> {
        var slots = listOf(1)
        while (slots.size < size) {
            val n = slots.size * 2
            slots = slots.flatMap { listOf(it, n + 1 - it) }
        }
        return slots
    }

    @Transactional
    fun generate(tournamentId: UUID, format: String? = null): BracketDto {
        val tournament = tournaments.findById(tournamentId)
            ?: throw ErreurMetier.Introuvable("Tournoi introuvable")

        // Re-génération possible tant que le tournoi n'a pas démarré (spec §4.2)
        if (tournament.status !in listOf(
                TournamentStatus.draft,
                TournamentStatus.registration,
                TournamentStatus.check_in,
            )
        ) {
            throw ErreurMetier.Conflit("Le tournoi a déjà démarré")
        }

        val phase = tournaments.findFirstPhase(tournamentId)
            ?: throw ErreurMetier.Conflit("Le tournoi n'a aucune phase")

        // Format choisi au moment de la génération (V1 : élimination simple)
        if (format != null) {
            val type = PhaseType.entries.firstOrNull { it.literal == format }
                ?: throw ErreurMetier.Invalide("Format inconnu : $format")
            if (type != PhaseType.single_elim) {
                throw ErreurMetier.Invalide(
                    "Format « $format » pas encore supporté — élimination simple uniquement en V1",
                )
            }
            tournaments.updatePhaseType(phase.id!!, type)
        }

        // Seeding : les seeds manuels d'abord, puis les non-seedés mélangés (spec : aléatoire ou manuel)
        val participants = tournaments.findActiveParticipants(tournamentId)
        if (participants.size < 2) {
            throw ErreurMetier.Conflit("Au moins 2 participants confirmés sont requis")
        }
        val ordered = participants.filter { it.seed != null }.sortedBy { it.seed } +
            participants.filter { it.seed == null }.shuffled()
        ordered.forEachIndexed { i, p -> repo.updateSeed(p.registrationId, i + 1) }

        val n = ordered.size
        var size = 1
        while (size < n) size *= 2
        val totalRounds = Integer.numberOfTrailingZeros(size)

        repo.deletePhaseMatches(phase.id!!)

        // Création de la finale vers le premier round pour chaîner next_match_id
        var nextRoundIds: List<UUID> = emptyList()
        var firstRoundIds: List<UUID> = emptyList()
        for (round in totalRounds downTo 1) {
            val count = size shr round
            val ids = (1..count).map { position ->
                repo.insertMatch(
                    phaseId = phase.id!!,
                    round = round,
                    position = position,
                    bestOf = phase.defaultBo ?: 1,
                    nextMatchId = nextRoundIds.getOrNull((position - 1) / 2),
                )
            }
            if (round == 1) firstRoundIds = ids
            nextRoundIds = ids
        }

        // Placement des participants au premier round + gestion des byes
        val slots = seedSlots(size)
        val firstRound = repo.findPhaseMatches(phase.id!!).filter { it.round == 1 }
        firstRoundIds.forEachIndexed { i, matchId ->
            val seedA = slots[2 * i]
            val seedB = slots[2 * i + 1]
            val regA = ordered.getOrNull(seedA - 1)?.registrationId
            val regB = ordered.getOrNull(seedB - 1)?.registrationId
            repo.setParticipants(matchId, regA, regB)

            // Bye : un seul participant → qualification automatique (spec §4.2)
            val winner = if (regA != null && regB == null) {
                regA
            } else if (regA == null && regB != null) {
                regB
            } else {
                null
            }
            if (winner != null) {
                repo.setResult(matchId, winner, MatchStatus.finished)
                val match = firstRound.first { it.id == matchId }
                propagateWinner(match.position!!, match.nextMatchId, winner)
            }
        }

        return getBracket(tournamentId)
    }

    /* ------------------------------------------------------------
       Saisie de score + propagation
       ------------------------------------------------------------ */

    @Transactional
    fun reportScore(matchId: UUID, scoreA: Int, scoreB: Int): BracketDto {
        if (scoreA == scoreB) {
            throw ErreurMetier.Invalide("Pas de match nul en élimination directe")
        }
        val match = repo.findMatch(matchId)
            ?: throw ErreurMetier.Introuvable("Match introuvable")
        if (match.status == MatchStatus.finished) {
            throw ErreurMetier.Conflit("Match déjà terminé")
        }
        val p1 = match.participant1Id
        val p2 = match.participant2Id
        if (p1 == null || p2 == null) {
            throw ErreurMetier.Conflit("Les deux participants ne sont pas encore connus")
        }

        repo.replaceScore(matchId, scoreA, scoreB)
        val winner = if (scoreA > scoreB) p1 else p2
        repo.setResult(matchId, winner, MatchStatus.finished)
        propagateWinner(match.position!!, match.nextMatchId, winner)

        val tournamentId = repo.findPhaseTournamentId(match.phaseId!!)
            ?: throw ErreurMetier.Conflit("Phase orpheline")

        // Finale jouée → le tournoi est terminé (cycle de vie spec §4.1)
        if (match.nextMatchId == null) {
            repo.setTournamentStatus(tournamentId, TournamentStatus.finished)
        } else {
            repo.setTournamentStatus(tournamentId, TournamentStatus.ongoing)
        }

        return getBracket(tournamentId)
    }

    /** Le vainqueur du match en position p va dans le slot 1 (p impair) ou 2 du match suivant. */
    private fun propagateWinner(position: Int, nextMatchId: UUID?, winnerId: UUID) {
        if (nextMatchId == null) return
        repo.fillSlot(nextMatchId, slot1 = position % 2 == 1, registrationId = winnerId)
    }

    /* ------------------------------------------------------------
       Lecture — format du frontend (BracketData)
       ------------------------------------------------------------ */

    fun getBracket(tournamentId: UUID): BracketDto {
        val phase = tournaments.findFirstPhase(tournamentId)
            ?: throw ErreurMetier.Introuvable("Tournoi sans phase")
        val matches = repo.findPhaseMatches(phase.id!!)
        if (matches.isEmpty()) return BracketDto(rounds = emptyList(), champion = null)

        val scores = repo.findScores(phase.id!!)
        val infos = repo.findRegistrationInfo(tournamentId)
        val colorBySeed = infos.values.sortedBy { it.seed ?: Int.MAX_VALUE }
            .mapIndexed { i, info -> info.registrationId to Display.colorFor(i) }
            .toMap()
        val totalRounds = matches.maxOf { it.round!! }

        val rounds = matches.groupBy { it.round!! }.toSortedMap().map { (round, roundMatches) ->
            val (label, codePrefix) = Display.roundLabel(round, totalRounds)
            BracketRoundDto(
                label = label,
                matches = roundMatches.map { m ->
                    toMatchDto(m, codePrefix, scores[m.id], infos, colorBySeed)
                },
            )
        }

        val final = matches.first { it.round == totalRounds }
        val champion = final.winnerId?.let { infos[it]?.displayName }

        return BracketDto(rounds = rounds, champion = champion)
    }

    private fun toMatchDto(
        m: MatchesRecord,
        codePrefix: String,
        score: Pair<Int, Int>?,
        infos: Map<UUID, org.example.backend.repository.RegistrationInfo>,
        colors: Map<UUID, String>,
    ): BracketMatchDto {
        val status = when {
            m.status == MatchStatus.finished || m.status == MatchStatus.forfeited -> "done"
            m.status == MatchStatus.ongoing || m.status == MatchStatus.disputed -> "live"
            m.participant1Id != null && m.participant2Id != null -> "scheduled"
            else -> "pending"
        }

        fun slot(regId: UUID?, slotScore: Int?, opponentWaitLabel: String): SlotDto {
            if (regId == null) {
                // Slot vide : bye (round 1) ou en attente du vainqueur précédent
                return SlotDto(tbd = true, name = opponentWaitLabel)
            }
            val info = infos[regId]
            return SlotDto(
                name = info?.displayName ?: "?",
                seed = info?.seed,
                code = Display.initials(info?.displayName ?: "?"),
                color = colors[regId],
                score = slotScore,
                win = m.winnerId == regId,
            )
        }

        val waitLabel = if (m.round == 1) "Bye" else "À déterminer"
        return BracketMatchDto(
            id = "$codePrefix${m.position}",
            matchId = m.id.toString(),
            status = status,
            time = m.scheduledAt?.let { timeFmt.format(it.atZoneSameInstant(zone)) },
            a = slot(m.participant1Id, score?.first, waitLabel),
            b = slot(m.participant2Id, score?.second, waitLabel),
        )
    }

    /* ------------------------------------------------------------
       Matchs du round courant — pour la page Détail tournoi
       ------------------------------------------------------------ */

    fun currentRoundMatches(tournamentId: UUID): Pair<String?, List<MatchRowDto>> {
        val phase = tournaments.findFirstPhase(tournamentId) ?: return null to emptyList()
        val matches = repo.findPhaseMatches(phase.id!!)
        if (matches.isEmpty()) return null to emptyList()

        val totalRounds = matches.maxOf { it.round!! }
        val currentRound = matches
            .filter { it.status != MatchStatus.finished && it.status != MatchStatus.forfeited }
            .minOfOrNull { it.round!! } ?: totalRounds
        val (label, _) = Display.roundLabel(currentRound, totalRounds)

        val scores = repo.findScores(phase.id!!)
        val infos = repo.findRegistrationInfo(tournamentId)
        val colorBySeed = infos.values.sortedBy { it.seed ?: Int.MAX_VALUE }
            .mapIndexed { i, info -> info.registrationId to Display.colorFor(i) }
            .toMap()

        fun ref(regId: UUID?): TeamRefDto {
            val info = regId?.let { infos[it] }
            return TeamRefDto(
                code = Display.initials(info?.displayName ?: "?"),
                name = info?.displayName ?: "À déterminer",
                color = (regId?.let { colorBySeed[it] }) ?: "#6B7AA0",
            )
        }

        val rows = matches.filter { it.round == currentRound }.map { m ->
            val score = scores[m.id]
            MatchRowDto(
                id = m.id.toString(),
                teamA = ref(m.participant1Id),
                teamB = ref(m.participant2Id),
                scoreA = score?.first,
                scoreB = score?.second,
                status = when {
                    m.status == MatchStatus.finished || m.status == MatchStatus.forfeited -> "done"
                    m.status == MatchStatus.ongoing || m.status == MatchStatus.disputed -> "live"
                    else -> "scheduled"
                },
                time = m.scheduledAt?.let { timeFmt.format(it.atZoneSameInstant(zone)) },
            )
        }
        return label to rows
    }
}
