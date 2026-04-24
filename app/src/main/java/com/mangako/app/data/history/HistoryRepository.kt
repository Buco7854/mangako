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
)

@Singleton
class HistoryRepository @Inject constructor(private val dao: HistoryDao) {

    fun observe(limit: Int = DEFAULT_LIMIT): Flow<List<HistoryRecord>> =
        dao.observeRecent(limit).map { rows -> rows.map { it.toRecord() } }

    suspend fun find(id: String): HistoryRecord? = dao.findById(id)?.toRecord()

    suspend fun record(trail: AuditTrail): String {
        val id = UUID.randomUUID().toString()
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
            )
        )
        return id
    }

    suspend fun clear() = dao.clear()

    suspend fun pruneOlderThan(cutoff: Long): Int = dao.pruneOlderThan(cutoff)

    companion object {
        /** How many recent rows the History screen shows. Older rows are still on disk until pruned. */
        const val DEFAULT_LIMIT = 200
    }

    private fun HistoryEntry.toRecord(): HistoryRecord = HistoryRecord(
        id = id,
        originalName = originalName,
        finalName = finalName,
        createdAt = createdAt,
        success = success,
        httpCode = httpCode,
        message = message,
        trail = HistoryJson.decodeFromString(AuditTrail.serializer(), auditJson),
    )
}
