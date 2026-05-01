package com.mangako.app.data.history

import com.mangako.app.data.db.HistoryDao
import com.mangako.app.data.db.HistoryEntry
import com.mangako.app.data.db.HistoryJson
import com.mangako.app.domain.pipeline.AuditTrail
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

data class HistoryRecord(
    val id: String,
    val originalName: String,
    val finalName: String,
    val createdAt: Long,
    val success: Boolean,
    val httpCode: Int?,
    val message: String?,
    val trail: AuditTrail,
    /** Path to a cover thumbnail JPEG copied across from the Pending
     *  row at processing time. Null when the row was created before
     *  thumbnails existed, or when extraction failed. */
    val thumbnailPath: String? = null,
)

@Singleton
class HistoryRepository @Inject constructor(private val dao: HistoryDao) {

    /**
     * Observe the full on-disk history, newest first. No query-time
     * limit and no background prune: the table doubles as the
     * "already processed, don't re-detect" record, so trimming it
     * would silently re-surface old uploads. The user manages growth
     * manually via the Processed tab's per-row trash and Clear-all
     * actions; the screen uses a LazyColumn so large lists scroll fine.
     */
    fun observe(): Flow<List<HistoryRecord>> =
        dao.observeAll().map { rows -> rows.map { it.toRecord() } }

    suspend fun find(id: String): HistoryRecord? = dao.findById(id)?.toRecord()

    /**
     * Insert (or replace) the audit trail for one processing attempt.
     *
     * [stableId], when supplied, becomes the row's primary key so
     * subsequent calls for the *same* attempt — WorkManager retries of
     * a transient failure, or a user-triggered reprocess — overwrite
     * the previous row instead of piling up duplicates. The Inbox card
     * tied to that file therefore tracks live progress with one
     * History entry per logical attempt.
     *
     * When [stableId] is null (legacy callers) we mint a fresh UUID,
     * matching the old behaviour.
     */
    suspend fun record(
        trail: AuditTrail,
        thumbnailPath: String? = null,
        stableId: String? = null,
    ): String {
        val id = stableId ?: UUID.randomUUID().toString()
        dao.insert(
            HistoryEntry(
                id = id,
                originalName = trail.sourceFile,
                finalName = trail.finalName,
                createdAt = trail.finishedAt,
                success = trail.uploadStatus.success,
                httpCode = trail.uploadStatus.httpCode,
                message = trail.uploadStatus.message,
                auditJson = HistoryJson.encodeToString(trail),
                thumbnailPath = thumbnailPath,
            )
        )
        return id
    }

    suspend fun clear() = dao.clear()

    /** Delete a single history entry by id. Used by the per-row trash
     *  action on the History screen — for forgetting a specific run
     *  without nuking the whole list. */
    suspend fun delete(id: String) = dao.deleteById(id)

    private fun HistoryEntry.toRecord(): HistoryRecord = HistoryRecord(
        id = id,
        originalName = originalName,
        finalName = finalName,
        createdAt = createdAt,
        success = success,
        httpCode = httpCode,
        message = message,
        trail = HistoryJson.decodeFromString(AuditTrail.serializer(), auditJson),
        thumbnailPath = thumbnailPath,
    )
}
