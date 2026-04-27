package com.mangako.app.ui.inbox

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.mangako.app.data.history.HistoryRepository
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
    historyRepo: HistoryRepository,
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
         * Pipeline-simulated %title%, ready to display as the human
         * title on the card. Computed by running today's pipeline
         * against the row's detection-time metadata + user overrides
         * and reading the resulting %title% variable. Falls back to
         * a sensible value when detection didn't manage to read
         * ComicInfo (the source filename, minus .cbz).
         */
        val simulatedTitle: String,
        /**
         * The actually-uploaded filename for a Processed row. Sourced
         * preferentially from the row's own [PendingFile.finalName] column
         * (set at upload time), then from the matching history record if
         * the column is null (legacy rows). Null only when neither source
         * has the value.
         */
        val recordedFinal: String?,
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
        historyRepo.observe(),
    ) { items, statusCounts, settings, config, history ->
        // Index history by original filename so Processed cards can fall
        // back to the most-recent recorded final name when the row's own
        // finalName column is null (legacy rows from before that column
        // existed). The History detail screen reads the same finalName off
        // the trail, so this guarantees the Processed view and History
        // never disagree.
        val historyFinalByOriginal: Map<String, String> = history
            .asReversed() // observe() returns newest-first; reverse so the newest wins the put.
            .associate { it.originalName to it.finalName }
        UiState(
            filter = filter.value,
            items = items.map { p ->
                // Simulate the pipeline as the worker will run it —
                // detection-time metadata + user overrides merged on
                // top. Read the resulting %title% to get the human
                // title we'll show on the card; that's the same value
                // the pipeline will write into ComicInfo, so the card
                // never lies about what Process will produce.
                InboxItem(
                    file = p,
                    simulatedTitle = simulateTitle(config, p),
                    recordedFinal = p.finalName ?: historyFinalByOriginal[p.name],
                )
            },
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

    /** Persist edit-detection overrides — pipeline variables that
     *  win over what ExtractXmlMetadata reads from the file. The
     *  worker reads them back when the user later taps Process. Pass
     *  an empty map to clear all overrides. */
    fun saveOverrides(file: PendingFile, metadata: Map<String, String>) = viewModelScope.launch {
        pendingRepo.setMetadataOverrides(file.id, metadata)
    }

    fun approveAll() = viewModelScope.launch {
        state.value.items.forEach { approve(it.file) }
    }

    fun rejectAll() = viewModelScope.launch {
        state.value.items.forEach { reject(it.file) }
    }

    /**
     * Run today's pipeline against [file]'s detection-time metadata
     * (overlaid with the user's edit-detection overrides) and return
     * the resulting %title% variable. That's the same string the
     * worker will end up writing into ComicInfo at processing time,
     * so the Inbox card can show "what's about to land in LANraragi"
     * without lying.
     *
     * Fallback chain (high → low):
     *   1. Pipeline-simulated %title% (when the pipeline ran cleanly)
     *   2. ComicInfo's <Title> + the user's title override (raw)
     *   3. The detected filename minus .cbz
     *
     * "Cleanly" means the result is non-blank AND doesn't contain
     * any unresolved %token% literals — those slip through when a
     * SetVariable rule references a variable that wasn't populated
     * (e.g. manhwa formatting reading %number% on a file with no
     * <Number> tag). When that happens we'd rather show the user
     * the ComicInfo title than a half-substituted template string.
     */
    private fun simulateTitle(config: PipelineConfig, file: PendingFile): String {
        val rawTitle = file.metadataOverrides["title"]?.takeIf { it.isNotBlank() }
            ?: file.metadata["title"]?.takeIf { it.isNotBlank() }
        val filenameStem = file.name.removeSuffix(".cbz")

        if (config.rules.isEmpty()) return rawTitle ?: filenameStem

        val merged = file.metadata + file.metadataOverrides
        val out = PipelineExecutor().run(
            config,
            PipelineExecutor.Input(originalFilename = file.name, metadata = merged),
        )
        val simulated = out.variables["title"]
        return simulated?.takeIf { it.isNotBlank() && !UNRESOLVED_TOKEN.containsMatchIn(it) }
            ?: rawTitle
            ?: filenameStem
    }

    private companion object {
        /** Detects "%name%" tokens that survived interpolation — the
         *  pipeline only substitutes vars that exist in the map; any
         *  reference to a missing key passes through verbatim. */
        private val UNRESOLVED_TOKEN = Regex("%[a-zA-Z_][a-zA-Z0-9_]*%")
    }
}
