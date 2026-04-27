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
    /**
     * Path to the thumbnail JPEG copied across from the Pending row at
     * processing time. The source .cbz is typically deleted after
     * upload (deleteOnSuccess defaults true), so we hold our own copy
     * here rather than relying on the live archive being readable.
     */
    val thumbnailPath: String? = null,
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
    /**
     * The actual filename Mangako produced for this file when the pipeline
     * ran. Captured at markDone() time so the Inbox/Processed view shows
     * what was really uploaded, not a re-run of today's pipeline against
     * the original name. NULL for rows that never reached DONE, or rows
     * created before this column existed.
     */
    val finalName: String? = null,
    /**
     * JSON-serialized map of pipeline-variable overrides keyed by
     * variable name (e.g. "title", "writer", "event", "extra_tags").
     * Merged on top of whatever ExtractXmlMetadata reads from the
     * archive, so a typo / missing tag / wrong event in detection can
     * be fixed once on the Inbox card without re-encoding the file.
     * NULL means "no overrides".
     */
    val metadataOverridesJson: String? = null,
    /**
     * JSON-serialized snapshot of ComicInfo.xml as the watcher read
     * it at detection time. Lets the Inbox card show a real
     * pipeline-simulated title and pre-fill the edit sheet without
     * having to re-open the .cbz on every render. NULL when the
     * watcher couldn't read the file (cloud-only URI, opened mid-write).
     */
    val metadataJson: String? = null,
    /**
     * Absolute path to a downsampled thumbnail JPEG the watcher saved
     * from the .cbz's first page image. NULL when no image could be
     * extracted (corrupt zip, image-less archive). Lives under the
     * app's internal cache dir; cleaned up when the row is deleted.
     */
    val thumbnailPath: String? = null,
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

    @Query("UPDATE pending SET status = :status, finalName = :finalName WHERE id = :id")
    suspend fun setStatusAndFinal(id: String, status: String, finalName: String)

    @Query("UPDATE pending SET metadataOverridesJson = :json WHERE id = :id")
    suspend fun setMetadataOverridesJson(id: String, json: String?)

    @Query("UPDATE pending SET metadataJson = :metadataJson, thumbnailPath = :thumbnailPath WHERE id = :id")
    suspend fun setDetectionInfo(id: String, metadataJson: String?, thumbnailPath: String?)

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

@Database(entities = [HistoryEntry::class, PendingEntry::class], version = 6, exportSchema = false)
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
