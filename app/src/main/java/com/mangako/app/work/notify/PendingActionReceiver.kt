package com.mangako.app.work.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.mangako.app.data.pending.PendingRepository
import com.mangako.app.data.pending.PendingStatus
import com.mangako.app.work.ProcessCbzWorker
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Handles the "Process" and "Ignore" action buttons on the detected-file
 * notification. Mirrors what the in-app approve/reject does: updates the
 * PendingFile row and — on Process — enqueues [ProcessCbzWorker].
 */
@AndroidEntryPoint
class PendingActionReceiver : BroadcastReceiver() {

    @Inject lateinit var pendingRepo: PendingRepository

    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra(Notifications.EXTRA_PENDING_ID) ?: return
        val pr = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val row = pendingRepo.find(id) ?: return@launch
                if (row.status != PendingStatus.PENDING) return@launch
                when (intent.action) {
                    Notifications.ACTION_APPROVE -> {
                        pendingRepo.approve(id)
                        enqueueProcess(context, row.id, row.uri, row.name)
                    }
                    Notifications.ACTION_REJECT -> pendingRepo.reject(id)
                }
                Notifications.cancelDetected(context, id)
            } finally {
                pr.finish()
            }
        }
    }

    private fun enqueueProcess(context: Context, pendingId: String, uri: String, name: String) {
        WorkManager.getInstance(context).enqueueUniqueWork(
            "process_$pendingId",
            ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<ProcessCbzWorker>()
                .setInputData(ProcessCbzWorker.dataFor(uri, name, pendingId))
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .build(),
        )
    }
}
