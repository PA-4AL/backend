package org.example.backend.repository

import org.example.backend.database.enums.MatchStatus
import org.example.backend.database.enums.TournamentStatus
import org.example.backend.database.tables.references.GAME_ACCOUNTS
import org.example.backend.database.tables.references.MATCHES
import org.example.backend.database.tables.references.PHASES
import org.example.backend.database.tables.references.REGISTRATIONS
import org.example.backend.database.tables.references.TOURNAMENTS
import org.example.backend.database.tables.references.USERS
import org.jooq.DSLContext
import org.springframework.stereotype.Repository
import java.util.UUID

data class GameAccountRow(val id: UUID, val game: String, val identifier: String)

data class HistoryRow(
    val registrationId: UUID,
    val tournamentId: UUID,
    val tournamentName: String,
    val game: String,
    val tournamentStatus: TournamentStatus,
    val matchesPlayed: Int,
    val matchesWon: Int,
    val wonFinal: Boolean,
)

data class UserInfoRow(val pseudo: String, val avatarUrl: String?)

@Repository
class ProfileRepository(private val dsl: DSLContext) {

    fun userInfo(userId: UUID): UserInfoRow? =
        dsl.select(USERS.PSEUDO, USERS.AVATAR_URL)
            .from(USERS)
            .where(USERS.ID.eq(userId))
            .fetchOne { UserInfoRow(it.get(USERS.PSEUDO)!!, it.get(USERS.AVATAR_URL)) }

    fun updateProfile(userId: UUID, pseudo: String?, avatarUrl: String?) {
        var q = dsl.update(USERS).set(USERS.ID, userId) // point de départ neutre
        if (pseudo != null) q = q.set(USERS.PSEUDO, pseudo)
        if (avatarUrl != null) q = q.set(USERS.AVATAR_URL, avatarUrl.ifEmpty { null })
        q.where(USERS.ID.eq(userId)).execute()
    }

    fun listGameAccounts(userId: UUID): List<GameAccountRow> =
        dsl.select(GAME_ACCOUNTS.ID, GAME_ACCOUNTS.GAME, GAME_ACCOUNTS.IDENTIFIER)
            .from(GAME_ACCOUNTS)
            .where(GAME_ACCOUNTS.USER_ID.eq(userId))
            .orderBy(GAME_ACCOUNTS.GAME.asc())
            .fetch { r ->
                GameAccountRow(
                    id = r.get(GAME_ACCOUNTS.ID)!!,
                    game = r.get(GAME_ACCOUNTS.GAME)!!,
                    identifier = r.get(GAME_ACCOUNTS.IDENTIFIER)!!,
                )
            }

    fun addGameAccount(userId: UUID, game: String, identifier: String): GameAccountRow {
        val id = dsl.insertInto(GAME_ACCOUNTS)
            .set(GAME_ACCOUNTS.USER_ID, userId)
            .set(GAME_ACCOUNTS.GAME, game)
            .set(GAME_ACCOUNTS.IDENTIFIER, identifier)
            .returning(GAME_ACCOUNTS.ID)
            .fetchOne()!!
            .get(GAME_ACCOUNTS.ID)!!
        return GameAccountRow(id, game, identifier)
    }

    /** Supprime uniquement si le compte appartient bien à l'utilisateur. */
    fun deleteGameAccount(userId: UUID, accountId: UUID): Boolean =
        dsl.deleteFrom(GAME_ACCOUNTS)
            .where(GAME_ACCOUNTS.ID.eq(accountId).and(GAME_ACCOUNTS.USER_ID.eq(userId)))
            .execute() == 1

    /** Historique des tournois de l'utilisateur (inscriptions solo) avec ses matchs. */
    fun history(userId: UUID): List<HistoryRow> {
        val registrations = dsl.select(
            REGISTRATIONS.ID, TOURNAMENTS.ID, TOURNAMENTS.NAME, TOURNAMENTS.STATUS, PHASES.GAME,
        )
            .from(REGISTRATIONS)
            .join(TOURNAMENTS).on(TOURNAMENTS.ID.eq(REGISTRATIONS.TOURNAMENT_ID))
            .leftJoin(PHASES).on(PHASES.TOURNAMENT_ID.eq(TOURNAMENTS.ID).and(PHASES.POSITION.eq(1)))
            .where(REGISTRATIONS.USER_ID.eq(userId))
            .orderBy(REGISTRATIONS.CREATED_AT.desc())
            .fetch()

        return registrations.map { r ->
            val regId = r.get(REGISTRATIONS.ID)!!
            val played = dsl.fetchCount(
                MATCHES,
                MATCHES.PARTICIPANT1_ID.eq(regId).or(MATCHES.PARTICIPANT2_ID.eq(regId))
                    .and(MATCHES.STATUS.`in`(MatchStatus.finished, MatchStatus.forfeited)),
            )
            val won = dsl.fetchCount(MATCHES, MATCHES.WINNER_ID.eq(regId))
            val wonFinal = dsl.fetchExists(
                MATCHES,
                MATCHES.WINNER_ID.eq(regId).and(MATCHES.NEXT_MATCH_ID.isNull),
            )
            HistoryRow(
                registrationId = regId,
                tournamentId = r.get(TOURNAMENTS.ID)!!,
                tournamentName = r.get(TOURNAMENTS.NAME)!!,
                game = r.get(PHASES.GAME) ?: "—",
                tournamentStatus = r.get(TOURNAMENTS.STATUS)!!,
                matchesPlayed = played,
                matchesWon = won,
                wonFinal = wonFinal,
            )
        }
    }
}
