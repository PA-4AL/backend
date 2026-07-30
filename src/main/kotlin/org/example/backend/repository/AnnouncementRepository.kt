package org.example.backend.repository

import org.example.backend.database.tables.references.ANNOUNCEMENTS
import org.example.backend.database.tables.references.TOURNAMENTS
import org.example.backend.database.tables.references.USERS
import org.jooq.DSLContext
import org.springframework.stereotype.Repository
import java.time.OffsetDateTime
import java.util.UUID

/** Une annonce, avec le nom de son tournoi — la cloche affiche les deux. */
data class AnnonceRow(
    val id: UUID,
    val tournamentId: UUID,
    val tournamentName: String,
    val kind: String,
    val message: String,
    val createdAt: OffsetDateTime,
)

@Repository
class AnnouncementRepository(private val dsl: DSLContext) {

    fun insert(tournamentId: UUID, kind: String, message: String): UUID = dsl.insertInto(ANNOUNCEMENTS)
        .set(ANNOUNCEMENTS.TOURNAMENT_ID, tournamentId)
        .set(ANNOUNCEMENTS.KIND, kind)
        .set(ANNOUNCEMENTS.MESSAGE, message)
        .returning(ANNOUNCEMENTS.ID)
        .fetchOne()!!
        .get(ANNOUNCEMENTS.ID)!!

    /** Les dernières annonces d'un tournoi, plus récentes d'abord. */
    fun listByTournament(tournamentId: UUID, limite: Int = 50): List<AnnonceRow> = base()
        .where(ANNOUNCEMENTS.TOURNAMENT_ID.eq(tournamentId))
        .orderBy(ANNOUNCEMENTS.CREATED_AT.desc())
        .limit(limite)
        .fetch(::toRow)

    /**
     * Les annonces des tournois donnés.
     *
     * Une liste vide ne renvoie rien — et non tout : c'est le piège d'un filtre
     * construit à la légère, déjà rencontré sur les validations.
     */
    fun listByTournaments(tournamentIds: Collection<UUID>, limite: Int = 50): List<AnnonceRow> {
        if (tournamentIds.isEmpty()) return emptyList()
        return base()
            .where(ANNOUNCEMENTS.TOURNAMENT_ID.`in`(tournamentIds))
            .orderBy(ANNOUNCEMENTS.CREATED_AT.desc())
            .limit(limite)
            .fetch(::toRow)
    }

    /**
     * Nombre d'annonces arrivées depuis la dernière consultation.
     *
     * `depuis == null` (jamais consulté) compte tout : un nouvel utilisateur doit
     * voir qu'il y a quelque chose à lire, pas un zéro trompeur.
     */
    fun countDepuis(tournamentIds: Collection<UUID>, depuis: OffsetDateTime?): Int {
        if (tournamentIds.isEmpty()) return 0
        val condition = ANNOUNCEMENTS.TOURNAMENT_ID.`in`(tournamentIds)
            .let { if (depuis == null) it else it.and(ANNOUNCEMENTS.CREATED_AT.gt(depuis)) }
        return dsl.fetchCount(ANNOUNCEMENTS, condition)
    }

    fun findSeenAt(userId: UUID): OffsetDateTime? = dsl.select(USERS.ANNOUNCEMENTS_SEEN_AT)
        .from(USERS)
        .where(USERS.ID.eq(userId))
        .fetchOne(USERS.ANNOUNCEMENTS_SEEN_AT)

    fun markSeen(userId: UUID, quand: OffsetDateTime) {
        dsl.update(USERS)
            .set(USERS.ANNOUNCEMENTS_SEEN_AT, quand)
            .where(USERS.ID.eq(userId))
            .execute()
    }

    private fun base() = dsl.select(
        ANNOUNCEMENTS.ID,
        ANNOUNCEMENTS.TOURNAMENT_ID,
        ANNOUNCEMENTS.KIND,
        ANNOUNCEMENTS.MESSAGE,
        ANNOUNCEMENTS.CREATED_AT,
        TOURNAMENTS.NAME,
    )
        .from(ANNOUNCEMENTS)
        .join(TOURNAMENTS).on(TOURNAMENTS.ID.eq(ANNOUNCEMENTS.TOURNAMENT_ID))

    private fun toRow(r: org.jooq.Record) = AnnonceRow(
        id = r.get(ANNOUNCEMENTS.ID)!!,
        tournamentId = r.get(ANNOUNCEMENTS.TOURNAMENT_ID)!!,
        tournamentName = r.get(TOURNAMENTS.NAME)!!,
        kind = r.get(ANNOUNCEMENTS.KIND)!!,
        message = r.get(ANNOUNCEMENTS.MESSAGE)!!,
        createdAt = r.get(ANNOUNCEMENTS.CREATED_AT)!!,
    )
}
