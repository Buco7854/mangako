package com.mangako.app.work

import android.content.Context
import androidx.documentfile.provider.DocumentFile
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.WorkerParameters
import com.mangako.app.data.history.HistoryRepository
import com.mangako.app.data.lanraragi.LanraragiClient
import com.mangako.app.data.lanraragi.LanraragiException
import com.mangako.app.data.pending.PendingRepository
import com.mangako.app.data.pipeline.PipelineRepository
import com.mangako.app.data.settings.SettingsRepository
import com.mangako.app.domain.cbz.CbzProcessor
import com.mangako.app.domain.pipeline.AuditTrail
import com.mangako.app.domain.pipeline.PipelineExecutor
import com.mangako.app.domain.pipeline.UploadStatus
import com.mangako.app.work.notify.Notifications
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * Terminal stage of the pipeline. For a given SAF URI pointing at a .cbz:
 *   1. debounce-wait for the file to stabilize
 *   2. copy it to app cache (so we can read it as a local zip)
 *   3. extract ComicInfo.xml variables
 *   4. run the user's pipeline to compute the final filename
 *   5. POST the archive to LANraragi (body is streamed, not buffered)
 *   6. on HTTP 200, delete the source (if configured) and mark the pending row DONE
 *   7. persist the audit trail
 *
 * Retry policy: network-shaped errors return [Result.retry] up to
 * [MAX_ATTEMPTS] tries; parse/permission/4xx failures return [Result.failure]
 * immediately. This stops WorkManager's default 20-attempt exponential backoff
 * from hammering forever on a permanently-bad input.
 */
@HiltWorker
class ProcessCbzWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val settingsRepo: SettingsRepository,
    private val pipelineRepo: PipelineRepository,
    private val historyRepo: HistoryRepository,
    private val pendingRepo: PendingRepository,
    private val cbzProcessor: CbzProcessor,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val uriStr = inputData.getString(KEY_URI) ?: return@withContext Result.failure()
        val originalName = inputData.getString(KEY_NAME) ?: "file.cbz"
        val pendingId = inputData.getString(KEY_PENDING_ID)
        val settings = settingsRepo.flow.first()
        val config = pipelineRepo.flow.first()

        val docFile = DocumentFile.fromSingleUri(applicationContext, android.net.Uri.parse(uriStr))
            ?: return@withContext Result.failure() // malformed URI — never going to work
        if (!docFile.exists()) return@withContext Result.failure() // source gone — permanent

        // 1. Debounce
        if (!awaitStable(docFile, settings.debounceSeconds.coerceAtLeast(1) * 1000L)) {
            return@withContext retryOrFail("File never stabilized")
        }

        // 2. Copy to cache so we can stream from a local File.
        val localCopy = File(applicationContext.cacheDir, "incoming_${System.currentTimeMillis()}.cbz")
        try {
            applicationContext.contentResolver.openInputStream(docFile.uri)?.use { input ->
                localCopy.outputStream().use { output -> input.copyTo(output) }
            } ?: return@withContext retryOrFail("Could not open input stream")
        } catch (e: IOException) {
            localCopy.delete()
            return@withContext retryOrFail("Copy failed: ${e.message}")
        }

        val startedAt = System.currentTimeMillis()

        try {
            // 3. Metadata → pipeline variable context.
            val metadata = cbzProcessor.extractMetadata(localCopy)

            // 4. Run pipeline.
            val runOut = PipelineExecutor().run(
                config,
                PipelineExecutor.Input(originalFilename = originalName, metadata = metadata),
            )
            val finalName = ensureCbzSuffix(runOut.finalFilename)

            // 5. Upload.
            val client = LanraragiClient(
                baseUrl = settings.lanraragiUrl,
                apiKey = settings.lanraragiApiKey,
            )
            val uploadResult = client.uploadArchive(localCopy, finalName)
            val uploadStatus = uploadResult.fold(
                onSuccess = { UploadStatus(success = true, httpCode = 200, message = it.message) },
                onFailure = { t ->
                    UploadStatus(
                        success = false,
                        httpCode = (t as? LanraragiException)?.code,
                        message = t.message,
                    )
                },
            )

            // 6. Delete source + close pending row on success.
            val deleted = if (uploadStatus.success && settings.deleteOnSuccess) {
                runCatching { docFile.delete() }.getOrDefault(false)
            } else false
            if (uploadStatus.success && pendingId != null) {
                // Persist the actually-uploaded filename onto the Pending row
                // so the Inbox / Processed view renders the same string the
                // user will see in LANraragi (not a fresh re-run of today's
                // pipeline against the original name).
                pendingRepo.markDone(pendingId, finalName)
                Notifications.cancelDetected(applicationContext, pendingId)
            }

            // 7. Persist audit trail.
            historyRepo.record(
                AuditTrail(
                    sourceFile = originalName,
                    startedAt = startedAt,
                    finishedAt = System.currentTimeMillis(),
                    steps = runOut.steps,
                    finalName = finalName,
                    uploadStatus = uploadStatus.copy(deletedSource = deleted),
                ),
            )

            if (uploadStatus.success) return@withContext Result.success()
            // Upload failure: 5xx / IOException → retry; 4xx / other → permanent fail.
            val shouldRetry = uploadStatus.httpCode?.let { it in 500..599 } ?: true
            return@withContext if (shouldRetry) retryOrFail("Upload failed: ${uploadStatus.message}") else Result.failure()
        } finally {
            localCopy.delete()
        }
    }

    private fun retryOrFail(reason: String): Result =
        if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.failure()

    private suspend fun awaitStable(doc: DocumentFile, windowMs: Long): Boolean {
        var lastSize = doc.length()
        val deadline = System.currentTimeMillis() + (windowMs * 4)
        var stableSince = 0L
        while (System.currentTimeMillis() < deadline) {
            delay(500)
            val now = doc.length()
            if (now == lastSize && now > 0) {
                if (stableSince == 0L) stableSince = System.currentTimeMillis()
                if (System.currentTimeMillis() - stableSince >= windowMs) return true
            } else {
                stableSince = 0L
                lastSize = now
            }
        }
        return false
    }

    private fun ensureCbzSuffix(name: String): String =
        if (name.endsWith(".cbz", ignoreCase = true)) name else "$name.cbz"

    companion object {
        const val KEY_URI = "uri"
        const val KEY_NAME = "name"
        const val KEY_PENDING_ID = "pending_id"

        /** Max number of WorkManager attempts before we stop retrying transient failures. */
        const val MAX_ATTEMPTS = 5

        fun dataFor(uri: String, name: String, pendingId: String? = null): Data = Data.Builder()
            .putString(KEY_URI, uri)
            .putString(KEY_NAME, name)
            .apply { if (pendingId != null) putString(KEY_PENDING_ID, pendingId) }
            .build()
    }
}
