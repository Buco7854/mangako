package com.mangako.app.domain.cbz

import android.content.Context
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Single source of truth for cover thumbnails.
 *
 * Old design: the watcher extracted thumbnails at detection time, the
 * worker re-extracted at upload time, and two cache directories
 * (`thumbnails/` for pending, `thumbnails_history/` for processed) leaked
 * storage when rows were deleted. The Inbox UI then had to second-guess
 * which path was "freshest" and silently regenerated nothing when the
 * cache evicted.
 *
 * New design: two-tier cache, one deterministic key (sha1(uri + size)),
 * one async API.
 *  - **L1**: `cacheDir/thumbnails/`  — fast, evictable. The default tier.
 *  - **L2**: `filesDir/thumbnails/` — persistent, won't be evicted by the
 *    OS. Populated by [persist] when an upload succeeds and the worker
 *    is about to delete the source .cbz; the pending L1 cache copy is
 *    promoted here so the Processed Inbox card still has a cover after
 *    the source bytes are gone.
 *
 * UI calls [thumbnailFor] every time a card composes; the service
 * resolves L1 → L2 → on-demand-extract from the URI. Eviction is
 * automatic on next request — there is no stale-cache failure mode.
 *
 * Thread-safe: a per-key mutex prevents the same .cbz being decoded by
 * two coroutines at once during fast scrolls.
 */
@Singleton
class ThumbnailService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val cbzProcessor: CbzProcessor,
) {

    private val l1Dir: File by lazy {
        File(context.cacheDir, CACHE_DIR_NAME).apply { if (!exists()) mkdirs() }
    }
    private val l2Dir: File by lazy {
        File(context.filesDir, CACHE_DIR_NAME).apply { if (!exists()) mkdirs() }
    }

    private val locks = HashMap<String, Mutex>()
    private val locksGuard = Object()

    /**
     * Return a cached thumbnail [File] for the .cbz at [uri], generating
     * one if both cache tiers are empty. Returns null only when the
     * archive isn't readable / contains no decodable cover.
     *
     * [sizeBytes] participates in the cache key so an in-place rewrite
     * of the .cbz reliably invalidates the old thumbnail.
     */
    suspend fun thumbnailFor(uri: Uri, sizeBytes: Long): File? = withContext(Dispatchers.IO) {
        val key = cacheKey(uri.toString(), sizeBytes)
        // L1 hit — most common path on a cold-but-recent Inbox open.
        l1File(key).existing()?.let { return@withContext it }
        mutexFor(key).withLock {
            l1File(key).existing()?.let { return@withLock it }
            // L2 hit — the source got deleted-on-success but we kept a
            // persistent copy. Promote it back to L1 for fast subsequent
            // reads.
            l2File(key).existing()?.let { source ->
                val l1 = l1File(key)
                runCatching {
                    source.inputStream().use { input ->
                        l1.outputStream().use { output -> input.copyTo(output) }
                    }
                }
                return@withLock l1.existing() ?: source
            }
            // Cold cache — extract from the source URI.
            val info = cbzProcessor.extractDetectionFromUri(context, uri)
            val bytes = info.firstImage ?: return@withLock null
            val l1 = l1File(key)
            if (cbzProcessor.saveThumbnail(bytes, l1)) l1 else null
        }
    }

    /** Same as [thumbnailFor] but takes a local [File] — used by the
     *  worker after it's already copied the .cbz to cache and wants to
     *  guarantee a thumbnail before the source is deleted. */
    suspend fun thumbnailFromLocal(localCbz: File, originUri: String, sizeBytes: Long): File? =
        withContext(Dispatchers.IO) {
            val key = cacheKey(originUri, sizeBytes)
            l1File(key).existing()?.let { return@withContext it }
            mutexFor(key).withLock {
                l1File(key).existing()?.let { return@withLock it }
                val info = runCatching {
                    localCbz.inputStream().use { cbzProcessor.extractDetectionInfo(it) }
                }.getOrNull()
                val bytes = info?.firstImage ?: return@withLock null
                val l1 = l1File(key)
                if (cbzProcessor.saveThumbnail(bytes, l1)) l1 else null
            }
        }

    /** Synchronous cache lookup (no extraction). Used by the UI's first
     *  composition pass to avoid a momentary placeholder when a hit is
     *  on disk; the async [thumbnailFor] still runs to handle misses. */
    fun cachedThumbnail(uri: String, sizeBytes: Long): File? {
        val key = cacheKey(uri, sizeBytes)
        return l1File(key).existing() ?: l2File(key).existing()
    }

    /**
     * Promote the L1 entry for [uri] to L2 so it survives cache eviction.
     * Called by [com.mangako.app.work.ProcessCbzWorker] after a successful
     * upload — once the source .cbz is deleted, on-demand extraction can
     * no longer regenerate the cover, so we make a persistent copy.
     */
    suspend fun persist(uri: String, sizeBytes: Long): File? = withContext(Dispatchers.IO) {
        val key = cacheKey(uri, sizeBytes)
        val l1 = l1File(key).existing() ?: return@withContext null
        val l2 = l2File(key)
        runCatching {
            l1.inputStream().use { input ->
                l2.outputStream().use { output -> input.copyTo(output) }
            }
        }.getOrNull()
        l2.existing()
    }

    /** Drop the cache entry for [uri] across both tiers. Used after an
     *  in-place archive rewrite where caller wants the next thumbnail
     *  request to re-decode against the updated bytes. */
    fun invalidate(uri: String, sizeBytes: Long) {
        val key = cacheKey(uri, sizeBytes)
        l1File(key).delete()
        l2File(key).delete()
    }

    private fun l1File(key: String) = File(l1Dir, "$key.jpg")
    private fun l2File(key: String) = File(l2Dir, "$key.jpg")

    private fun File.existing(): File? = takeIf { exists() && length() > 0 }

    private fun mutexFor(key: String): Mutex = synchronized(locksGuard) {
        locks.getOrPut(key) { Mutex() }
    }

    private fun cacheKey(uri: String, sizeBytes: Long): String {
        val md = MessageDigest.getInstance("SHA-1")
        md.update(uri.toByteArray(Charsets.UTF_8))
        md.update("|$sizeBytes".toByteArray(Charsets.UTF_8))
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val CACHE_DIR_NAME = "thumbnails"
    }
}
