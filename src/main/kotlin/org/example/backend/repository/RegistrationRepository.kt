package org.example.backend.repository

import org.example.backend.database.enums.RegistrationStatus
import org.example.backend.database.enums.TeamMemberRole
import org.example.backend.database.tables.references.REGISTRATIONS
import org.example.backend.database.tables.references.TEAMS
import org.example.backend.database.tables.references.TEAM_MEMBERS
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
    /** Classement final (1 = vainqueur) ; `null` tant que le tournoi n'est pas terminé. */
    val finalRank: Int? = null,
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

    /**
     * Équipe existante portant ce nom, s'il y en a une.
     *
     * Sert à rendre l'import **idempotent** : Pub/Sub garantit au moins une
     * livraison, un même fichier peut donc être matérialisé deux fois. Sans cette
     * recherche, chaque redélivrance créerait des équipes en double.
     */
    fun findTeamByName(name: String): UUID? = dsl.select(TEAMS.ID)
        .from(TEAMS)
        .where(TEAMS.NAME.eq(name))
        .limit(1)
        .fetchOne(TEAMS.ID)

    /**
     * Joueur **fantôme** existant portant ce pseudo — même raison d'être
     * qu'au-dessus, mais restreint aux comptes sans identité Keycloak.
     *
     * La restriction est essentielle : sans elle, importer un fichier contenant
     * le pseudo d'un joueur réellement inscrit rattachait **son compte** à
     * l'équipe importée, sans son consentement. Un import ne doit pouvoir créer
     * ou compléter que des identités qu'il a lui-même fabriquées.
     */
    fun findGhostUserByPseudo(pseudo: String): UUID? = dsl.select(USERS.ID)
        .from(USERS)
        .where(USERS.PSEUDO.eq(pseudo).and(USERS.KEYCLOAK_ID.isNull))
        .limit(1)
        .fetchOne(USERS.ID)

    /**
     * Rattache un joueur à une équipe avec son rang en jeu.
     *
     * `onConflictDoUpdate` sur la clé (équipe, joueur) : un import rejoué met le
     * rang à jour au lieu d'échouer sur la clé primaire.
     */
    fun attacherMembre(teamId: UUID, userId: UUID, role: TeamMemberRole, rank: String?) {
        dsl.insertInto(TEAM_MEMBERS)
            .set(TEAM_MEMBERS.TEAM_ID, teamId)
            .set(TEAM_MEMBERS.USER_ID, userId)
            .set(TEAM_MEMBERS.ROLE, role)
            .set(TEAM_MEMBERS.RANK, rank)
            .onConflict(TEAM_MEMBERS.TEAM_ID, TEAM_MEMBERS.USER_ID)
            .doUpdate()
            .set(TEAM_MEMBERS.RANK, rank)
            .execute()
    }

    /** Classement final d'une inscription (1 = vainqueur). */
    fun updateFinalRank(registrationId: UUID, finalRank: Int?): Boolean = dsl.update(REGISTRATIONS)
        .set(REGISTRATIONS.FINAL_RANK, finalRank)
        .where(REGISTRATIONS.ID.eq(registrationId))
        .execute() == 1

    fun findFinalRanks(tournamentId: UUID): Map<UUID, Int> = dsl.select(REGISTRATIONS.ID, REGISTRATIONS.FINAL_RANK)
        .from(REGISTRATIONS)
        .where(REGISTRATIONS.TOURNAMENT_ID.eq(tournamentId).and(REGISTRATIONS.FINAL_RANK.isNotNull))
        .fetch()
        .associate { it.get(REGISTRATIONS.ID)!! to it.get(REGISTRATIONS.FINAL_RANK)!! }

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

    /**
     * Tournoi et statut d'une inscription — nécessaires pour autoriser une action
     * (organisateur de CE tournoi) et pour décider d'un repêchage.
     */
    fun findTournamentAndStatus(registrationId: UUID): Pair<UUID, RegistrationStatus>? =
        dsl.select(REGISTRATIONS.TOURNAMENT_ID, REGISTRATIONS.STATUS)
            .from(REGISTRATIONS)
            .where(REGISTRATIONS.ID.eq(registrationId))
            .fetchOne { r -> r.get(REGISTRATIONS.TOURNAMENT_ID)!! to r.get(REGISTRATIONS.STATUS)!! }

    /**
     * Plus ancienne inscription en liste d'attente, s'il en existe une.
     *
     * La liste d'attente ne fonctionnait que dans un sens : une inscription
     * basculait en `waitlist` quand le tournoi était complet, mais personne n'en
     * sortait jamais quand une place se libérait. L'ordre d'arrivée est le seul
     * critère défendable — premier arrivé, premier repêché.
     */
    fun findPremierEnAttente(tournamentId: UUID): UUID? = dsl.select(REGISTRATIONS.ID)
        .from(REGISTRATIONS)
        .where(
            REGISTRATIONS.TOURNAMENT_ID.eq(tournamentId)
                .and(REGISTRATIONS.STATUS.eq(RegistrationStatus.waitlist)),
        )
        .orderBy(REGISTRATIONS.CREATED_AT.asc())
        .limit(1)
        .fetchOne(REGISTRATIONS.ID)

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
        REGISTRATIONS.FINAL_RANK,
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
        finalRank = r.get(REGISTRATIONS.FINAL_RANK),
    )
}
