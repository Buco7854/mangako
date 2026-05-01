package com.mangako.app.work.observer

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.mangako.app.data.settings.SettingsRepository
import com.mangako.app.work.DirectoryScanWorker
import com.mangako.app.work.notify.Notifications
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/**
 * Long-running foreground service that owns the inotify-backed
 * [MangakoFolderObserver]s. It exists because Android kills the app process
 * aggressively when the user closes the app — and FileObservers die with the
 * process, so without this service users would only see new chapters when
 * they manually opened Mangako.
 *
 * The trade-off is the persistent low-priority notification (`CHANNEL_WATCHER`)
 * Android requires to keep the service alive. That's the same deal Nextcloud,
 * Syncthing, FolderSync, etc. all make — there's no Android API that lets you
 * watch the filesystem reliably without it.
 *
 * The service is only started when both:
 *  - the user has the watcher toggled on (`watcherEnabled`),
 *  - the user has granted MANAGE_EXTERNAL_STORAGE so [FileObserver] can run
 *    against real filesystem paths ([canUseFileObserver]).
 *
 * If either becomes false at runtime the service stops itself, the
 * notification goes away, and the app falls back to WorkManager-periodic +
 * scan-on-resume.
 */
@AndroidEntryPoint
class RealtimeWatchService : Service() {

    @Inject lateinit var settingsRepo: SettingsRepository

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val activeObservers = mutableMapOf<String, MangakoFolderObserver>()
    private var collectorJob: Job? = null
    private var catchUpJob: Job? = null

    /**
     * Receiver for the wake-style system broadcasts (screen on, user
     * unlock, power connected). These fire at moments where the user is
     * very likely about to interact with manga, AND moments where the
     * OS has likely just left Doze — both excellent excuses to refresh
     * the inbox even if inotify dropped events while we were idle.
     *
     * Nextcloud's auto-upload service registers an analogous wake
     * receiver for the same reason; this is the user's reference point
     * and the part that turns "I only get notifications when I open
     * the app" into "I see new chapters when I unlock my phone".
     *
     * Only registered at runtime — manifest receivers can't subscribe
     * to ACTION_SCREEN_ON / ACTION_USER_PRESENT.
     */
    private val wakeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            DirectoryScanWorker.runIfWatching(context.applicationContext)
        }
    }
    private var wakeReceiverRegistered = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Notifications.ensureChannels(this)
        startInForeground()
        registerWakeReceiver()
        startPeriodicCatchUp()
        observeSettings()
    }

    /**
     * START_STICKY tells Android to recreate the service if it has to kill
     * us under memory pressure. Without it, a transient memory crunch would
     * stop the watcher and the user would silently lose real-time detection
     * until the next app open.
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onDestroy() {
        collectorJob?.cancel()
        catchUpJob?.cancel()
        unregisterWakeReceiver()
        stopAllObservers()
        scope.cancel()
        super.onDestroy()
    }

    /**
     * In-process safety net that fires every [CATCH_UP_INTERVAL_MS] while
     * the foreground service is alive. Runs in addition to inotify, not
     * instead of it.
     *
     * Why we don't lean only on WorkManager's periodic scan:
     *  - WorkManager's minimum interval is 15 minutes, and Doze can stretch
     *    that further on devices with aggressive power management.
     *  - This loop runs inside an FGS coroutine, so as long as the service
     *    is alive (which is the WHOLE POINT of the service) it ticks on
     *    its own clock, independent of JobScheduler / Doze deferral.
     *  - inotify on its own loses events during kernel queue overflow and
     *    on filesystems that don't deliver them at all (FUSE-mounted SAF
     *    backends, NFS, etc.). The user has watcherEnabled — that's
     *    explicit consent to spend the cycles double-checking.
     */
    private fun startPeriodicCatchUp() {
        catchUpJob = scope.launch {
            while (isActive) {
                delay(CATCH_UP_INTERVAL_MS)
                DirectoryScanWorker.runIfWatching(applicationContext)
            }
        }
    }

    private fun registerWakeReceiver() {
        if (wakeReceiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(Intent.ACTION_POWER_CONNECTED)
        }
        runCatching {
            // System broadcasts are still delivered to NOT_EXPORTED
            // receivers — the flag only blocks third-party app senders.
            // Required from API 33+ when not specifying a permission.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(wakeReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                registerReceiver(wakeReceiver, filter)
            }
            wakeReceiverRegistered = true
        }.onFailure { Log.w(TAG, "Could not register wake receiver: ${it.message}") }
    }

    private fun unregisterWakeReceiver() {
        if (!wakeReceiverRegistered) return
        runCatching { unregisterReceiver(wakeReceiver) }
        wakeReceiverRegistered = false
    }

    private fun startInForeground() {
        val notification = Notifications.buildWatcherNotification(this)
        // Android 14 (API 34) requires the foregroundServiceType be passed
        // into startForeground when the service was declared with that type
        // in the manifest. Earlier APIs ignore the second arg.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                Notifications.WATCHER_NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            startForeground(Notifications.WATCHER_NOTIFICATION_ID, notification)
        }
    }

    private fun observeSettings() {
        collectorJob = scope.launch {
            settingsRepo.flow
                .distinctUntilChanged { a, b ->
                    a.watcherEnabled == b.watcherEnabled &&
                        a.watchFolderUris == b.watchFolderUris
                }
                .collect { settings ->
                    if (!canUseFileObserver() || !settings.watcherEnabled) {
                        Log.i(TAG, "Watcher conditions no longer met, stopping service.")
                        stopSelf()
                        return@collect
                    }
                    syncObservers(settings.watchFolderUris)
                }
        }
    }

    private fun syncObservers(watchFolderUris: Set<String>) {
        val targetPaths = watchFolderUris
            .mapNotNull { SafPathResolver.toFilePath(this, Uri.parse(it))?.absolutePath }
            .toSet()

        val toRemove = activeObservers.keys - targetPaths
        toRemove.forEach { activeObservers.remove(it)?.stop() }

        for (path in targetPaths) {
            if (!activeObservers.containsKey(path)) {
                val observer = MangakoFolderObserver(path) {
                    DirectoryScanWorker.runIfWatching(applicationContext)
                }
                observer.start()
                activeObservers[path] = observer
            }
        }

        // Nothing to watch — stay alive only if we expect URIs that resolve
        // later (e.g. the user just removed all folders). For now: if there's
        // truly nothing to do, drop the notification.
        if (activeObservers.isEmpty() && watchFolderUris.isEmpty()) {
            stopSelf()
        }
    }

    private fun stopAllObservers() {
        activeObservers.values.forEach { it.stop() }
        activeObservers.clear()
    }

    companion object {
        private const val TAG = "RealtimeWatchSvc"

        /**
         * In-process catch-up tick. Frequent enough that users notice new
         * chapters in roughly the same window as a notification but not
         * so often that we burn battery walking the SAF tree on idle
         * devices. WorkManager's periodic schedule remains as the
         * cross-process fallback for when the service itself is dead.
         */
        private val CATCH_UP_INTERVAL_MS = TimeUnit.MINUTES.toMillis(5)

        /**
         * Starts the service if the user has the watcher on AND has granted
         * `MANAGE_EXTERNAL_STORAGE`; otherwise stops it. Idempotent —
         * Android dedupes start commands for an already-running service.
         */
        fun reconcile(context: Context, watcherEnabled: Boolean) {
            val intent = Intent(context, RealtimeWatchService::class.java)
            if (watcherEnabled && canUseFileObserver()) {
                runCatching {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.startForegroundService(intent)
                    } else {
                        context.startService(intent)
                    }
                }.onFailure { Log.w(TAG, "Could not start watcher service: ${it.message}") }
            } else {
                runCatching { context.stopService(intent) }
            }
        }
    }
}
