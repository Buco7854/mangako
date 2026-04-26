package com.mangako.app.work

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.mangako.app.data.pending.PendingRepository
import com.mangako.app.data.settings.SettingsRepository
import com.mangako.app.work.notify.Notifications
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.firstOrNull
import java.util.concurrent.TimeUnit

/**
 * Periodic scan of every folder the user has subscribed to. SAF URIs can't be
 * watched with FileObserver across API levels, so we poll every 15 minutes
 * (the WorkManager minimum) and also expose a "Scan now" trigger.
 *
 * What happens with each newly-discovered .cbz depends on [SettingsRepository.Settings.mode]:
 *   - APPROVAL: row is inserted into the pending queue, optionally with a
 *               notification carrying Process/Ignore actions.
 *   - AUTO:     a [ProcessCbzWorker] is enqueued immediately.
 */
@HiltWorker
class DirectoryScanWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val settingsRepo: SettingsRepository,
    private val pendingRepo: PendingRepository,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val settings = settingsRepo.flow.first()
        // The "manual" flag is set by [runOnce]: the user explicitly tapped
        // "Scan now", which is intent enough to override watcherEnabled. The
        // periodic scheduler does NOT set it, so a watcher that's been
        // explicitly turned off still stays off in the background.
        val manual = inputData.getBoolean(KEY_MANUAL, false)
        if (settings.watchFolderUris.isEmpty()) {
            return@withContext Result.success()
        }
        if (!manual && !settings.watcherEnabled) {
            return@withContext Result.success()
        }

        try {
            scanFolders(settings)
            Result.success()
        } finally {
            // Re-arm the content-uri-trigger observer so the worker wakes
            // again the next time the SAF tree changes. WorkManager consumes
            // the trigger after firing, so without this we'd only get one
            // event-driven run before the worker fell back to the periodic
            // 15-minute schedule.
            if (!manual && settings.watcherEnabled && settings.watchFolderUris.isNotEmpty()) {
                scheduleObserver(applicationContext, settings.watchFolderUris)
            }
        }
    }

    private suspend fun scanFolders(settings: SettingsRepository.Settings) {
        for (folderUri in settings.watchFolderUris) {
            val folder = DocumentFile.fromTreeUri(applicationContext, Uri.parse(folderUri))
            if (folder == null || !folder.isDirectory) continue

            walkCbz(folder).forEach { child ->
                val name = child.name.orEmpty()
                when (settings.mode) {
                    SettingsRepository.Mode.APPROVAL -> handleApproval(
                        child = child,
                        name = name,
                        folderUri = folderUri,
                        notify = settings.notifyOnDetected,
                    )
                    SettingsRepository.Mode.AUTO -> enqueueProcess(
                        uri = child.uri.toString(),
                        name = name,
                    )
                }
            }
        }
        // When per-file toasts are disabled but the inbox has waiting items,
        // surface a single persistent "N awaiting review" notification so the
        // user isn't relying purely on spotting the bottom-nav badge.
        if (settings.mode == SettingsRepository.Mode.APPROVAL && !settings.notifyOnDetected) {
            val pendingCount = pendingRepo.countPending().firstOrNull() ?: 0
            Notifications.postInboxSummary(applicationContext, pendingCount)
        } else {
            Notifications.cancelInboxSummary(applicationContext)
        }
    }

    /**
     * Depth-first walk of [root] yielding every .cbz descendant. Manga readers
     * (Mihon, Tachiyomi) often nest files several levels deep — e.g.
     * `root/ExtensionName/MangaName/Chapter.cbz` — so a single-level `listFiles`
     * misses the actual downloads. We cap recursion depth defensively to avoid
     * pathological loops if the tree ever contains a symlink cycle via SAF.
     */
    private fun walkCbz(root: DocumentFile, maxDepth: Int = MAX_WALK_DEPTH): Sequence<DocumentFile> = sequence {
        val stack = ArrayDeque<Pair<DocumentFile, Int>>().apply { addLast(root to 0) }
        while (stack.isNotEmpty()) {
            val (node, depth) = stack.removeLast()
            if (!node.isDirectory) continue
            for (child in node.listFiles()) {
                when {
                    child.isDirectory && depth < maxDepth -> stack.addLast(child to (depth + 1))
                    child.isFile && child.name.orEmpty().endsWith(".cbz", ignoreCase = true) -> yield(child)
                }
            }
        }
    }

    private suspend fun handleApproval(
        child: DocumentFile,
        name: String,
        folderUri: String,
        notify: Boolean,
    ) {
        val row = pendingRepo.addIfAbsent(
            uri = child.uri.toString(),
            name = name,
            sizeBytes = child.length(),
            folderUri = folderUri,
        )
        // addIfAbsent returns either a fresh PENDING row or an existing one of
        // any status — only post a notification for the former.
        if (row.status == com.mangako.app.data.pending.PendingStatus.PENDING && notify) {
            Notifications.postDetected(
                context = applicationContext,
                pendingId = row.id,
                filename = name,
                folder = DocumentFile.fromTreeUri(applicationContext, Uri.parse(folderUri))?.name
                    ?: folderUri,
            )
        }
    }

    private fun enqueueProcess(uri: String, name: String) {
        WorkManager.getInstance(applicationContext).enqueueUniqueWork(
            "process_${uri.hashCode()}",
            ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<ProcessCbzWorker>()
                .setInputData(ProcessCbzWorker.dataFor(uri, name))
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build(),
                )
                .build(),
        )
    }

    companion object {
        const val UNIQUE_NAME = "mangako_watcher"
        private const val OBSERVER_UNIQUE_NAME = "mangako_watcher_observer"

        /** Depth cap for [walkCbz]. Mihon/Tachiyomi typically nest 2–3 deep; 6 is generous. */
        private const val MAX_WALK_DEPTH = 6

        /** Periodic safety-net interval (WorkManager minimum is 15 min). */
        private const val SCAN_INTERVAL_MINUTES = 15L

        /** Marker on the [WorkerParameters.inputData] that distinguishes a user-
         *  initiated "Scan now" from the periodic / observer-triggered runs.
         *  When set, the scan runs even if the persistent watcher toggle is off. */
        const val KEY_MANUAL = "manual"

        /**
         * Set up both the event-driven observer and the periodic safety-net.
         * Call this from anywhere settings affecting the watcher change
         * (folder list mutations, watcher toggle on, etc.). It's idempotent —
         * existing workers are replaced with the latest URI list.
         */
        fun schedule(context: Context, watchFolderUris: Set<String>) {
            schedulePeriodic(context)
            scheduleObserver(context, watchFolderUris)
        }

        /**
         * Periodic safety net. Runs every 15 minutes even if no content-uri
         * triggers fire — guards against DocumentsProviders that don't notify
         * on change (some third-party file managers).
         *
         * No network constraint: scanning is a local-disk operation, so
         * requiring CONNECTED would gate the worker offline. Upload still
         * keeps its own network constraint inside ProcessCbzWorker.
         */
        private fun schedulePeriodic(context: Context) {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                PeriodicWorkRequestBuilder<DirectoryScanWorker>(SCAN_INTERVAL_MINUTES, TimeUnit.MINUTES).build(),
            )
        }

        /**
         * Event-driven observer. Wakes the worker within seconds of a watched
         * SAF tree changing (file added / renamed / removed) instead of
         * waiting for the next periodic tick.
         *
         * Implementation: a one-shot work request with content-uri triggers
         * for each folder. WorkManager consumes the trigger after firing, so
         * the worker re-arms itself at the end of doWork() to keep listening.
         *
         * Caveats:
         *  - Triggers only fire when the underlying [DocumentsProvider]
         *    notifies via [ContentResolver.notifyChange]. Standard providers
         *    (DownloadProvider, ExternalStorageProvider) do; some third-party
         *    file managers don't. The periodic schedule above is the fallback
         *    for those.
         *  - The 3-second debounce avoids re-running mid-file-write while a
         *    download is still streaming bytes.
         */
        private fun scheduleObserver(context: Context, watchFolderUris: Set<String>) {
            if (watchFolderUris.isEmpty()) return
            val constraints = Constraints.Builder()
                .apply {
                    watchFolderUris.forEach { uri ->
                        runCatching { addContentUriTrigger(Uri.parse(uri), true) }
                    }
                }
                .setTriggerContentUpdateDelay(3, TimeUnit.SECONDS)
                .setTriggerContentMaxDelay(15, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                OBSERVER_UNIQUE_NAME,
                ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<DirectoryScanWorker>()
                    .setConstraints(constraints)
                    .build(),
            )
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_NAME)
            WorkManager.getInstance(context).cancelUniqueWork(OBSERVER_UNIQUE_NAME)
        }

        /** One-shot scan (UI "Scan now" button). */
        fun runOnce(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                "mangako_watcher_once",
                ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<DirectoryScanWorker>()
                    .setInputData(androidx.work.workDataOf(KEY_MANUAL to true))
                    .build(),
            )
        }

        /**
         * Scan if (and only if) the watcher is enabled. Used by MainActivity
         * on app resume — when the user comes back to Mangako after
         * downloading something via another app, the SAF DocumentsProvider
         * may have skipped notifying our content-uri trigger (most
         * downloaders write through the underlying filesystem, not via
         * DocumentsContract), so a synchronous catch-up scan on resume is
         * what consistently picks them up. The worker itself checks
         * watcherEnabled and bails if the user has the watcher toggled off.
         */
        fun runIfWatching(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                "mangako_watcher_resume",
                ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<DirectoryScanWorker>().build(),
            )
        }
    }
}
