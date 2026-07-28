package org.example.backend.repository

import org.example.backend.database.enums.MatchStatus
import org.example.backend.database.enums.RegistrationStatus
import org.example.backend.database.enums.TournamentStatus
import org.example.backend.database.tables.references.MATCHES
import org.example.backend.database.tables.references.REGISTRATIONS
import org.example.backend.database.tables.references.TEAMS
import org.example.backend.database.tables.references.TOURNAMENTS
import org.example.backend.database.tables.references.USERS
import org.jooq.DSLContext
import org.springframework.stereotype.Repository
import java.time.OffsetDateTime
import java.util.UUID

/** Nom affichable laissé à `null` si l'inscription n'a ni équipe ni joueur rattaché. */
data class RecentRegistrationRow(
    val id: UUID,
    val participantName: String?,
    val tournamentName: String,
    val createdAt: OffsetDateTime,
)

data class RecentTournamentRow(
    val id: UUID,
    val name: String,
    val status: TournamentStatus,
    val createdAt: OffsetDateTime,
)

/**
 * Compteurs et derniers événements du tableau de bord.
 *
 * Les fenêtres temporelles sont passées par le service : elles relèvent de la règle
 * métier (« cette semaine », « ce mois »), pas de la requête.
 */
@Repository
class DashboardRepository(private val dsl: DSLContext) {

    fun countActiveTournaments(): Int = dsl.fetchCount(
        TOURNAMENTS,
        TOURNAMENTS.STATUS.`in`(
            TournamentStatus.registration,
            TournamentStatus.check_in,
            TournamentStatus.ongoing,
        ),
    )

    fun countTournamentsCreatedSince(since: OffsetDateTime): Int =
        dsl.fetchCount(TOURNAMENTS, TOURNAMENTS.CREATED_AT.gt(since))

    fun countLiveMatches(): Int = dsl.fetchCount(MATCHES, MATCHES.STATUS.eq(MatchStatus.ongoing))

    /** Inscriptions hors désistements. */
    fun countActiveRegistrations(): Int =
        dsl.fetchCount(REGISTRATIONS, REGISTRATIONS.STATUS.ne(RegistrationStatus.withdrawn))

    fun countRegistrationsCreatedSince(since: OffsetDateTime): Int =
        dsl.fetchCount(REGISTRATIONS, REGISTRATIONS.CREATED_AT.gt(since))

    fun countPendingRegistrations(): Int =
        dsl.fetchCount(REGISTRATIONS, REGISTRATIONS.STATUS.eq(RegistrationStatus.pending))

    fun recentRegistrations(limit: Int): List<RecentRegistrationRow> = dsl.select(
        REGISTRATIONS.ID,
        REGISTRATIONS.CREATED_AT,
        TEAMS.NAME,
        USERS.PSEUDO,
        TOURNAMENTS.NAME,
    )
        .from(REGISTRATIONS)
        .join(TOURNAMENTS).on(TOURNAMENTS.ID.eq(REGISTRATIONS.TOURNAMENT_ID))
        .leftJoin(TEAMS).on(TEAMS.ID.eq(REGISTRATIONS.TEAM_ID))
        .leftJoin(USERS).on(USERS.ID.eq(REGISTRATIONS.USER_ID))
        .orderBy(REGISTRATIONS.CREATED_AT.desc())
        .limit(limit)
        .fetch { r ->
            RecentRegistrationRow(
                id = r.get(REGISTRATIONS.ID)!!,
                participantName = r.get(TEAMS.NAME) ?: r.get(USERS.PSEUDO),
                tournamentName = r.get(TOURNAMENTS.NAME)!!,
                createdAt = r.get(REGISTRATIONS.CREATED_AT)!!,
            )
        }

    fun recentTournaments(limit: Int): List<RecentTournamentRow> =
        dsl.select(TOURNAMENTS.ID, TOURNAMENTS.NAME, TOURNAMENTS.STATUS, TOURNAMENTS.CREATED_AT)
            .from(TOURNAMENTS)
            .orderBy(TOURNAMENTS.CREATED_AT.desc())
            .limit(limit)
            .fetch { r ->
                RecentTournamentRow(
                    id = r.get(TOURNAMENTS.ID)!!,
                    name = r.get(TOURNAMENTS.NAME)!!,
                    status = r.get(TOURNAMENTS.STATUS)!!,
                    createdAt = r.get(TOURNAMENTS.CREATED_AT)!!,
                )
            }
}
