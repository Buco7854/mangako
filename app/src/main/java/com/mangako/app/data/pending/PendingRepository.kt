package com.mangako.app.data.pending

import com.mangako.app.data.db.PendingDao
import com.mangako.app.data.db.PendingEntry
import com.mangako.app.data.db.StatusCount
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

private val OverrideJson = Json { ignoreUnknownKeys = true; encodeDefaults = false }
private val OverrideMapSerializer = MapSerializer(String.serializer(), String.serializer())

@Suppress("unused") // surfaced via observeCounts; explicit re-export keeps the data class reachable.
private typealias PendingStatusCount = StatusCount

enum class PendingStatus { PENDING, APPROVED, REJECTED, DONE }

data class PendingFile(
    val id: String,
    val uri: String,
    val name: String,
    val sizeBytes: Long,
    val detectedAt: Long,
    val folderUri: String,
    val status: PendingStatus,
    /** The renamed filename actually written to LANraragi for this row. Null
     *  for not-yet-processed rows. Lets the Inbox/Processed view show the
     *  truth without a fuzzy join against history-by-original-name. */
    val finalName: String? = null,
    /** User-set pipeline-variable overrides (title, writer, event,
     *  extra_tags, etc.). Merged on top of ExtractXmlMetadata's read so
     *  the user can fix bad detection without re-encoding the file.
     *  Empty when no overrides are set. */
    val metadataOverrides: Map<String, String> = emptyMap(),
    /** ComicInfo.xml as the watcher read it at detection time. Empty if
     *  the watcher couldn't read the file or it carried no ComicInfo. */
    val metadata: Map<String, String> = emptyMap(),
    /** Absolute path to a small JPEG cover thumbnail saved at detection
     *  time. Null if no image could be extracted. */
    val thumbnailPath: String? = null,
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

    /** Observe rows that match any status in [statuses] — backs the Inbox
     *  filter chip switch (Pending / Processed / Ignored). */
    fun observeByStatuses(statuses: Set<PendingStatus>): Flow<List<PendingFile>> =
        dao.observeByStatuses(statuses.map { it.name }).map { rows -> rows.map { it.toDomain() } }

    /** Reactive [PendingStatus] → count map. Empty buckets are zero-filled. */
    fun observeCounts(): Flow<Map<PendingStatus, Int>> = dao.countByStatus().map { rows ->
        val seed = PendingStatus.values().associateWith { 0 }
        rows.fold(seed) { acc, row ->
            val status = runCatching { PendingStatus.valueOf(row.status) }.getOrNull()
                ?: return@fold acc
            acc + (status to row.count)
        }
    }

    fun countPending(): Flow<Int> = dao.countPending()

    suspend fun find(id: String): PendingFile? = dao.findById(id)?.toDomain()

    /**
     * Idempotent add: returns an existing row if we've already seen the same
     * (uri, sizeBytes) pair, otherwise inserts & returns the fresh row.
     *
     * [metadata] and [thumbnailPath] come from the watcher's
     * detection-time peek inside the .cbz. They're optional — passing
     * empty/null is fine and the row will still appear in the Inbox,
     * just without a thumbnail or simulated title.
     */
    suspend fun addIfAbsent(
        uri: String,
        name: String,
        sizeBytes: Long,
        folderUri: String,
        detectedAt: Long = System.currentTimeMillis(),
        metadata: Map<String, String> = emptyMap(),
        thumbnailPath: String? = null,
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
            metadataJson = metadata.takeIf { it.isNotEmpty() }?.let {
                OverrideJson.encodeToString(OverrideMapSerializer, it)
            },
            thumbnailPath = thumbnailPath,
        )
        dao.insertIgnoring(row)
        return (dao.findById(id) ?: row).toDomain()
    }

    /** Updates the detection-time metadata + thumbnail for an
     *  already-known row. Lets the watcher fill in detection details
     *  on a row that was inserted before the extraction completed
     *  (e.g. the file was still being copied into the folder). */
    suspend fun updateDetectionInfo(id: String, metadata: Map<String, String>, thumbnailPath: String?) {
        val json = metadata.takeIf { it.isNotEmpty() }?.let {
            OverrideJson.encodeToString(OverrideMapSerializer, it)
        }
        dao.setDetectionInfo(id, json, thumbnailPath)
    }

    suspend fun approve(id: String) = dao.setStatus(id, PendingStatus.APPROVED.name)
    suspend fun reject(id: String) = dao.setStatus(id, PendingStatus.REJECTED.name)

    /** Mark a row DONE and remember exactly what filename was uploaded so
     *  the Inbox can faithfully show original → final without joining by
     *  fuzzy name matches against the history table. */
    suspend fun markDone(id: String, finalName: String) =
        dao.setStatusAndFinal(id, PendingStatus.DONE.name, finalName)

    /**
     * Persist user-set pipeline-variable overrides for [id]. Empty
     * map clears all overrides. Empty values inside the map are
     * dropped before persisting — a blank override means "don't
     * override" rather than "set to empty".
     *
     * %extra_tags% is conventionally stored with a leading space (it
     * gets interpolated immediately after "[%language%]" in the build
     * template, so the leading space provides the gap). User input
     * from the edit sheet typically arrives as "[Decensored]" without
     * that space — normalise here so users don't have to think about
     * whitespace conventions.
     */
    suspend fun setMetadataOverrides(id: String, overrides: Map<String, String>) {
        val cleaned = overrides
            .filterValues { it.isNotBlank() }
            .mapValues { (k, v) ->
                if (k == "extra_tags" && !v.startsWith(" ")) " $v" else v
            }
        val json = if (cleaned.isEmpty()) null
        else OverrideJson.encodeToString(OverrideMapSerializer, cleaned)
        dao.setMetadataOverridesJson(id, json)
    }

    /**
     * Resets a previously-processed or ignored row back to PENDING so the
     * user can run it through the pipeline again. The original file URI
     * is kept; if the underlying SAF document no longer resolves (file was
     * deleted or moved), the next process attempt will fail loudly via the
     * usual ProcessCbzWorker error path rather than silently no-op.
     */
    suspend fun reprocess(id: String) = dao.setStatus(id, PendingStatus.PENDING.name)
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
        finalName = finalName,
        metadataOverrides = metadataOverridesJson.toMapOrEmpty(),
        metadata = metadataJson.toMapOrEmpty(),
        thumbnailPath = thumbnailPath,
    )

    private fun String?.toMapOrEmpty(): Map<String, String> =
        this?.let { runCatching { OverrideJson.decodeFromString(OverrideMapSerializer, it) }.getOrDefault(emptyMap()) }
            ?: emptyMap()
}
