package org.example.backend.repository

import org.example.backend.database.tables.references.MATCHDB
import org.example.backend.database.tables.references.PLAYERDB
import org.example.backend.database.tables.references.TOURNAMENTDB
import org.example.backend.model.Match
import org.example.backend.model.Player
import org.example.backend.model.Tournament
import org.jooq.DSLContext
import org.springframework.stereotype.Repository
import java.time.LocalDate

@Repository
class TournamentRepository(private val dsl: DSLContext) {

    fun saveTournament(tournament: Tournament): Tournament {
        val savedId = dsl.insertInto(TOURNAMENTDB)
            .set(TOURNAMENTDB.NAME, tournament.name)
            .set(TOURNAMENTDB.START_DATE, LocalDate.parse(tournament.startDate))
            .set(TOURNAMENTDB.END_DATE, LocalDate.parse(tournament.endDate))
            .set(TOURNAMENTDB.MAX_PLAYERS, tournament.maxPlayers)
            .set(TOURNAMENTDB.STATUS, tournament.status.name)
            .returning(TOURNAMENTDB.ID)
            .fetchOne()
            ?.getValue(TOURNAMENTDB.ID)

        return tournament.copy(id = savedId)
    }

    fun findAllTournaments(): List<Tournament> {
        return dsl.selectFrom(TOURNAMENTDB)
            .fetchInto(Tournament::class.java)
    }

    fun findById(id: Long): Tournament? {
        return dsl.selectFrom(TOURNAMENTDB)
            .where(TOURNAMENTDB.ID.eq(id))
            .fetchOneInto(Tournament::class.java)
    }

    fun savePlayer(player: Player): Player {
        val savedId = dsl.insertInto(PLAYERDB)
            .set(PLAYERDB.TOURNAMENT_ID, player.tournamentId)
            .set(PLAYERDB.NAME, player.name)
            .set(PLAYERDB.SCORE, player.score)
            .returning(PLAYERDB.ID)
            .fetchOne()
            ?.getValue(PLAYERDB.ID)

        return player.copy(id = savedId)
    }

    fun saveMatch(match: Match): Match {
        val savedId = dsl.insertInto(MATCHDB)
            .set(MATCHDB.TOURNAMENT_ID, match.tournamentId)
            .set(MATCHDB.PLAYER1_ID, match.player1Id)
            .set(MATCHDB.PLAYER2_ID, match.player2Id)
            .set(MATCHDB.SCORE_PLAYER1, match.scorePlayer1)
            .set(MATCHDB.SCORE_PLAYER2, match.scorePlayer2)
            .returning(MATCHDB.ID)
            .fetchOne()
            ?.getValue(MATCHDB.ID)

        return match.copy(id = savedId)
    }
}
