package com.mangako.app.ui.inbox

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.mangako.app.data.history.HistoryRecord
import com.mangako.app.data.history.HistoryRepository
import com.mangako.app.data.pending.PendingFile
import com.mangako.app.data.pending.PendingRepository
import com.mangako.app.data.pending.PendingStatus
import com.mangako.app.data.pipeline.PipelineRepository
import com.mangako.app.data.settings.SettingsRepository
import com.mangako.app.domain.cbz.CbzProcessor
import com.mangako.app.domain.cbz.ThumbnailService
import com.mangako.app.domain.pipeline.PipelineEvaluation
import com.mangako.app.domain.pipeline.PipelineEvaluator
import com.mangako.app.domain.pipeline.PipelineInputBuilder
import com.mangako.app.domain.rule.PipelineConfig
import com.mangako.app.work.ProcessCbzWorker
import com.mangako.app.work.notify.Notifications
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.Collections
import javax.inject.Inject

@HiltViewModel
class InboxViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val pendingRepo: PendingRepository,
    settingsRepo: SettingsRepository,
    pipelineRepo: PipelineRepository,
    historyRepo: HistoryRepository,
    private val evaluator: PipelineEvaluator,
    /** Exposed so the [com.mangako.app.ui.inbox.CoverThumbnail] composable
     *  can request lazy on-demand thumbnails for visible cards. */
    val thumbnailService: ThumbnailService,
    private val cbzProcessor: CbzProcessor,
) : ViewModel() {

    /**
     * IDs of pending rows currently being backfilled. Stops the same row
     * being re-extracted by every Flow emission while a job's in flight.
     * Synchronized because it's read from the Flow collector and written
     * from coroutines launched into the viewModelScope.
     */
    private val backfilling: MutableSet<String> = Collections.synchronizedSet(HashSet())

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
         * Human title to display on the card. For Pending / Ignored rows
         * it's the pipeline-evaluated `%title%` (so the card shows what
         * Process *will* produce). For Processed rows it's reconstructed
         * from the audit trail of the run that actually uploaded — so
         * the card and the History detail screen always agree, even if
         * the user edits the pipeline afterwards.
         */
        val displayTitle: String,
        /**
         * The actually-uploaded filename for a Processed row, sourced
         * preferentially from the row's own `finalName` column and
         * falling back to the matching history record. Null when neither
         * source has the value.
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
        filter
            .flatMapLatest { f -> pendingRepo.observeByStatuses(f.statuses) }
            // Self-heal pending rows whose ComicInfo extraction failed at
            // detection time (cloud URI dropped bytes mid-stream, file
            // caught mid-write, older row predating reliable extraction).
            // Without this, the Inbox card title stays stuck on the raw
            // filename until the user taps Process — even though we could
            // re-read the .cbz right now. Runs once per row per process,
            // guarded by [backfilling] so the same row isn't re-extracted
            // by every Flow emission.
            .onEach { rows -> backfillMissingMetadata(rows) },
        pendingRepo.observeCounts(),
        settingsRepo.flow,
        pipelineRepo.flow,
        historyRepo.observe(),
    ) { items, statusCounts, settings, config, history ->
        // History indexed by original filename so Processed cards can
        // surface the title/final name that actually landed in
        // LANraragi — not a fresh re-simulation that could drift if the
        // user edits the pipeline afterwards.
        val historyByOriginal: Map<String, HistoryRecord> = history
            .asReversed() // observe() returns newest-first; reverse so newest wins the put.
            .associateBy { it.originalName }
        UiState(
            filter = filter.value,
            items = items.map { p -> buildItem(p, config, historyByOriginal[p.name]) },
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

    fun setFilter(f: Filter) { filter.value = f }

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

    /** Hard-delete a row — useful from the Processed view when the user
     *  doesn't ever want to see this file again. The on-disk .cbz is left
     *  alone; only Mangako's tracking row goes away. */
    fun forget(file: PendingFile) = viewModelScope.launch {
        pendingRepo.delete(file.id)
        Notifications.cancelDetected(context, file.id)
    }

    /** Persist edit-detection overrides + removals.
     *  - [overrides] win over what ExtractXmlMetadata reads from the file.
     *  - [removals] are pipeline-variable names the user removed; the
     *    worker drops these from pipeline input AND strips the matching
     *    elements from the .cbz's ComicInfo.xml.
     *  Pass empty collections to clear all edits. */
    fun saveOverrides(
        file: PendingFile,
        overrides: Map<String, String>,
        removals: Set<String> = emptySet(),
    ) = viewModelScope.launch {
        pendingRepo.setMetadataEdits(file.id, overrides, removals)
    }

    fun approveAll() = viewModelScope.launch { state.value.items.forEach { approve(it.file) } }
    fun rejectAll() = viewModelScope.launch { state.value.items.forEach { reject(it.file) } }

    /**
     * Re-extract ComicInfo for any pending rows whose stored detection
     * snapshot is empty. The detection-time pass via [DirectoryScanWorker]
     * handles the common case, but it can come up empty when the URI
     * drops bytes mid-stream or the file was caught mid-write — in which
     * case the Inbox simulator has nothing to feed the pipeline with.
     *
     * We re-read the .cbz here and write the result back to the row, so
     * the Flow re-emits and the card refreshes with a real title. The
     * [backfilling] guard means we only do this once per row per process
     * lifetime, not on every Flow emission.
     */
    private fun backfillMissingMetadata(rows: List<PendingFile>) {
        val targets = rows.filter { it.metadata.isEmpty() && backfilling.add(it.id) }
        if (targets.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            for (row in targets) {
                try {
                    val uri = runCatching { Uri.parse(row.uri) }.getOrNull() ?: continue
                    val info = runCatching {
                        cbzProcessor.extractDetectionFromUri(context, uri)
                    }.getOrNull() ?: continue
                    if (info.metadata.isEmpty()) continue
                    pendingRepo.updateDetectionInfo(
                        id = row.id,
                        metadata = info.metadata,
                        thumbnailPath = null,
                    )
                } finally {
                    backfilling.remove(row.id)
                }
            }
        }
    }

    private fun buildItem(file: PendingFile, config: PipelineConfig, historyRow: HistoryRecord?): InboxItem {
        val displayTitle = when (file.status) {
            // For files that already uploaded, reconstruct the title from
            // the audit trail of the run that landed — that's the string
            // the user saw in LANraragi and the History detail surfaces.
            // Fresh re-simulation would drift if rules have changed since.
            PendingStatus.APPROVED, PendingStatus.DONE ->
                historyRow?.let(::titleFromTrail) ?: simulateTitle(config, file)
            else -> simulateTitle(config, file)
        }
        return InboxItem(
            file = file,
            displayTitle = displayTitle,
            recordedFinal = file.finalName ?: historyRow?.finalName,
        )
    }

    /**
     * Dry-run today's pipeline against this row's detection metadata +
     * user overrides and read out `%title%`. Same input shape the worker
     * builds, so the card preview matches what Process will produce.
     *
     * Fallback chain (high → low):
     *   1. Pipeline-evaluated `%title%` (when clean)
     *   2. ComicInfo's <Title> overlaid by the user's override
     *   3. The detected filename minus `.cbz`
     *
     * "Clean" means the result is non-blank AND has no leftover
     * `%token%` literals (those slip through when a SetVariable rule
     * references a missing variable).
     *
     * If the user has cleared their pipeline, we skip the run entirely
     * and use the raw fallback — the card should reflect what their
     * pipeline produces, not silently substitute a default template
     * the user explicitly opted out of.
     */
    private fun simulateTitle(config: PipelineConfig, file: PendingFile): String {
        val titleOverride = file.metadataOverrides["title"]
        val titleRemoved = "title" in file.metadataRemovals
        val rawTitle = when {
            titleOverride != null -> titleOverride.takeIf { it.isNotBlank() }
            titleRemoved -> null
            else -> file.metadata["title"]?.takeIf { it.isNotBlank() }
        }
        val filenameStem = file.name.removeSuffix(".cbz")
        if (config.rules.isEmpty()) return rawTitle ?: filenameStem

        val input = PipelineInputBuilder.build(
            originalFilename = file.name,
            detectedMetadata = file.metadata,
            overrides = file.metadataOverrides,
            removals = file.metadataRemovals,
        )
        val evaluation = evaluator.evaluate(config, input)
        val simulated = evaluation["title"]
        return simulated?.takeIf { it.isNotBlank() && !UNRESOLVED_TOKEN.containsMatchIn(it) }
            ?: rawTitle
            ?: filenameStem
    }

    /**
     * Aggregate per-step variable deltas across the audit trail to
     * recover the final `%title%` the pipeline produced at upload time.
     * Same value the History detail screen surfaces in its Breakdown
     * card — using it here means Processed cards and History agree.
     */
    private fun titleFromTrail(record: HistoryRecord): String? {
        val vars = LinkedHashMap<String, String>()
        for (step in record.trail.steps) for ((k, v) in step.variablesAfter) vars[k] = v
        return vars["title"]?.takeIf { it.isNotBlank() && !UNRESOLVED_TOKEN.containsMatchIn(it) }
    }

    private companion object {
        /** Detects "%name%" tokens that survived interpolation — when a
         *  SetVariable references a missing variable the literal token
         *  comes through verbatim, and we'd rather show a sane fallback
         *  than a half-substituted template string. */
        private val UNRESOLVED_TOKEN = Regex("%[a-zA-Z_][a-zA-Z0-9_]*%")
    }
}

/** Thin helper used by the UI: pipe an [PipelineEvaluation] through this
 *  to get the "is the simulated title clean enough to show?" check the
 *  ViewModel applies internally. Exposed so dry-run previews on the
 *  Pipeline screen can apply the same definition of "clean". */
fun PipelineEvaluation.cleanTitle(): String? {
    val raw = this["title"] ?: return null
    return raw.takeIf { it.isNotBlank() && !Regex("%[a-zA-Z_][a-zA-Z0-9_]*%").containsMatchIn(it) }
}
