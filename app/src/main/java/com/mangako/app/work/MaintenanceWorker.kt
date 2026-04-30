package com.mangako.app.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.mangako.app.data.history.HistoryRepository
import com.mangako.app.data.pending.PendingRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * Daily housekeeping: prune the History table older than 90 days and
 * DONE/REJECTED Pending rows older than 7 days. Without this both tables
 * grow unbounded — a power user can easily produce 10k+ history rows a year.
 *
 * Scheduled as a periodic work request; the actual cadence is governed by
 * WorkManager's 15-minute minimum, but we only need daily accuracy.
 */
@HiltWorker
class MaintenanceWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val historyRepo: HistoryRepository,
    private val pendingRepo: PendingRepository,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        historyRepo.pruneOlderThan(now - HISTORY_RETENTION_MS)
        pendingRepo.pruneOlderThan(now - PENDING_RETENTION_MS)
        Result.success()
    }

    companion object {
        const val UNIQUE_NAME = "mangako_maintenance"

        // Retention windows. Tuned to "recent enough to debug, old enough to
        // forget" — adjust here if users start losing context they wanted.
        private val HISTORY_RETENTION_MS = TimeUnit.DAYS.toMillis(90)
        private val PENDING_RETENTION_MS = TimeUnit.DAYS.toMillis(7)

        fun schedule(context: Context) {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                PeriodicWorkRequestBuilder<MaintenanceWorker>(1, TimeUnit.DAYS).build(),
            )
        }
    }
}
