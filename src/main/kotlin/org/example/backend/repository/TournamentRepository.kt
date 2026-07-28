package org.example.backend.repository

import org.example.backend.database.enums.MatchStatus
import org.example.backend.database.enums.OrganizerRole
import org.example.backend.database.enums.PhaseType
import org.example.backend.database.enums.RegistrationStatus
import org.example.backend.database.enums.TournamentStatus
import org.example.backend.database.enums.TournamentVisibility
import org.example.backend.database.tables.records.PhasesRecord
import org.example.backend.database.tables.records.TournamentsRecord
import org.example.backend.database.tables.references.MATCHES
import org.example.backend.database.tables.references.PHASES
import org.example.backend.database.tables.references.REGISTRATIONS
import org.example.backend.database.tables.references.TEAMS
import org.example.backend.database.tables.references.TOURNAMENTS
import org.example.backend.database.tables.references.TOURNAMENT_ORGANIZERS
import org.example.backend.database.tables.references.USERS
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.springframework.stereotype.Repository
import java.util.UUID

data class ParticipantRow(val registrationId: UUID, val displayName: String, val seed: Int?)

@Repository
class TournamentRepository(private val dsl: DSLContext) {

    fun findAll(): List<Pair<TournamentsRecord, Int>> {
        // Nombre d'inscriptions actives (hors désistements) par tournoi
        val participants = DSL.selectCount()
            .from(REGISTRATIONS)
            .where(
                REGISTRATIONS.TOURNAMENT_ID.eq(TOURNAMENTS.ID)
                    .and(REGISTRATIONS.STATUS.ne(RegistrationStatus.withdrawn)),
            )
            .asField<Int>("participants")

        return dsl.select(TOURNAMENTS.asterisk(), participants)
            .from(TOURNAMENTS)
            .orderBy(TOURNAMENTS.START_AT.desc().nullsLast())
            .fetch { r -> r.into(TOURNAMENTS) to (r.get("participants", Int::class.java) ?: 0) }
    }

    fun findById(id: UUID): TournamentsRecord? = dsl.selectFrom(TOURNAMENTS).where(TOURNAMENTS.ID.eq(id)).fetchOne()

    fun countParticipants(tournamentId: UUID): Int = dsl.fetchCount(
        REGISTRATIONS,
        REGISTRATIONS.TOURNAMENT_ID.eq(tournamentId)
            .and(REGISTRATIONS.STATUS.ne(RegistrationStatus.withdrawn)),
    )

    fun findFirstPhase(tournamentId: UUID): PhasesRecord? = dsl.selectFrom(PHASES)
        .where(PHASES.TOURNAMENT_ID.eq(tournamentId))
        .orderBy(PHASES.POSITION.asc())
        .limit(1)
        .fetchOne()

    fun findPhases(tournamentId: UUID): List<PhasesRecord> = dsl.selectFrom(PHASES)
        .where(PHASES.TOURNAMENT_ID.eq(tournamentId))
        .orderBy(PHASES.POSITION.asc())
        .fetch()

    fun updatePhaseType(phaseId: UUID, type: PhaseType) {
        dsl.update(PHASES)
            .set(PHASES.TYPE, type)
            .where(PHASES.ID.eq(phaseId))
            .execute()
    }

    fun findOwnerPseudo(tournamentId: UUID): String? = dsl.select(USERS.PSEUDO)
        .from(TOURNAMENT_ORGANIZERS)
        .join(USERS).on(USERS.ID.eq(TOURNAMENT_ORGANIZERS.USER_ID))
        .where(TOURNAMENT_ORGANIZERS.TOURNAMENT_ID.eq(tournamentId))
        .orderBy(TOURNAMENT_ORGANIZERS.ROLE.asc()) // owner avant co_organizer
        .limit(1)
        .fetchOne(USERS.PSEUDO)

    /** Inscriptions actives avec le nom affichable (équipe ou joueur solo). */
    fun findActiveParticipants(tournamentId: UUID): List<ParticipantRow> =
        dsl.select(REGISTRATIONS.ID, REGISTRATIONS.SEED, TEAMS.NAME, USERS.PSEUDO)
            .from(REGISTRATIONS)
            .leftJoin(TEAMS).on(TEAMS.ID.eq(REGISTRATIONS.TEAM_ID))
            .leftJoin(USERS).on(USERS.ID.eq(REGISTRATIONS.USER_ID))
            .where(
                REGISTRATIONS.TOURNAMENT_ID.eq(tournamentId)
                    .and(
                        REGISTRATIONS.STATUS.`in`(
                            RegistrationStatus.confirmed,
                            RegistrationStatus.checked_in,
                        ),
                    ),
            )
            .orderBy(REGISTRATIONS.SEED.asc().nullsLast())
            .fetch { r ->
                ParticipantRow(
                    registrationId = r.get(REGISTRATIONS.ID)!!,
                    displayName = r.get(TEAMS.NAME) ?: r.get(USERS.PSEUDO) ?: "?",
                    seed = r.get(REGISTRATIONS.SEED),
                )
            }

    /** (matchs joués, matchs au total) pour toutes les phases du tournoi. */
    fun countMatches(tournamentId: UUID): Pair<Int, Int> {
        val byStatus = dsl.select(MATCHES.STATUS, DSL.count())
            .from(MATCHES)
            .join(PHASES).on(PHASES.ID.eq(MATCHES.PHASE_ID))
            .where(PHASES.TOURNAMENT_ID.eq(tournamentId))
            .groupBy(MATCHES.STATUS)
            .fetchMap(MATCHES.STATUS, DSL.count())
        val total = byStatus.values.sum()
        val played = byStatus[MatchStatus.finished] ?: 0
        return played to total
    }

    fun create(
        name: String,
        description: String?,
        games: List<Pair<String, Int>>, // (jeu, best-of)
        format: PhaseType,
        teamSize: Int,
        maxParticipants: Int?,
        visibility: TournamentVisibility,
        startAt: java.time.OffsetDateTime?,
        ownerKeycloakId: String,
        ownerPseudo: String,
        ownerEmail: String?,
    ): TournamentsRecord {
        // Rattache (ou crée) l'utilisateur plateforme lié au compte Keycloak — spec §6.1.3
        val userId = dsl.insertInto(USERS)
            .set(USERS.KEYCLOAK_ID, ownerKeycloakId)
            .set(USERS.PSEUDO, ownerPseudo)
            .set(USERS.EMAIL, ownerEmail)
            .onConflict(USERS.KEYCLOAK_ID)
            .doUpdate()
            .set(USERS.PSEUDO, ownerPseudo)
            .returning(USERS.ID)
            .fetchOne()!!
            .get(USERS.ID)!!

        val tournament = dsl.insertInto(TOURNAMENTS)
            .set(TOURNAMENTS.NAME, name)
            .set(TOURNAMENTS.DESCRIPTION, description)
            .set(TOURNAMENTS.STATUS, TournamentStatus.draft)
            .set(TOURNAMENTS.VISIBILITY, visibility)
            .set(TOURNAMENTS.TEAM_SIZE, teamSize)
            .set(TOURNAMENTS.MAX_PARTICIPANTS, maxParticipants)
            .set(TOURNAMENTS.START_AT, startAt)
            .returning()
            .fetchOne()!!

        dsl.insertInto(TOURNAMENT_ORGANIZERS)
            .set(TOURNAMENT_ORGANIZERS.TOURNAMENT_ID, tournament.id)
            .set(TOURNAMENT_ORGANIZERS.USER_ID, userId)
            .set(TOURNAMENT_ORGANIZERS.ROLE, OrganizerRole.owner)
            .execute()

        // Multi-jeu : une phase par jeu, chacune avec son BO (spec §6.2)
        games.forEachIndexed { i, (game, bestOf) ->
            dsl.insertInto(PHASES)
                .set(PHASES.TOURNAMENT_ID, tournament.id)
                .set(PHASES.GAME, game)
                .set(PHASES.POSITION, i + 1)
                .set(PHASES.TYPE, format)
                .set(PHASES.DEFAULT_BO, bestOf)
                .execute()
        }

        return tournament
    }
}
