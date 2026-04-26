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
import com.mangako.app.data.pending.PendingStatus
import com.mangako.app.data.pipeline.PipelineRepository
import com.mangako.app.data.settings.SettingsRepository
import com.mangako.app.domain.pipeline.PipelineExecutor
import com.mangako.app.domain.rule.PipelineConfig
import com.mangako.app.work.ProcessCbzWorker
import com.mangako.app.work.notify.Notifications
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
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
     * Three buckets of files — picking one drives the list shown on the
     * Inbox. PENDING is the daily-use surface; PROCESSED and IGNORED let
     * the user revisit past decisions and reprocess a file if they want
     * to feed it back through the pipeline (e.g. after editing rules).
     */
    enum class Filter(val statuses: Set<PendingStatus>) {
        PENDING(setOf(PendingStatus.PENDING)),
        PROCESSED(setOf(PendingStatus.APPROVED, PendingStatus.DONE)),
        IGNORED(setOf(PendingStatus.REJECTED)),
    }

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
         * Dry-run preview of what the renaming pipeline would CURRENTLY
         * produce for this file's name. Used by the Pending view as a hint
         * about what 'Process' will do — meaningful only when the pipeline
         * could still actually run against the file.
         */
        val previewedFinal: String,
    )

    data class UiState(
        val filter: Filter = Filter.PENDING,
        val items: List<InboxItem> = emptyList(),
        val counts: Map<Filter, Int> = emptyMap(),
        val setup: SetupStatus = SetupStatus(false, false, false),
    )

    private val filter = MutableStateFlow(Filter.PENDING)
    val filterFlow: StateFlow<Filter> = filter.asStateFlow()

    @OptIn(ExperimentalCoroutinesApi::class)
    val state: StateFlow<UiState> = combine(
        filter.flatMapLatest { f -> pendingRepo.observeByStatuses(f.statuses) },
        pendingRepo.observeCounts(),
        settingsRepo.flow,
        pipelineRepo.flow,
    ) { items, statusCounts, settings, config ->
        UiState(
            filter = filter.value,
            // The Processed card reads file.finalName for the actual upload
            // result; previewedFinal stays for the Pending view's dry-run
            // 'this is what Process will do' hint.
            items = items.map { p -> InboxItem(file = p, previewedFinal = previewRename(config, p.name)) },
            counts = Filter.values().associateWith { f ->
                f.statuses.sumOf { statusCounts[it] ?: 0 }
            },
            setup = SetupStatus(
                serverConnected = settings.lanraragiUrl.isNotBlank() && settings.lanraragiApiKey.isNotBlank(),
                foldersConfigured = settings.watchFolderUris.isNotEmpty(),
                rulesConfigured = config.rules.isNotEmpty(),
            ),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UiState())

    fun setFilter(f: Filter) {
        filter.value = f
    }

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

    /** Reset a Processed / Ignored row back to PENDING so the user can run
     *  it through the pipeline again from the standard Inbox flow. */
    fun reprocess(file: PendingFile) = viewModelScope.launch {
        pendingRepo.reprocess(file.id)
    }

    /** Hard-delete a row — useful from the Processed view when the user
     *  doesn't ever want to see this file again. The on-disk .cbz is left
     *  alone; only Mangako's tracking row goes away. */
    fun forget(file: PendingFile) = viewModelScope.launch {
        pendingRepo.delete(file.id)
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
