package org.example.backend.repository

import org.example.backend.database.enums.RegistrationStatus
import org.example.backend.database.tables.references.REGISTRATIONS
import org.example.backend.database.tables.references.TEAMS
import org.example.backend.database.tables.references.TOURNAMENTS
import org.example.backend.database.tables.references.USERS
import org.jooq.DSLContext
import org.springframework.stereotype.Repository
import java.time.OffsetDateTime
import java.util.UUID

data class RegistrationRow(
    val id: UUID,
    val name: String,
    val status: RegistrationStatus,
    val seed: Int?,
    val createdAt: OffsetDateTime,
    val tournamentId: UUID,
    val tournamentName: String,
)

@Repository
class RegistrationRepository(private val dsl: DSLContext) {

    fun listByTournament(tournamentId: UUID): List<RegistrationRow> = baseSelect()
        .where(REGISTRATIONS.TOURNAMENT_ID.eq(tournamentId))
        .orderBy(REGISTRATIONS.SEED.asc().nullsLast(), REGISTRATIONS.CREATED_AT.asc())
        .fetch(::toRow)

    /** Inscriptions à traiter par un organisateur (en attente ou liste d'attente). */
    fun listPending(): List<RegistrationRow> = baseSelect()
        .where(REGISTRATIONS.STATUS.`in`(RegistrationStatus.pending, RegistrationStatus.waitlist))
        .orderBy(REGISTRATIONS.CREATED_AT.asc())
        .fetch(::toRow)

    /**
     * Retrouve ou crée l'utilisateur plateforme lié au compte Keycloak.
     * Rattachement par email (spec §6.1.3) : un compte fantôme ou un compte
     * lié à une ancienne instance Keycloak est récupéré au lieu d'être dupliqué.
     * Le pseudo Keycloak ne sert qu'à la création : le pseudo plateforme reste modifiable.
     */
    fun upsertUserByKeycloak(keycloakId: String, pseudo: String, email: String?): UUID {
        // 1. Déjà lié à ce compte Keycloak
        dsl.select(USERS.ID)
            .from(USERS)
            .where(USERS.KEYCLOAK_ID.eq(keycloakId))
            .fetchOne(USERS.ID)
            ?.let { return it }

        // 2. Même email → rattachement
        if (email != null) {
            val attached = dsl.update(USERS)
                .set(USERS.KEYCLOAK_ID, keycloakId)
                .where(USERS.EMAIL.eq(email))
                .returning(USERS.ID)
                .fetchOne()
                ?.get(USERS.ID)
            if (attached != null) return attached
        }

        // 3. Nouveau compte
        return dsl.insertInto(USERS)
            .set(USERS.KEYCLOAK_ID, keycloakId)
            .set(USERS.PSEUDO, pseudo)
            .set(USERS.EMAIL, email)
            .returning(USERS.ID)
            .fetchOne()!!
            .get(USERS.ID)!!
    }

    /** Joueur "fantôme" (spec §6.1.3) : créé sans compte Keycloak, rattachable plus tard. */
    fun insertGhostUser(pseudo: String): UUID = dsl.insertInto(USERS)
        .set(USERS.PSEUDO, pseudo)
        .returning(USERS.ID)
        .fetchOne()!!
        .get(USERS.ID)!!

    /** Équipe "fantôme" : ajoutée par l'organisateur sans roster ni capitaine. */
    fun insertGhostTeam(name: String): UUID = dsl.insertInto(TEAMS)
        .set(TEAMS.NAME, name)
        .returning(TEAMS.ID)
        .fetchOne()!!
        .get(TEAMS.ID)!!

    fun existsForTeam(tournamentId: UUID, teamId: UUID): Boolean = dsl.fetchExists(
        REGISTRATIONS,
        REGISTRATIONS.TOURNAMENT_ID.eq(tournamentId).and(REGISTRATIONS.TEAM_ID.eq(teamId)),
    )

    fun insertTeam(tournamentId: UUID, teamId: UUID, status: RegistrationStatus): UUID = dsl.insertInto(REGISTRATIONS)
        .set(REGISTRATIONS.TOURNAMENT_ID, tournamentId)
        .set(REGISTRATIONS.TEAM_ID, teamId)
        .set(REGISTRATIONS.STATUS, status)
        .returning(REGISTRATIONS.ID)
        .fetchOne()!!
        .get(REGISTRATIONS.ID)!!

    fun existsForUser(tournamentId: UUID, userId: UUID): Boolean = dsl.fetchExists(
        REGISTRATIONS,
        REGISTRATIONS.TOURNAMENT_ID.eq(tournamentId).and(REGISTRATIONS.USER_ID.eq(userId)),
    )

    fun insertSolo(tournamentId: UUID, userId: UUID, status: RegistrationStatus): UUID = dsl.insertInto(REGISTRATIONS)
        .set(REGISTRATIONS.TOURNAMENT_ID, tournamentId)
        .set(REGISTRATIONS.USER_ID, userId)
        .set(REGISTRATIONS.STATUS, status)
        .returning(REGISTRATIONS.ID)
        .fetchOne()!!
        .get(REGISTRATIONS.ID)!!

    fun findStatus(registrationId: UUID): RegistrationStatus? = dsl.select(REGISTRATIONS.STATUS)
        .from(REGISTRATIONS)
        .where(REGISTRATIONS.ID.eq(registrationId))
        .fetchOne(REGISTRATIONS.STATUS)

    fun updateSeed(registrationId: UUID, seed: Int?): Boolean = dsl.update(REGISTRATIONS)
        .set(REGISTRATIONS.SEED, seed)
        .where(REGISTRATIONS.ID.eq(registrationId))
        .execute() == 1

    fun updateStatus(registrationId: UUID, status: RegistrationStatus): Boolean = dsl.update(REGISTRATIONS)
        .set(REGISTRATIONS.STATUS, status)
        .where(REGISTRATIONS.ID.eq(registrationId))
        .execute() == 1

    private fun baseSelect() = dsl.select(
        REGISTRATIONS.ID,
        REGISTRATIONS.STATUS,
        REGISTRATIONS.SEED,
        REGISTRATIONS.CREATED_AT,
        REGISTRATIONS.TOURNAMENT_ID,
        TOURNAMENTS.NAME,
        TEAMS.NAME,
        USERS.PSEUDO,
    )
        .from(REGISTRATIONS)
        .join(TOURNAMENTS).on(TOURNAMENTS.ID.eq(REGISTRATIONS.TOURNAMENT_ID))
        .leftJoin(TEAMS).on(TEAMS.ID.eq(REGISTRATIONS.TEAM_ID))
        .leftJoin(USERS).on(USERS.ID.eq(REGISTRATIONS.USER_ID))

    private fun toRow(r: org.jooq.Record): RegistrationRow = RegistrationRow(
        id = r.get(REGISTRATIONS.ID)!!,
        name = r.get(TEAMS.NAME) ?: r.get(USERS.PSEUDO) ?: "?",
        status = r.get(REGISTRATIONS.STATUS)!!,
        seed = r.get(REGISTRATIONS.SEED),
        createdAt = r.get(REGISTRATIONS.CREATED_AT)!!,
        tournamentId = r.get(REGISTRATIONS.TOURNAMENT_ID)!!,
        tournamentName = r.get(TOURNAMENTS.NAME)!!,
    )
}
