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
import org.example.backend.repository.RegistrationRepository
import org.example.backend.repository.TournamentRepository
import org.example.backend.service.bracket.CalculClassement
import org.example.backend.service.bracket.GenerateurBracket
import org.example.backend.service.bracket.MatchJoue
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

@Service
class BracketService(
    private val tournaments: TournamentRepository,
    private val repo: BracketRepository,
    private val inscriptions: RegistrationRepository,
) {

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
        tournaments.findById(tournamentId)
            ?: throw ErreurMetier.Introuvable("Tournoi introuvable")

        val phase = tournaments.findFirstPhase(tournamentId)
            ?: throw ErreurMetier.Conflit("Le tournoi n'a aucune phase")

        // Le statut n'est pas le bon critère. Un tournoi peut être « en cours »
        // sans qu'aucun match n'ait jamais été saisi — c'était alors un
        // cul-de-sac : l'arbre ne pouvait plus être généré, donc le tournoi ne
        // pouvait plus être joué. Ce qu'il faut protéger, c'est la perte de
        // résultats, pas un statut.
        if (aDesResultats(phase.id!!)) {
            throw ErreurMetier.Conflit(
                "Des résultats ont déjà été saisis : régénérer l'arbre les effacerait",
            )
        }

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

    /**
     * Un résultat a-t-il déjà été saisi dans cette phase ?
     *
     * Attention au piège : la génération résout les byes en marquant des matchs
     * `finished` d'emblée. Un match terminé n'est donc pas la preuve qu'on a
     * joué. Le critère est un match **à deux participants** doté d'un vainqueur,
     * ou l'existence d'un score enregistré.
     */
    private fun aDesResultats(phaseId: UUID): Boolean {
        if (repo.findScores(phaseId).isNotEmpty()) return true
        return repo.findPhaseMatches(phaseId).any {
            it.participant1Id != null && it.participant2Id != null && it.winnerId != null
        }
    }

    /* ------------------------------------------------------------
       Placement manuel des participants
       ------------------------------------------------------------ */

    /**
     * Échange les deux participants de deux emplacements de l'arbre.
     *
     * Le seeding automatique ne convient pas toujours : on veut pouvoir éviter
     * que deux équipes d'un même club se rencontrent au premier tour, ou refaire
     * un tirage à la main. Un emplacement est désigné par un match et un slot
     * (1 ou 2) — la même désignation que celle affichée à l'écran.
     *
     * Un emplacement vide est accepté : échanger avec du vide **déplace** le
     * participant, ce qui évite d'avoir deux opérations pour un seul geste.
     *
     * @throws ErreurMetier.Conflit si l'un des deux matchs est déjà joué, ou si
     *   l'échange mettrait deux fois le même participant dans un match.
     */
    @Transactional
    fun echangerEmplacements(matchA: UUID, slotA: Int, matchB: UUID, slotB: Int): BracketDto {
        if (slotA !in 1..2 || slotB !in 1..2) {
            throw ErreurMetier.Invalide("Un slot vaut 1 ou 2")
        }
        if (matchA == matchB && slotA == slotB) {
            throw ErreurMetier.Invalide("Les deux emplacements sont identiques")
        }

        val a = repo.findMatch(matchA) ?: throw ErreurMetier.Introuvable("Match introuvable")
        val b = repo.findMatch(matchB) ?: throw ErreurMetier.Introuvable("Match introuvable")
        if (a.phaseId != b.phaseId) {
            throw ErreurMetier.Invalide("Les deux emplacements doivent être dans la même phase")
        }

        // Déplacer un participant hors d'un match joué invaliderait son résultat.
        listOf(a, b).forEach { m ->
            if (m.status == MatchStatus.finished || m.winnerId != null) {
                throw ErreurMetier.Conflit("Un match déjà joué ne peut pas être réorganisé")
            }
        }

        val occupantA = if (slotA == 1) a.participant1Id else a.participant2Id
        val occupantB = if (slotB == 1) b.participant1Id else b.participant2Id
        if (occupantA == null && occupantB == null) {
            throw ErreurMetier.Invalide("Les deux emplacements sont vides")
        }

        // Le cas de deux slots du même match se calcule à part : les deux
        // écritures portent sur la même ligne, un `setParticipants` par match
        // écraserait la première.
        if (matchA == matchB) {
            repo.setParticipants(matchA, occupantB, occupantA)
        } else {
            val autreDeA = if (slotA == 1) a.participant2Id else a.participant1Id
            val autreDeB = if (slotB == 1) b.participant2Id else b.participant1Id
            if (occupantB != null && occupantB == autreDeA) {
                throw ErreurMetier.Conflit("Ce participant est déjà présent dans le match d'arrivée")
            }
            if (occupantA != null && occupantA == autreDeB) {
                throw ErreurMetier.Conflit("Ce participant est déjà présent dans le match d'arrivée")
            }
            repo.setParticipants(
                matchA,
                if (slotA == 1) occupantB else autreDeA,
                if (slotA == 1) autreDeA else occupantB,
            )
            repo.setParticipants(
                matchB,
                if (slotB == 1) occupantA else autreDeB,
                if (slotB == 1) autreDeB else occupantA,
            )
        }

        val tournamentId = repo.findPhaseTournamentId(a.phaseId!!)
            ?: throw ErreurMetier.Introuvable("Tournoi introuvable")
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
        // Le classement se fige à la fin, pas à chaque score : c'est un résultat,
        // et il doit rester consultable et corrigeable après coup.
        if (termine) figerLeClassement(tournamentId, match.phaseId!!)

        return getBracket(tournamentId)
    }

    /**
     * Calcule et enregistre le classement final des inscriptions.
     *
     * Il était jusqu'ici recalculé à la volée par le worker à chaque export : donc
     * invisible dans l'application, et impossible à corriger. Le voici persisté
     * dans `registrations.final_rank`.
     *
     * Le tri est celui de [CalculClassement], délibérément identique à celui du
     * worker : deux classements divergents seraient pires que pas de classement.
     */
    private fun figerLeClassement(tournamentId: UUID, phaseId: UUID) {
        val participants = tournaments.findActiveParticipants(tournamentId)
        val scores = repo.findScores(phaseId)
        val joues = repo.findPhaseMatches(phaseId).mapNotNull { m ->
            val a = m.participant1Id ?: return@mapNotNull null
            val b = m.participant2Id ?: return@mapNotNull null
            val score = scores[m.id] ?: return@mapNotNull null
            MatchJoue(a, b, score.first, score.second)
        }

        CalculClassement.calculer(participants.map { it.registrationId }, joues)
            .forEachIndexed { index, registrationId ->
                inscriptions.updateFinalRank(registrationId, index + 1)
            }
        log.info("Classement figé pour le tournoi {} ({} inscriptions)", tournamentId, participants.size)
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
