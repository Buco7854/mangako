package com.mangako.app.ui.inbox

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.mangako.app.data.pending.PendingFile
import com.mangako.app.data.pending.PendingRepository
import com.mangako.app.work.ProcessCbzWorker
import com.mangako.app.work.notify.Notifications
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class InboxViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val pendingRepo: PendingRepository,
) : ViewModel() {

    val items: StateFlow<List<PendingFile>> = pendingRepo.observePending()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun approve(file: PendingFile) = viewModelScope.launch {
        pendingRepo.approve(file.id)
        Notifications.cancelDetected(context, file.id)
        WorkManager.getInstance(context).enqueueUniqueWork(
            "process_${file.id}",
            ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<ProcessCbzWorker>()
                .setInputData(ProcessCbzWorker.dataFor(file.uri, file.name, file.id))
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build(),
                )
                .build(),
        )
    }

    fun reject(file: PendingFile) = viewModelScope.launch {
        pendingRepo.reject(file.id)
        Notifications.cancelDetected(context, file.id)
    }

    fun approveAll() = viewModelScope.launch {
        items.value.forEach { approve(it) }
    }

    fun rejectAll() = viewModelScope.launch {
        items.value.forEach { reject(it) }
    }
}
