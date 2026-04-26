package com.mangako.app

import android.app.Application
import android.net.Uri
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.mangako.app.data.settings.SettingsRepository
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
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class MangakoApp : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var settingsRepo: SettingsRepository

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
        startRealtimeWatchersIfPermitted()
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
