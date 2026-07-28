package org.example.backend.repository

import org.example.backend.database.enums.JobStatus
import org.example.backend.database.enums.JobType
import org.example.backend.database.tables.references.JOBS
import org.jooq.DSLContext
import org.jooq.JSONB
import org.springframework.stereotype.Repository
import java.time.OffsetDateTime
import java.util.UUID

data class JobRow(
    val id: UUID,
    val type: JobType,
    val status: JobStatus,
    val payload: String,
    val error: String?,
    val fileUrl: String?,
    val createdAt: OffsetDateTime?,
    val finishedAt: OffsetDateTime?,
)

/**
 * Suivi des traitements asynchrones (spec §6.3, table `jobs`).
 *
 * La table ne sert plus de file d'attente depuis le passage à Pub/Sub : elle
 * garde la trace de l'état de chaque traitement, ce que la file ne sait pas
 * faire (historique consultable, message d'erreur, résultat).
 */
@Repository
class JobRepository(private val dsl: DSLContext) {

    fun create(type: JobType, payload: String, createdBy: UUID?): UUID = dsl.insertInto(JOBS)
        .set(JOBS.TYPE, type)
        .set(JOBS.STATUS, JobStatus.pending)
        .set(JOBS.PAYLOAD, JSONB.valueOf(payload))
        .set(JOBS.CREATED_BY, createdBy)
        .returning(JOBS.ID)
        .fetchOne()!!
        .get(JOBS.ID)!!

    fun markProcessing(id: UUID): Int = dsl.update(JOBS)
        .set(JOBS.STATUS, JobStatus.processing)
        .where(JOBS.ID.eq(id))
        .and(JOBS.STATUS.eq(JobStatus.pending))
        .execute()

    fun markDone(id: UUID): Int = dsl.update(JOBS)
        .set(JOBS.STATUS, JobStatus.done)
        .set(JOBS.ERROR, null as String?)
        .set(JOBS.FINISHED_AT, OffsetDateTime.now())
        .where(JOBS.ID.eq(id))
        .execute()

    fun markFailed(id: UUID, message: String): Int = dsl.update(JOBS)
        .set(JOBS.STATUS, JobStatus.failed)
        .set(JOBS.ERROR, message.take(2000))
        .set(JOBS.FINISHED_AT, OffsetDateTime.now())
        .where(JOBS.ID.eq(id))
        .execute()

    /** Enregistre le résultat renvoyé par le worker à côté de la demande. */
    fun saveResult(id: UUID, resultJson: String): Int = dsl.update(JOBS)
        .set(
            JOBS.PAYLOAD,
            org.jooq.impl.DSL.field(
                "jsonb_set({0}, '{result}', {1}::jsonb, true)",
                JSONB::class.java,
                JOBS.PAYLOAD,
                org.jooq.impl.DSL.value(resultJson),
            ),
        )
        .where(JOBS.ID.eq(id))
        .execute()

    fun findById(id: UUID): JobRow? = dsl.selectFrom(JOBS)
        .where(JOBS.ID.eq(id))
        .fetchOne()
        ?.let {
            JobRow(
                id = it.id!!,
                type = it.type,
                status = it.status ?: JobStatus.pending,
                payload = it.payload.data(),
                error = it.error,
                fileUrl = it.fileUrl,
                createdAt = it.createdAt,
                finishedAt = it.finishedAt,
            )
        }
}
