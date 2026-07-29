package org.example.backend.service

import org.example.backend.database.enums.BracketType
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
import org.example.backend.service.bracket.GenerateurBracket
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

@Service
class BracketService(private val tournaments: TournamentRepository, private val repo: BracketRepository) {

    private val log = LoggerFactory.getLogger(javaClass)
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

        // Format demandé à la génération, ou celui déjà porté par la phase.
        val type = if (format != null) {
            val demande = PhaseType.entries.firstOrNull { it.literal == format }
                ?: throw ErreurMetier.Invalide("Format inconnu : $format")
            if (demande == PhaseType.swiss) {
                // Le système suisse apparie selon le classement après chaque tour :
                // il n'existe pas d'arbre à pré-générer (docs/adr/0008).
                throw ErreurMetier.Invalide(
                    "Le format suisse se génère tour par tour et n'est pas encore disponible",
                )
            }
            tournaments.updatePhaseType(phase.id!!, demande)
            demande
        } else {
            phase.type
        }

        // Seeding : les seeds manuels d'abord, puis les non-seedés mélangés (spec : aléatoire ou manuel)
        val participants = tournaments.findActiveParticipants(tournamentId)
        if (participants.size < 2) {
            throw ErreurMetier.Conflit("Au moins 2 participants confirmés sont requis")
        }
        val ordered = participants.filter { it.seed != null }.sortedBy { it.seed } +
            participants.filter { it.seed == null }.shuffled()
        ordered.forEachIndexed { i, p -> repo.updateSeed(p.registrationId, i + 1) }

        // La structure est calculée par un générateur pur (aucune base), puis
        // persistée : les clés locales des matchs sont traduites en identifiants.
        val planifies = when (type) {
            PhaseType.single_elim -> GenerateurBracket.eliminationSimple(ordered.size)
            PhaseType.double_elim -> {
                if (ordered.size < 4) {
                    throw ErreurMetier.Conflit("L'élimination double exige au moins 4 participants")
                }
                GenerateurBracket.eliminationDouble(ordered.size)
            }
            PhaseType.round_robin -> GenerateurBracket.roundRobin(ordered.size)
            PhaseType.swiss -> throw ErreurMetier.Invalide(
                "Le format suisse se génère tour par tour et n'est pas encore disponible",
            )
        }

        repo.deletePhaseMatches(phase.id!!)
        val bo = phase.defaultBo ?: 1

        // Insertion sans les liens (les cibles n'existent pas encore), puis
        // câblage : indispensable en élimination double, où un match du tableau
        // des vainqueurs pointe vers un match du tableau des perdants créé après.
        val idParCle = planifies.associate { plan ->
            plan.cle to repo.insertMatch(
                phaseId = phase.id!!,
                round = plan.round,
                position = plan.position,
                bestOf = bo,
                nextMatchId = null,
                bracket = plan.bracket,
            )
        }
        planifies.forEach { plan ->
            if (plan.vainqueurVers != null || plan.perdantVers != null) {
                repo.updateLiens(
                    matchId = idParCle.getValue(plan.cle),
                    nextMatchId = plan.vainqueurVers?.let { idParCle[it] },
                    nextMatchLoserId = plan.perdantVers?.let { idParCle[it] },
                )
            }
        }

        // Placement des participants : le générateur indique les seeds, on les
        // traduit en inscriptions. Un seed au-delà du nombre réel est un bye.
        val parSeed = ordered.withIndex().associate { (i, p) -> i + 1 to p.registrationId }
        planifies.filter { it.seedA != null || it.seedB != null }.forEach { plan ->
            val regA = plan.seedA?.let { parSeed[it] }
            val regB = plan.seedB?.let { parSeed[it] }
            val matchId = idParCle.getValue(plan.cle)
            repo.setParticipants(matchId, regA, regB)

            // Bye : un seul participant présent → qualification immédiate (spec §4.2)
            val seul = when {
                regA != null && regB == null -> regA
                regA == null && regB != null -> regB
                else -> null
            }
            if (seul != null) {
                repo.setResult(matchId, seul, MatchStatus.finished)
                plan.vainqueurVers?.let { cible ->
                    repo.remplirPremierSlotLibre(idParCle.getValue(cible), seul)
                }
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
        val loser = if (winner == p1) p2 else p1
        repo.setResult(matchId, winner, MatchStatus.finished)
        propagateWinner(match.position!!, match.nextMatchId, winner)

        // Élimination double : le perdant n'est pas éliminé, il descend dans le
        // tableau des perdants. Le slot dépend de qui est déjà arrivé.
        match.nextMatchLoserId?.let { cible ->
            if (!repo.remplirPremierSlotLibre(cible, loser)) {
                log.warn("Aucun slot libre dans le match {} pour le perdant de {}", cible, matchId)
            }
        }

        val tournamentId = repo.findPhaseTournamentId(match.phaseId!!)
            ?: throw ErreurMetier.Conflit("Phase orpheline")

        // Fin de tournoi : selon le format, ce n'est pas le même critère.
        //  - arbre (simple, double) : le match sans suite est la finale
        //  - round robin : il n'y a pas de finale, on attend la dernière rencontre
        val restants = repo.findPhaseMatches(match.phaseId!!)
            .count { it.status != MatchStatus.finished && it.status != MatchStatus.forfeited }
        val termine = if (match.bracket == BracketType.group) restants == 0 else match.nextMatchId == null
        repo.setTournamentStatus(
            tournamentId,
            if (termine) TournamentStatus.finished else TournamentStatus.ongoing,
        )

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
        // Les libellés dépendent du format : « Demi-finales » n'a aucun sens dans
        // un round robin, et un tableau des perdants doit être annoncé comme tel.
        val parTableau = matches.groupBy { it.bracket ?: BracketType.winner }
        val toursW = parTableau[BracketType.winner]?.map { it.round!! }?.distinct()?.sorted() ?: emptyList()
        val toursL = parTableau[BracketType.loser]?.map { it.round!! }?.distinct()?.sorted() ?: emptyList()
        val doubleElim = toursL.isNotEmpty()

        val rounds = matches.groupBy { it.round!! }.toSortedMap().map { (round, roundMatches) ->
            val tableau = roundMatches.first().bracket ?: BracketType.winner
            val (label, codePrefix) = when (tableau) {
                BracketType.group -> "Journée $round" to "J$round-"
                BracketType.grand_final -> "Grande finale" to "GF"
                BracketType.loser -> {
                    val rang = toursL.indexOf(round) + 1
                    val dernier = rang == toursL.size
                    (if (dernier) "Finale des perdants" else "Perdants — tour $rang") to "LB$rang-"
                }
                BracketType.winner -> {
                    val rang = toursW.indexOf(round) + 1
                    val (base, prefixe) = Display.roundLabel(rang, toursW.size)
                    // En double élimination, la « finale » du tableau des vainqueurs
                    // n'est pas la finale du tournoi : la grande finale l'est.
                    val ajuste = if (doubleElim && rang == toursW.size) "Finale des vainqueurs" else base
                    ajuste to prefixe
                }
            }
            BracketRoundDto(
                label = label,
                matches = roundMatches.sortedBy { it.position }.map { m ->
                    toMatchDto(m, codePrefix, scores[m.id], infos, colorBySeed)
                },
            )
        }

        val champion = championDe(matches, parTableau, infos)

        return BracketDto(rounds = rounds, champion = champion)
    }

    /**
     * Vainqueur du tournoi, s'il est déjà connu.
     *
     * - arbre : le vainqueur du dernier match (grande finale, ou finale simple)
     * - round robin : celui qui compte le plus de victoires, une fois toutes les
     *   rencontres jouées. Avant cela, personne n'est champion.
     */
    private fun championDe(
        matches: List<MatchesRecord>,
        parTableau: Map<BracketType, List<MatchesRecord>>,
        infos: Map<UUID, org.example.backend.repository.RegistrationInfo>,
    ): String? {
        val poules = parTableau[BracketType.group]
        if (poules != null && poules.isNotEmpty()) {
            if (poules.any { it.status != MatchStatus.finished && it.status != MatchStatus.forfeited }) {
                return null
            }
            val victoires = poules.mapNotNull { it.winnerId }.groupingBy { it }.eachCount()
            return victoires.maxByOrNull { it.value }?.key?.let { infos[it]?.displayName }
        }
        val dernier = matches.maxByOrNull { it.round!! } ?: return null
        return dernier.winnerId?.let { infos[it]?.displayName }
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
