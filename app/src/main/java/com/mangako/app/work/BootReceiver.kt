package com.mangako.app.work

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.mangako.app.data.settings.SettingsRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Re-schedules the periodic watcher after device reboot or app update, but
 * only if the user actually had the watcher turned on.
 */
@AndroidEntryPoint
class BootReceiver : BroadcastReceiver() {

    @Inject lateinit var settingsRepo: SettingsRepository

    override fun onReceive(context: Context, intent: Intent) {
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val settings = settingsRepo.flow.first()
                if (settings.watcherEnabled && settings.watchFolderUri != null) {
                    DirectoryScanWorker.schedule(context)
                }
            } finally {
                pendingResult.finish()
            }
        }
    }
}
