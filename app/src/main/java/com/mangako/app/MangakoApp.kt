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
import com.mangako.app.work.observer.RealtimeWatchService
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
     * Process-scope coroutine for one-shot startup work (default-pipeline
     * preload, metadata backfill) plus the settings-flow collector that
     * reconciles [RealtimeWatchService]. The service itself owns the
     * inotify watchers; we just toggle it on/off in response to settings
     * changes.
     */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        preloadDefaultPipelineOnce()
        backfillPendingDetectionInfo()
        reconcileWatchService()
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
     * Watch the settings flow and start/stop [RealtimeWatchService] in
     * response. The service is the only thing keeping inotify watchers
     * alive when the app is in the background, so we treat it as the
     * single source of truth — `reconcile` is idempotent and safe to call
     * on every settings change.
     */
    private fun reconcileWatchService() {
        appScope.launch {
            settingsRepo.flow
                .distinctUntilChanged { a, b -> a.watcherEnabled == b.watcherEnabled }
                .collect { settings ->
                    RealtimeWatchService.reconcile(this@MangakoApp, settings.watcherEnabled)
                }
        }
    }
}
