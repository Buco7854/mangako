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
import com.mangako.app.data.pipeline.PipelineRepository
import com.mangako.app.data.settings.SettingsRepository
import com.mangako.app.domain.pipeline.PipelineExecutor
import com.mangako.app.domain.rule.PipelineConfig
import com.mangako.app.work.ProcessCbzWorker
import com.mangako.app.work.notify.Notifications
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class InboxViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val pendingRepo: PendingRepository,
    settingsRepo: SettingsRepository,
    pipelineRepo: PipelineRepository,
) : ViewModel() {

    /**
     * Setup checklist surfaced as a banner on the Inbox so a brand-new user can
     * see at a glance what's missing before the watcher will produce anything.
     * `isComplete` is what the banner uses to hide itself.
     */
    data class SetupStatus(
        val serverConnected: Boolean,
        val foldersConfigured: Boolean,
        val rulesConfigured: Boolean,
    ) {
        val isComplete: Boolean
            get() = serverConnected && foldersConfigured && rulesConfigured
    }

    data class InboxItem(
        val file: PendingFile,
        /**
         * What the renaming pipeline would currently produce for this file's
         * filename. Empty if the pipeline is empty, in which case we fall back
         * to the original name.
         */
        val previewedFinal: String,
    )

    data class UiState(
        val items: List<InboxItem> = emptyList(),
        val setup: SetupStatus = SetupStatus(false, false, false),
    )

    val state: StateFlow<UiState> = combine(
        pendingRepo.observePending(),
        settingsRepo.flow,
        pipelineRepo.flow,
    ) { pending, settings, config ->
        UiState(
            items = pending.map { p ->
                InboxItem(file = p, previewedFinal = previewRename(config, p.name))
            },
            setup = SetupStatus(
                serverConnected = settings.lanraragiUrl.isNotBlank() && settings.lanraragiApiKey.isNotBlank(),
                foldersConfigured = settings.watchFolderUris.isNotEmpty(),
                rulesConfigured = config.rules.isNotEmpty(),
            ),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UiState())

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
        state.value.items.forEach { approve(it.file) }
    }

    fun rejectAll() = viewModelScope.launch {
        state.value.items.forEach { reject(it.file) }
    }

    /**
     * Best-effort dry run of the pipeline against [name]. The real run inside
     * [ProcessCbzWorker] also extracts ComicInfo metadata; we don't have that
     * here, so any rule that depends on real metadata will simply not fire and
     * the preview will reflect what would happen if the file had no metadata —
     * still useful as a "this is what the rename rules would do" indicator.
     */
    private fun previewRename(config: PipelineConfig, name: String): String =
        if (config.rules.isEmpty()) name
        else PipelineExecutor()
            .run(config, PipelineExecutor.Input(originalFilename = name))
            .finalFilename
}
