package com.mangako.app.data.db

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import com.mangako.app.domain.pipeline.AuditTrail
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * History: one row per processed file. The full audit trail is stored as JSON
 * so we don't have to normalize every rule type into columns.
 */
@Entity(tableName = "history")
data class HistoryEntry(
    @PrimaryKey val id: String,
    val originalName: String,
    val finalName: String,
    val createdAt: Long,
    val success: Boolean,
    val httpCode: Int?,
    val message: String?,
    /** Serialized [AuditTrail]. */
    val auditJson: String,
)

/**
 * Pending queue: files detected by the watcher that need user approval before
 * running the pipeline. [uri] is a SAF `content://` URI; a compound unique
 * index on (uri, sizeBytes) dedupes the same file across scan ticks.
 */
@Entity(tableName = "pending")
data class PendingEntry(
    @PrimaryKey val id: String,
    val uri: String,
    val name: String,
    val sizeBytes: Long,
    val detectedAt: Long,
    val folderUri: String,
    /** PENDING, APPROVED, REJECTED, DONE */
    val status: String,
)

/** Row of a `GROUP BY status` query — used for the Inbox filter chip counts. */
data class StatusCount(val status: String, val count: Int)

@Dao
interface HistoryDao {
    @Query("SELECT * FROM history ORDER BY createdAt DESC LIMIT :limit")
    fun observeRecent(limit: Int = 200): Flow<List<HistoryEntry>>

    @Query("SELECT * FROM history WHERE id = :id")
    suspend fun findById(id: String): HistoryEntry?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: HistoryEntry)

    @Query("DELETE FROM history WHERE createdAt < :cutoff")
    suspend fun pruneOlderThan(cutoff: Long): Int

    @Query("DELETE FROM history")
    suspend fun clear()
}

@Dao
interface PendingDao {
    @Query("SELECT * FROM pending WHERE status = :status ORDER BY detectedAt DESC")
    fun observeByStatus(status: String = "PENDING"): Flow<List<PendingEntry>>

    @Query("SELECT * FROM pending WHERE status IN (:statuses) ORDER BY detectedAt DESC")
    fun observeByStatuses(statuses: List<String>): Flow<List<PendingEntry>>

    @Query("SELECT * FROM pending ORDER BY detectedAt DESC LIMIT :limit")
    fun observeAll(limit: Int = 200): Flow<List<PendingEntry>>

    @Query("SELECT * FROM pending WHERE id = :id")
    suspend fun findById(id: String): PendingEntry?

    @Query("SELECT * FROM pending WHERE uri = :uri AND sizeBytes = :size LIMIT 1")
    suspend fun findByUriAndSize(uri: String, size: Long): PendingEntry?

    @Query("SELECT COUNT(*) FROM pending WHERE status = 'PENDING'")
    fun countPending(): Flow<Int>

    @Query("SELECT status, COUNT(*) AS count FROM pending GROUP BY status")
    fun countByStatus(): Flow<List<StatusCount>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnoring(entry: PendingEntry): Long

    @Query("UPDATE pending SET status = :status WHERE id = :id")
    suspend fun setStatus(id: String, status: String)

    @Query("DELETE FROM pending WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM pending WHERE status IN ('REJECTED','DONE') AND detectedAt < :cutoff")
    suspend fun pruneOld(cutoff: Long)
}

class Converters {
    @TypeConverter
    fun auditFromJson(json: String): AuditTrail =
        HistoryJson.decodeFromString(AuditTrail.serializer(), json)

    @TypeConverter
    fun auditToJson(trail: AuditTrail): String =
        HistoryJson.encodeToString(trail)
}

@Database(entities = [HistoryEntry::class, PendingEntry::class], version = 1, exportSchema = false)
@TypeConverters(Converters::class)
abstract class HistoryDatabase : RoomDatabase() {
    abstract fun historyDao(): HistoryDao
    abstract fun pendingDao(): PendingDao
}

internal val HistoryJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
}
