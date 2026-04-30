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
import com.mangako.app.data.pending.PendingFile
import com.mangako.app.data.pending.PendingRepository
import com.mangako.app.data.pipeline.PipelineRepository
import com.mangako.app.data.settings.SettingsRepository
import com.mangako.app.domain.cbz.CbzProcessor
import com.mangako.app.domain.cbz.ThumbnailService
import com.mangako.app.domain.pipeline.AuditTrail
import com.mangako.app.domain.pipeline.PipelineEvaluation
import com.mangako.app.domain.pipeline.PipelineEvaluator
import com.mangako.app.domain.pipeline.PipelineInputBuilder
import com.mangako.app.domain.pipeline.UploadStatus
import com.mangako.app.domain.rule.PipelineConfig
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
 * Terminal stage of the upload pipeline. The flow is broken into named
 * private stages (`prepareSource`, `runPipeline`, `applyComicInfoUpdates`,
 * `upload`, `finalise`) so [doWork] reads as a top-level outline of what
 * happens, not a 200-line script.
 *
 * Retry policy: network-shaped errors return [Result.retry] up to
 * [MAX_ATTEMPTS] tries; parse / permission / 4xx failures return
 * [Result.failure] immediately. This stops WorkManager's default
 * 20-attempt exponential backoff from hammering forever on a permanently
 * bad input.
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
    private val thumbnailService: ThumbnailService,
    private val evaluator: PipelineEvaluator,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val request = parseRequest() ?: return@withContext Result.failure()
        val source = resolveSource(request) ?: return@withContext Result.failure()

        if (!awaitStable(source.doc, request.settings.debounceSeconds)) {
            return@withContext retryOrFail("File never stabilized")
        }

        val localCopy = copyToCache(source.doc) ?: return@withContext retryOrFail("Could not copy source")
        val startedAt = System.currentTimeMillis()
        try {
            val evaluation = runPipeline(localCopy, request)
            val finalName = ensureCbzSuffix(evaluation.filename)

            applyComicInfoUpdates(localCopy, evaluation, request.removals)

            val uploadStatus = upload(localCopy, finalName, request.settings)
            val deleted = finaliseSource(uploadStatus, source.doc, request, finalName)

            // Persist the audit trail on EVERY attempt, success or
            // failure. We use a stable history id derived from the
            // source file so WorkManager retries (and reprocesses)
            // REPLACE the previous row instead of inserting a dupe —
            // the user gets immediate feedback after the first attempt
            // and only ever sees the latest outcome per file.
            persistAuditTrail(
                request = request,
                source = source,
                localCopy = localCopy,
                evaluation = evaluation,
                finalName = finalName,
                uploadStatus = uploadStatus.copy(deletedSource = deleted),
                startedAt = startedAt,
            )

            return@withContext when {
                uploadStatus.success -> Result.success()
                uploadStatus.shouldRetry -> retryOrFail("Upload failed: ${uploadStatus.message}")
                else -> Result.failure()
            }
        } finally {
            localCopy.delete()
        }
    }

    // ─────────────────────────── Stages ───────────────────────────

    private suspend fun parseRequest(): Request? {
        val uri = inputData.getString(KEY_URI) ?: return null
        val originalName = inputData.getString(KEY_NAME) ?: "file.cbz"
        val pendingId = inputData.getString(KEY_PENDING_ID)
        val settings = settingsRepo.flow.first()
        val config = pipelineRepo.flow.first()
        // Look up overrides here (not at enqueue time) so edits made
        // after the worker was already queued — e.g. via approveAll —
        // still get picked up.
        val pendingRow = pendingId?.let { pendingRepo.find(it) }
        return Request(
            uriStr = uri,
            originalName = originalName,
            pendingId = pendingId,
            pendingRow = pendingRow,
            settings = settings,
            config = config,
            overrides = pendingRow?.metadataOverrides.orEmpty(),
            removals = pendingRow?.metadataRemovals.orEmpty(),
        )
    }

    private fun resolveSource(req: Request): SourceRef? {
        val uri = runCatching { android.net.Uri.parse(req.uriStr) }.getOrNull() ?: return null
        val doc = DocumentFile.fromSingleUri(applicationContext, uri) ?: return null
        return if (doc.exists()) SourceRef(doc, uri) else null
    }

    private suspend fun copyToCache(doc: DocumentFile): File? {
        val target = File(applicationContext.cacheDir, "incoming_${System.currentTimeMillis()}.cbz")
        return try {
            applicationContext.contentResolver.openInputStream(doc.uri)?.use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            } ?: run { target.delete(); null }
            target.takeIf { it.length() > 0 }
        } catch (e: IOException) {
            target.delete()
            null
        }
    }

    private fun runPipeline(localCopy: File, req: Request): PipelineEvaluation {
        // Detection metadata (overlaid with user edits) → pipeline input.
        // PipelineInputBuilder applies the same merge + filename-stem seed
        // the Inbox simulator uses, so the card preview matches what the
        // worker actually uploads.
        val input = PipelineInputBuilder.build(
            originalFilename = req.originalName,
            detectedMetadata = cbzProcessor.extractMetadata(localCopy),
            overrides = req.overrides,
            removals = req.removals,
        )
        return evaluator.evaluate(req.config, input)
    }

    private fun applyComicInfoUpdates(
        localCopy: File,
        evaluation: PipelineEvaluation,
        removals: Set<String>,
    ) {
        // Translate user-facing pipeline-variable names into ComicInfo
        // element names. The match is case-insensitive at write time so
        // we don't need exact PascalCase here.
        val elementsToRemove = removals
            .flatMap { CbzProcessor.comicInfoElementsForVariable(it) }
            .toSet()
        val updates = evaluation.comicInfoUpdates
        if (updates.isEmpty() && elementsToRemove.isEmpty()) return
        runCatching {
            cbzProcessor.updateMetadata(
                cbz = localCopy,
                fieldsToSet = updates,
                fieldsToRemove = elementsToRemove,
            )
        }
    }

    private suspend fun upload(
        localCopy: File,
        finalName: String,
        settings: SettingsRepository.Settings,
    ): UploadOutcome {
        val client = LanraragiClient(
            baseUrl = settings.lanraragiUrl,
            apiKey = settings.lanraragiApiKey,
        )
        val result = client.uploadArchive(localCopy, finalName)
        return result.fold(
            onSuccess = { UploadOutcome(success = true, httpCode = 200, message = it.message) },
            onFailure = { t ->
                val code = (t as? LanraragiException)?.code
                UploadOutcome(success = false, httpCode = code, message = t.message)
            },
        )
    }

    private suspend fun finaliseSource(
        uploadStatus: UploadOutcome,
        doc: DocumentFile,
        req: Request,
        finalName: String,
    ): Boolean {
        if (!uploadStatus.success) return false
        val deleted = if (req.settings.deleteOnSuccess) {
            runCatching { doc.delete() }.getOrDefault(false)
        } else false
        if (req.pendingId != null) {
            // Persist the actually-uploaded filename onto the Pending row
            // so the Inbox / Processed view renders the same string the
            // user will see in LANraragi (not a fresh re-run of today's
            // pipeline against the original name).
            pendingRepo.markDone(req.pendingId, finalName)
            Notifications.cancelDetected(applicationContext, req.pendingId)
        }
        return deleted
    }

    /**
     * Persist the audit trail and a thumbnail copy that survives the
     * source deletion. Two-stage thumbnail strategy:
     *   1. Make sure L1 (cacheDir) has a thumbnail — extract from the
     *      local copy if cache is cold (cloud-only URI at detection,
     *      file caught mid-write, etc.).
     *   2. Promote L1 → L2 (filesDir) so it survives cache eviction
     *      after the source .cbz has been deleted.
     */
    private suspend fun persistAuditTrail(
        request: Request,
        source: SourceRef,
        localCopy: File,
        evaluation: PipelineEvaluation,
        finalName: String,
        uploadStatus: UploadStatus,
        startedAt: Long,
    ) {
        val sizeBytes = request.pendingRow?.sizeBytes ?: localCopy.length()
        // Step 1: ensure L1 cache is populated. Won't re-extract if a
        // recent watcher pass already cached one.
        thumbnailService.thumbnailFromLocal(localCopy, request.uriStr, sizeBytes)
        // Step 2: promote to the persistent tier.
        val historyThumbnail = thumbnailService.persist(request.uriStr, sizeBytes)

        historyRepo.record(
            trail = AuditTrail(
                sourceFile = request.originalName,
                startedAt = startedAt,
                finishedAt = System.currentTimeMillis(),
                steps = evaluation.toAuditSteps(),
                finalName = finalName,
                uploadStatus = uploadStatus,
                comicInfoUpdates = evaluation.comicInfoUpdates,
            ),
            thumbnailPath = historyThumbnail?.absolutePath,
            // Stable history id derived from the source file. WorkManager
            // retries of the SAME upload attempt all share this id, so
            // INSERT OR REPLACE collapses them into a single History row
            // showing the latest outcome — no dupes from transient
            // failures, but the user still sees an entry after the very
            // first attempt instead of waiting for the retry budget to
            // drain.
            stableId = stableHistoryId(request, sizeBytes),
        )
    }

    /** Stable per-attempt history id. Prefers the Pending row id when
     *  present (uniquely identifies one user-initiated Process action,
     *  survives retries, and changes on reprocess because the row goes
     *  back through PENDING). For AUTO-mode runs without a Pending row
     *  we fall back to a SHA-1 of (uri, size) so retries still collapse. */
    private fun stableHistoryId(request: Request, sizeBytes: Long): String {
        request.pendingId?.let { return "pending:$it" }
        val md = java.security.MessageDigest.getInstance("SHA-1")
        md.update(request.uriStr.toByteArray(Charsets.UTF_8))
        md.update("|$sizeBytes".toByteArray(Charsets.UTF_8))
        return "auto:" + md.digest().joinToString("") { "%02x".format(it) }
    }

    // ─────────────────────────── Helpers ───────────────────────────

    private fun retryOrFail(reason: String): Result =
        if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.failure()

    private suspend fun awaitStable(doc: DocumentFile, debounceSeconds: Int): Boolean {
        val windowMs = debounceSeconds.coerceAtLeast(1) * 1000L
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

    // ─────────────────────────── Types ───────────────────────────

    /** Materialised inputs for one work invocation. */
    private data class Request(
        val uriStr: String,
        val originalName: String,
        val pendingId: String?,
        val pendingRow: PendingFile?,
        val settings: SettingsRepository.Settings,
        val config: PipelineConfig,
        val overrides: Map<String, String>,
        val removals: Set<String>,
    )

    private data class SourceRef(val doc: DocumentFile, val uri: android.net.Uri)

    /** Local upload outcome with the retry-vs-fail decision baked in. */
    private data class UploadOutcome(
        val success: Boolean,
        val httpCode: Int?,
        val message: String?,
    ) {
        /** Network errors / 5xx / IOException → retry. 4xx → permanent. */
        val shouldRetry: Boolean get() = !success && (httpCode?.let { it in 500..599 } ?: true)
    }

    private fun UploadOutcome.copy(deletedSource: Boolean): UploadStatus =
        UploadStatus(success = success, httpCode = httpCode, message = message, deletedSource = deletedSource)

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
