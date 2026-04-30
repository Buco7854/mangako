package com.mangako.app.data.pending

import com.mangako.app.data.db.PendingDao
import com.mangako.app.data.db.PendingEntry
import com.mangako.app.data.db.StatusCount
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

private val OverrideJson = Json { ignoreUnknownKeys = true; encodeDefaults = false }
private val OverrideMapSerializer = MapSerializer(String.serializer(), String.serializer())
private val RemovalListSerializer = ListSerializer(String.serializer())

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
     *  Empty values mean "explicitly blank" — the variable feeds an empty
     *  string into the pipeline, distinct from "no override" (which leaves
     *  the detected value alone). Empty when no overrides are set. */
    val metadataOverrides: Map<String, String> = emptyMap(),
    /** Pipeline-variable names the user has marked for removal in the Inbox
     *  edit sheet. The worker drops these keys from the merged metadata
     *  AND strips the matching elements from the archive's ComicInfo.xml
     *  at processing time. Empty when no fields have been removed. */
    val metadataRemovals: Set<String> = emptySet(),
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
     * Result of an idempotent insert. [file] is the row in the DB
     * (either the one we just inserted or an existing match);
     * [wasInserted] is true only when this call physically created the
     * row, so callers can fire side effects (e.g. a "new file
     * detected" notification) exactly once per file rather than on
     * every periodic re-scan.
     */
    data class AddResult(val file: PendingFile, val wasInserted: Boolean)

    /**
     * Idempotent add: returns an [AddResult] whose `wasInserted` flag is
     * true only when this call actually created a row. A repeat call
     * for the same (uri, sizeBytes) pair returns the existing row with
     * `wasInserted = false` — the watcher uses this to skip
     * notifications for files it has already announced.
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
    ): AddResult {
        dao.findByUriAndSize(uri, sizeBytes)?.let {
            return AddResult(it.toDomain(), wasInserted = false)
        }
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
        // If a parallel scan beat us between the existence check and the
        // insert, insertIgnoring is a no-op and findByUriAndSize now
        // resolves to the other row — treat that as "not inserted by us"
        // so we don't double-notify.
        val persisted = dao.findById(id)
        return if (persisted != null) {
            AddResult(persisted.toDomain(), wasInserted = true)
        } else {
            val existing = dao.findByUriAndSize(uri, sizeBytes)?.toDomain() ?: row.toDomain()
            AddResult(existing, wasInserted = false)
        }
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
     * Persist the user's metadata edits for [id]:
     *   - [overrides]: pipeline-variable overrides. Blank values are now
     *     preserved (interpreted as "explicitly empty", so %title% becomes
     *     "" in the pipeline). Pass an empty map to clear all overrides.
     *   - [removals]: pipeline-variable names the user marked for removal.
     *     The worker strips these keys from the merged metadata AND
     *     removes the matching elements from the archive's ComicInfo.xml.
     *
     * %extra_tags% is conventionally stored with a leading space (it gets
     * interpolated immediately after "[%language%]" in the build template,
     * so the leading space provides the gap). User input from the edit
     * sheet typically arrives as "[Decensored]" without that space —
     * normalise here so users don't have to think about whitespace
     * conventions. Blank %extra_tags% stays blank (no leading space added).
     */
    suspend fun setMetadataEdits(
        id: String,
        overrides: Map<String, String>,
        removals: Set<String> = emptySet(),
    ) {
        val cleanedOverrides = overrides.mapValues { (k, v) ->
            if (k == "extra_tags" && v.isNotBlank() && !v.startsWith(" ")) " $v" else v
        }
        val overridesJson = if (cleanedOverrides.isEmpty()) null
        else OverrideJson.encodeToString(OverrideMapSerializer, cleanedOverrides)
        val removalsJson = if (removals.isEmpty()) null
        else OverrideJson.encodeToString(RemovalListSerializer, removals.toList())
        dao.setMetadataEditsJson(id, overridesJson, removalsJson)
    }

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
        metadataRemovals = metadataRemovalsJson.toListOrEmpty().toSet(),
        metadata = metadataJson.toMapOrEmpty(),
        thumbnailPath = thumbnailPath,
    )

    private fun String?.toMapOrEmpty(): Map<String, String> =
        this?.let { runCatching { OverrideJson.decodeFromString(OverrideMapSerializer, it) }.getOrDefault(emptyMap()) }
            ?: emptyMap()

    private fun String?.toListOrEmpty(): List<String> =
        this?.let { runCatching { OverrideJson.decodeFromString(RemovalListSerializer, it) }.getOrDefault(emptyList()) }
            ?: emptyList()
}
