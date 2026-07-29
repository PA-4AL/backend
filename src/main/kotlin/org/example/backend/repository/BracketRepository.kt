package org.example.backend.repository

import org.example.backend.database.enums.BracketType
import org.example.backend.database.enums.MatchStatus
import org.example.backend.database.enums.TournamentStatus
import org.example.backend.database.tables.records.MatchesRecord
import org.example.backend.database.tables.references.MATCHES
import org.example.backend.database.tables.references.MATCH_GAMES
import org.example.backend.database.tables.references.PHASES
import org.example.backend.database.tables.references.REGISTRATIONS
import org.example.backend.database.tables.references.TEAMS
import org.example.backend.database.tables.references.TOURNAMENTS
import org.example.backend.database.tables.references.USERS
import org.jooq.DSLContext
import org.springframework.stereotype.Repository
import java.util.UUID

data class RegistrationInfo(val registrationId: UUID, val displayName: String, val seed: Int?)

@Repository
class BracketRepository(private val dsl: DSLContext) {

    fun deletePhaseMatches(phaseId: UUID) {
        dsl.deleteFrom(MATCHES).where(MATCHES.PHASE_ID.eq(phaseId)).execute()
    }

    fun insertMatch(
        phaseId: UUID,
        round: Int,
        position: Int,
        bestOf: Int,
        nextMatchId: UUID?,
        bracket: BracketType = BracketType.winner,
        nextMatchLoserId: UUID? = null,
    ): UUID = dsl.insertInto(MATCHES)
        .set(MATCHES.PHASE_ID, phaseId)
        .set(MATCHES.ROUND, round)
        .set(MATCHES.POSITION, position)
        .set(MATCHES.BRACKET, bracket)
        .set(MATCHES.BEST_OF, bestOf)
        .set(MATCHES.STATUS, MatchStatus.pending)
        .set(MATCHES.NEXT_MATCH_ID, nextMatchId)
        .set(MATCHES.NEXT_MATCH_LOSER_ID, nextMatchLoserId)
        .returning(MATCHES.ID)
        .fetchOne()!!
        .get(MATCHES.ID)!!

    /**
     * Renseigne les liens de sortie après coup.
     *
     * Nécessaire pour l'élimination double : un match du tableau des vainqueurs
     * envoie son perdant dans le tableau des perdants, qui est créé après lui. On
     * insère donc d'abord, on relie ensuite.
     */
    fun updateLiens(matchId: UUID, nextMatchId: UUID?, nextMatchLoserId: UUID?): Int = dsl.update(MATCHES)
        .set(MATCHES.NEXT_MATCH_ID, nextMatchId)
        .set(MATCHES.NEXT_MATCH_LOSER_ID, nextMatchLoserId)
        .where(MATCHES.ID.eq(matchId))
        .execute()

    /**
     * Place un participant dans le premier slot libre d'un match.
     *
     * Utilisé pour les descentes en élimination double : le perdant d'un match du
     * tableau des vainqueurs rejoint un match du tableau des perdants dont l'autre
     * slot est déjà occupé (tour de bascule) ou encore vide (premier tour, où deux
     * perdants s'affrontent).
     *
     * Renvoie `false` si les deux slots sont déjà pris — signe d'une incohérence de
     * chaînage, que l'appelant doit journaliser.
     */
    fun remplirPremierSlotLibre(matchId: UUID, registrationId: UUID): Boolean {
        val match = findMatch(matchId) ?: return false
        val champ = when {
            match.participant1Id == null -> MATCHES.PARTICIPANT1_ID
            match.participant2Id == null -> MATCHES.PARTICIPANT2_ID
            else -> return false
        }
        dsl.update(MATCHES)
            .set(champ, registrationId)
            .where(MATCHES.ID.eq(matchId))
            .execute()
        return true
    }

    fun setParticipants(matchId: UUID, participant1: UUID?, participant2: UUID?) {
        dsl.update(MATCHES)
            .set(MATCHES.PARTICIPANT1_ID, participant1)
            .set(MATCHES.PARTICIPANT2_ID, participant2)
            .where(MATCHES.ID.eq(matchId))
            .execute()
    }

    /** Place un participant dans le slot 1 (position impaire) ou 2 du match. */
    fun fillSlot(matchId: UUID, slot1: Boolean, registrationId: UUID) {
        dsl.update(MATCHES)
            .set(if (slot1) MATCHES.PARTICIPANT1_ID else MATCHES.PARTICIPANT2_ID, registrationId)
            .where(MATCHES.ID.eq(matchId))
            .execute()
    }

    fun setResult(matchId: UUID, winnerId: UUID, status: MatchStatus) {
        dsl.update(MATCHES)
            .set(MATCHES.WINNER_ID, winnerId)
            .set(MATCHES.STATUS, status)
            .where(MATCHES.ID.eq(matchId))
            .execute()
    }

    fun findMatch(id: UUID): MatchesRecord? = dsl.selectFrom(MATCHES).where(MATCHES.ID.eq(id)).fetchOne()

    fun findPhaseMatches(phaseId: UUID): List<MatchesRecord> = dsl.selectFrom(MATCHES)
        .where(MATCHES.PHASE_ID.eq(phaseId))
        .orderBy(MATCHES.ROUND.asc(), MATCHES.POSITION.asc())
        .fetch()

    /** Score agrégé (manches gagnées) par match. */
    fun findScores(phaseId: UUID): Map<UUID, Pair<Int, Int>> =
        dsl.select(MATCH_GAMES.MATCH_ID, MATCH_GAMES.SCORE1, MATCH_GAMES.SCORE2)
            .from(MATCH_GAMES)
            .join(MATCHES).on(MATCHES.ID.eq(MATCH_GAMES.MATCH_ID))
            .where(MATCHES.PHASE_ID.eq(phaseId))
            .fetchGroups(MATCH_GAMES.MATCH_ID)
            .mapNotNull { (id, rows) ->
                id?.let {
                    it to Pair(
                        rows.sumOf { r -> r.get(MATCH_GAMES.SCORE1) ?: 0 },
                        rows.sumOf { r -> r.get(MATCH_GAMES.SCORE2) ?: 0 },
                    )
                }
            }
            .toMap()

    fun replaceScore(matchId: UUID, score1: Int, score2: Int) {
        dsl.deleteFrom(MATCH_GAMES).where(MATCH_GAMES.MATCH_ID.eq(matchId)).execute()
        dsl.insertInto(MATCH_GAMES)
            .set(MATCH_GAMES.MATCH_ID, matchId)
            .set(MATCH_GAMES.GAME_NUMBER, 1)
            .set(MATCH_GAMES.SCORE1, score1)
            .set(MATCH_GAMES.SCORE2, score2)
            .execute()
    }

    /** Toutes les inscriptions du tournoi avec nom affichable (équipe ou joueur). */
    fun findRegistrationInfo(tournamentId: UUID): Map<UUID, RegistrationInfo> =
        dsl.select(REGISTRATIONS.ID, REGISTRATIONS.SEED, TEAMS.NAME, USERS.PSEUDO)
            .from(REGISTRATIONS)
            .leftJoin(TEAMS).on(TEAMS.ID.eq(REGISTRATIONS.TEAM_ID))
            .leftJoin(USERS).on(USERS.ID.eq(REGISTRATIONS.USER_ID))
            .where(REGISTRATIONS.TOURNAMENT_ID.eq(tournamentId))
            .fetch { r ->
                val id = r.get(REGISTRATIONS.ID)!!
                id to RegistrationInfo(
                    registrationId = id,
                    displayName = r.get(TEAMS.NAME) ?: r.get(USERS.PSEUDO) ?: "?",
                    seed = r.get(REGISTRATIONS.SEED),
                )
            }
            .toMap()

    fun updateSeed(registrationId: UUID, seed: Int) {
        dsl.update(REGISTRATIONS)
            .set(REGISTRATIONS.SEED, seed)
            .where(REGISTRATIONS.ID.eq(registrationId))
            .execute()
    }

    fun findPhaseTournamentId(phaseId: UUID): UUID? = dsl.select(PHASES.TOURNAMENT_ID)
        .from(PHASES)
        .where(PHASES.ID.eq(phaseId))
        .fetchOne(PHASES.TOURNAMENT_ID)

    fun setTournamentStatus(tournamentId: UUID, status: TournamentStatus) {
        dsl.update(TOURNAMENTS)
            .set(TOURNAMENTS.STATUS, status)
            .where(TOURNAMENTS.ID.eq(tournamentId))
            .execute()
    }
}
