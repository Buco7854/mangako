package com.mangako.app.work

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.mangako.app.data.settings.SettingsRepository
import com.mangako.app.work.observer.RealtimeWatchService
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Re-schedules the periodic watcher and the real-time foreground service
 * after device reboot or app update, but only if the user actually had the
 * watcher turned on.
 */
@AndroidEntryPoint
class BootReceiver : BroadcastReceiver() {

    @Inject lateinit var settingsRepo: SettingsRepository

    override fun onReceive(context: Context, intent: Intent) {
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val settings = settingsRepo.flow.first()
                if (settings.watcherEnabled && settings.watchFolderUris.isNotEmpty()) {
                    DirectoryScanWorker.schedule(context, settings.watchFolderUris)
                    RealtimeWatchService.reconcile(context, watcherEnabled = true)
                }
            } finally {
                pendingResult.finish()
            }
        }
    }
}
