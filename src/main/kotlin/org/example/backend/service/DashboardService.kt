package org.example.backend.service

import org.example.backend.database.enums.MatchStatus
import org.example.backend.database.enums.RegistrationStatus
import org.example.backend.database.enums.TournamentStatus
import org.example.backend.database.tables.references.MATCHES
import org.example.backend.database.tables.references.REGISTRATIONS
import org.example.backend.database.tables.references.TEAMS
import org.example.backend.database.tables.references.TOURNAMENTS
import org.example.backend.database.tables.references.USERS
import org.example.backend.model.ActivityItemDto
import org.example.backend.model.DashboardKpisDto
import org.jooq.DSLContext
import org.springframework.stereotype.Service
import java.time.Duration
import java.time.OffsetDateTime

@Service
class DashboardService(private val dsl: DSLContext) {

    fun kpis(): DashboardKpisDto {
        val active = dsl.fetchCount(
            TOURNAMENTS,
            TOURNAMENTS.STATUS.`in`(
                TournamentStatus.registration, TournamentStatus.check_in, TournamentStatus.ongoing,
            ),
        )
        val createdThisWeek = dsl.fetchCount(
            TOURNAMENTS,
            TOURNAMENTS.CREATED_AT.gt(OffsetDateTime.now().minusDays(7)),
        )
        val liveMatches = dsl.fetchCount(MATCHES, MATCHES.STATUS.eq(MatchStatus.ongoing))
        val participants = dsl.fetchCount(
            REGISTRATIONS,
            REGISTRATIONS.STATUS.ne(RegistrationStatus.withdrawn),
        )
        val registeredThisMonth = dsl.fetchCount(
            REGISTRATIONS,
            REGISTRATIONS.CREATED_AT.gt(OffsetDateTime.now().minusDays(30)),
        )
        val pending = dsl.fetchCount(
            REGISTRATIONS,
            REGISTRATIONS.STATUS.eq(RegistrationStatus.pending),
        )

        return DashboardKpisDto(
            activeTournaments = active,
            activeTournamentsDelta = "+$createdThisWeek cette semaine",
            liveMatches = liveMatches,
            participants = participants,
            participantsDelta = "+$registeredThisMonth ce mois",
            pendingValidations = pending,
        )
    }

    /** Fil d'activité réel : dernières inscriptions et derniers tournois créés. */
    fun activity(): List<ActivityItemDto> {
        data class Event(val id: String, val kind: String, val html: String, val at: OffsetDateTime)

        val registrations = dsl.select(
            REGISTRATIONS.ID, REGISTRATIONS.CREATED_AT,
            TEAMS.NAME, USERS.PSEUDO, TOURNAMENTS.NAME,
        )
            .from(REGISTRATIONS)
            .join(TOURNAMENTS).on(TOURNAMENTS.ID.eq(REGISTRATIONS.TOURNAMENT_ID))
            .leftJoin(TEAMS).on(TEAMS.ID.eq(REGISTRATIONS.TEAM_ID))
            .leftJoin(USERS).on(USERS.ID.eq(REGISTRATIONS.USER_ID))
            .orderBy(REGISTRATIONS.CREATED_AT.desc())
            .limit(5)
            .fetch { r ->
                val who = r.get(TEAMS.NAME) ?: r.get(USERS.PSEUDO) ?: "Un participant"
                Event(
                    id = "reg-${r.get(REGISTRATIONS.ID)}",
                    kind = "registration",
                    html = "<b>$who</b> s'est inscrit sur ${r.get(TOURNAMENTS.NAME)}.",
                    at = r.get(REGISTRATIONS.CREATED_AT)!!,
                )
            }

        val tournaments = dsl.select(TOURNAMENTS.ID, TOURNAMENTS.NAME, TOURNAMENTS.STATUS, TOURNAMENTS.CREATED_AT)
            .from(TOURNAMENTS)
            .orderBy(TOURNAMENTS.CREATED_AT.desc())
            .limit(5)
            .fetch { r ->
                val finished = r.get(TOURNAMENTS.STATUS) == TournamentStatus.finished
                Event(
                    id = "trn-${r.get(TOURNAMENTS.ID)}",
                    kind = if (finished) "finished" else "live",
                    html = if (finished) "<b>${r.get(TOURNAMENTS.NAME)}</b> est terminé."
                    else "Tournoi <b>${r.get(TOURNAMENTS.NAME)}</b> créé.",
                    at = r.get(TOURNAMENTS.CREATED_AT)!!,
                )
            }

        return (registrations + tournaments)
            .sortedByDescending { it.at }
            .take(6)
            .map { ActivityItemDto(it.id, it.kind, it.html, relativeTime(it.at)) }
    }

    private fun relativeTime(at: OffsetDateTime): String {
        val d = Duration.between(at, OffsetDateTime.now())
        return when {
            d.toMinutes() < 1 -> "À l'instant"
            d.toMinutes() < 60 -> "Il y a ${d.toMinutes()} min"
            d.toHours() < 24 -> "Il y a ${d.toHours()} h"
            else -> "Il y a ${d.toDays()} j"
        }
    }
}
