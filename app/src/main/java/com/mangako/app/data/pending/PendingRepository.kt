package com.mangako.app.data.pending

import com.mangako.app.data.db.PendingDao
import com.mangako.app.data.db.PendingEntry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

enum class PendingStatus { PENDING, APPROVED, REJECTED, DONE }

data class PendingFile(
    val id: String,
    val uri: String,
    val name: String,
    val sizeBytes: Long,
    val detectedAt: Long,
    val folderUri: String,
    val status: PendingStatus,
)

/**
 * Storage for files the watcher has noticed but hasn't processed yet. The
 * status machine is:
 *
 *   PENDING → APPROVED  (user tapped Process)
 *   PENDING → REJECTED  (user tapped Ignore; row becomes invisible)
 *   APPROVED → DONE     (upload completed, row kept for audit; pruned eventually)
 */
@Singleton
class PendingRepository @Inject constructor(private val dao: PendingDao) {

    fun observePending(): Flow<List<PendingFile>> =
        dao.observeByStatus(PendingStatus.PENDING.name).map { rows -> rows.map { it.toDomain() } }

    fun countPending(): Flow<Int> = dao.countPending()

    suspend fun find(id: String): PendingFile? = dao.findById(id)?.toDomain()

    /**
     * Idempotent add: returns an existing row if we've already seen the same
     * (uri, sizeBytes) pair, otherwise inserts & returns the fresh row.
     */
    suspend fun addIfAbsent(
        uri: String,
        name: String,
        sizeBytes: Long,
        folderUri: String,
        detectedAt: Long = System.currentTimeMillis(),
    ): PendingFile {
        dao.findByUriAndSize(uri, sizeBytes)?.let { return it.toDomain() }
        val id = UUID.randomUUID().toString()
        val row = PendingEntry(
            id = id,
            uri = uri,
            name = name,
            sizeBytes = sizeBytes,
            detectedAt = detectedAt,
            folderUri = folderUri,
            status = PendingStatus.PENDING.name,
        )
        dao.insertIgnoring(row)
        return (dao.findById(id) ?: row).toDomain()
    }

    suspend fun approve(id: String) = dao.setStatus(id, PendingStatus.APPROVED.name)
    suspend fun reject(id: String) = dao.setStatus(id, PendingStatus.REJECTED.name)
    suspend fun markDone(id: String) = dao.setStatus(id, PendingStatus.DONE.name)
    suspend fun delete(id: String) = dao.delete(id)

    suspend fun pruneOlderThan(cutoff: Long) = dao.pruneOld(cutoff)

    private fun PendingEntry.toDomain() = PendingFile(
        id = id,
        uri = uri,
        name = name,
        sizeBytes = sizeBytes,
        detectedAt = detectedAt,
        folderUri = folderUri,
        status = runCatching { PendingStatus.valueOf(status) }.getOrDefault(PendingStatus.PENDING),
    )
}
