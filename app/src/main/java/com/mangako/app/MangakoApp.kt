package com.mangako.app

import android.app.Application
import android.net.Uri
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.mangako.app.data.pending.PendingRepository
import com.mangako.app.data.pending.PendingStatus
import com.mangako.app.data.pipeline.PipelineRepository
import com.mangako.app.data.settings.SettingsRepository
import com.mangako.app.domain.cbz.CbzProcessor
import com.mangako.app.work.DirectoryScanWorker
import com.mangako.app.work.MaintenanceWorker
import com.mangako.app.work.observer.MangakoFolderObserver
import com.mangako.app.work.observer.SafPathResolver
import com.mangako.app.work.observer.canUseFileObserver
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltAndroidApp
class MangakoApp : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var settingsRepo: SettingsRepository
    @Inject lateinit var pipelineRepo: PipelineRepository
    @Inject lateinit var pendingRepo: PendingRepository
    @Inject lateinit var cbzProcessor: CbzProcessor

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .setMinimumLoggingLevel(android.util.Log.INFO)
            .build()

    /**
     * Process-scope lifecycle for the inotify-backed real-time watchers.
     * Lives as long as the app process — when the process is killed by the
     * OS, the observers stop, and we re-arm on the next process start. This
     * matches Nextcloud's auto-upload model and avoids the persistent-
     * foreground-service notification that users rightly hate. WorkManager
     * (15-min periodic) and scan-on-resume cover the gap when the process
     * isn't alive.
     */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val activeObservers = mutableMapOf<String, MangakoFolderObserver>()

    override fun onCreate() {
        super.onCreate()
        // Schedule daily housekeeping once; WorkManager deduplicates by unique name.
        MaintenanceWorker.schedule(this)
        preloadDefaultPipelineOnce()
        backfillPendingDetectionInfo()
        startRealtimeWatchersIfPermitted()
    }

    /**
     * Re-extract ComicInfo metadata for Pending rows whose cached
     * snapshot is empty. The watcher only refreshes metadata when
     * [DirectoryScanWorker] walks a folder — which never happens if
     * the user has the watcher toggled off, or before the periodic
     * 15-minute schedule ticks. Without this pass, rows that originally
     * failed extraction (cloud-mounted SAF URIs that dropped bytes
     * mid-stream, files caught mid-write) stay title-less forever —
     * the Inbox simulator runs against an empty metadata map and falls
     * back to the raw filename.
     *
     * Thumbnails are NOT backfilled here any more —
     * [com.mangako.app.domain.cbz.ThumbnailService] generates them
     * lazily the first time an Inbox card composes, so no eager pass
     * is required. That keeps process startup snappy on devices with
     * a long pending queue.
     */
    private fun backfillPendingDetectionInfo() {
        appScope.launch {
            withContext(Dispatchers.IO) {
                val rows = pendingRepo.observeByStatuses(setOf(PendingStatus.PENDING)).first()
                for (row in rows) {
                    if (row.metadata.isNotEmpty()) continue
                    val uri = runCatching { Uri.parse(row.uri) }.getOrNull() ?: continue
                    val info = runCatching {
                        cbzProcessor.extractDetectionFromUri(this@MangakoApp, uri)
                    }.getOrNull() ?: continue
                    if (info.metadata.isEmpty()) continue
                    pendingRepo.updateDetectionInfo(
                        id = row.id,
                        metadata = info.metadata,
                        thumbnailPath = null,
                    )
                }
            }
        }
    }

    /**
     * One-shot "preload LANraragi defaults" migration. The pipeline DataStore
     * already seeds with the standard template when no pipeline.json exists,
     * but that doesn't help users who have an empty pipeline.json on disk
     * from an earlier version (the file exists, so the default is never
     * consulted). On the first app launch where [SettingsRepository.Settings.pipelineInitialized]
     * is still false, we check the live pipeline: when it has zero rules,
     * load the standard template; either way we set the flag so subsequent
     * launches respect any user clear and don't keep re-loading defaults.
     */
    private fun preloadDefaultPipelineOnce() {
        appScope.launch {
            val settings = settingsRepo.flow.first()
            if (settings.pipelineInitialized) return@launch
            val current = pipelineRepo.flow.first()
            if (current.rules.isEmpty()) {
                pipelineRepo.loadLanraragiStandard()
            }
            settingsRepo.update { it.copy(pipelineInitialized = true) }
        }
    }

    /**
     * Collect the settings flow and (re-)arm a [MangakoFolderObserver] for
     * each watched folder whose SAF tree URI we can resolve to a real
     * filesystem path. When the user toggles 'All files access' off, or
     * removes a folder, the corresponding observer is stopped.
     *
     * The fall-through path (no permission or unresolvable URI) is the
     * existing SAF + content-uri trigger + scan-on-resume + periodic stack,
     * so the app stays functional even with the permission denied.
     */
    private fun startRealtimeWatchersIfPermitted() {
        appScope.launch {
            settingsRepo.flow
                .distinctUntilChanged { a, b ->
                    a.watcherEnabled == b.watcherEnabled &&
                        a.watchFolderUris == b.watchFolderUris
                }
                .collect { settings ->
                    syncObservers(settings)
                }
        }
    }

    private fun syncObservers(settings: SettingsRepository.Settings) {
        if (!canUseFileObserver() || !settings.watcherEnabled) {
            stopAllObservers()
            return
        }

        // Resolve each SAF URI to a filesystem path. URIs from Drive, OneDrive,
        // FTP shares, etc. resolve to null and are silently skipped — the SAF
        // observer + periodic schedule still cover them.
        val targetPaths = settings.watchFolderUris
            .mapNotNull { SafPathResolver.toFilePath(this, Uri.parse(it))?.absolutePath }
            .toSet()

        // Stop watchers for paths that have been removed.
        val toRemove = activeObservers.keys - targetPaths
        toRemove.forEach { activeObservers.remove(it)?.stop() }

        // Start watchers for newly-added paths.
        for (path in targetPaths) {
            if (!activeObservers.containsKey(path)) {
                val observer = MangakoFolderObserver(path) {
                    // Inotify callbacks run on a shared framework thread —
                    // hand off to WorkManager so the actual disk walk +
                    // pipeline routing happens on a proper background worker.
                    DirectoryScanWorker.runIfWatching(applicationContext)
                }
                observer.start()
                activeObservers[path] = observer
            }
        }
    }

    private fun stopAllObservers() {
        activeObservers.values.forEach { it.stop() }
        activeObservers.clear()
    }
}
