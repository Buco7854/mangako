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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
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
    private val historyRepo: HistoryRepository,
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
     * Three buckets shown on the Inbox tab. PENDING and IGNORED come from
     * the pending-row table; PROCESSED is the audit-trail record from
     * [HistoryRepository] (so the tab acts as the History view too — that
     * tab no longer exists separately). All three lists are loaded eagerly
     * so the user can swipe between them without a flash of empty content.
     */
    enum class Filter {
        PENDING,
        PROCESSED,
        IGNORED,
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
        val pendingItems: List<InboxItem> = emptyList(),
        val processedRecords: List<HistoryRecord> = emptyList(),
        val ignoredItems: List<InboxItem> = emptyList(),
        val counts: Map<Filter, Int> = emptyMap(),
        val setup: SetupStatus = SetupStatus(false, false, false),
    )

    private val filter = MutableStateFlow(Filter.PENDING)
    val filterFlow: StateFlow<Filter> = filter.asStateFlow()

    val state: StateFlow<UiState> = combine(
        // Pending rows — re-extract metadata for any row whose detection-
        // time snapshot is empty (cloud URI dropped bytes mid-stream, file
        // caught mid-write). Without this the Inbox simulator has nothing
        // to feed the pipeline and the card stays stuck on the raw filename.
        pendingRepo.observeByStatuses(setOf(PendingStatus.PENDING))
            .onEach { rows -> backfillMissingMetadata(rows) },
        pendingRepo.observeByStatuses(setOf(PendingStatus.REJECTED)),
        // Processed = the audit-trail History (so the old History tab
        // collapses into this one). Newest-first thanks to the DAO order.
        historyRepo.observe(),
        pendingRepo.observeCounts(),
        combine(settingsRepo.flow, pipelineRepo.flow, filter) { s, p, f -> Triple(s, p, f) },
    ) { pending, ignored, history, statusCounts, (settings, config, currentFilter) ->
        UiState(
            filter = currentFilter,
            pendingItems = pending.map { buildItem(it, config) },
            processedRecords = history,
            ignoredItems = ignored.map { buildItem(it, config) },
            counts = mapOf(
                Filter.PENDING to (statusCounts[PendingStatus.PENDING] ?: 0),
                Filter.PROCESSED to history.size,
                Filter.IGNORED to (statusCounts[PendingStatus.REJECTED] ?: 0),
            ),
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

    /** Hard-delete a pending row (used by the Ignored view). The on-disk
     *  .cbz is left alone; only Mangako's tracking row goes away. */
    fun forget(file: PendingFile) = viewModelScope.launch {
        pendingRepo.delete(file.id)
        Notifications.cancelDetected(context, file.id)
    }

    /** Delete a single history record from the Processed view. The
     *  on-disk .cbz and the LANraragi-side archive are untouched; only
     *  Mangako's audit row disappears.
     *
     *  Also drop the matching DONE pending row so the watcher will
     *  re-detect the file on the next scan. Without this, the pending
     *  table keeps a (uri, sizeBytes) match in DONE state forever and
     *  [PendingRepository.addIfAbsent] silently treats every future
     *  rescan as a duplicate — the user expects "remove from Processed"
     *  to mean "stop ignoring", not "ignore forever without a record".
     *
     *  History ids minted in approval-mode runs are prefixed
     *  `pending:<pendingId>` (see [ProcessCbzWorker.stableHistoryId]),
     *  so we can recover the pending row id directly without a fuzzy
     *  filename join. AUTO-mode runs use an `auto:<sha1>` prefix and
     *  have no pending row to clean up, so they fall through. */
    fun forgetProcessed(id: String) = viewModelScope.launch {
        historyRepo.delete(id)
        if (id.startsWith(PENDING_HISTORY_PREFIX)) {
            pendingRepo.delete(id.removePrefix(PENDING_HISTORY_PREFIX))
        }
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

    fun approveAll() = viewModelScope.launch { state.value.pendingItems.forEach { approve(it.file) } }
    fun rejectAll() = viewModelScope.launch { state.value.pendingItems.forEach { reject(it.file) } }

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

    private fun buildItem(file: PendingFile, config: PipelineConfig): InboxItem {
        return InboxItem(
            file = file,
            displayTitle = simulateTitle(config, file),
            recordedFinal = file.finalName,
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

    private companion object {
        /** Detects "%name%" tokens that survived interpolation — when a
         *  SetVariable references a missing variable the literal token
         *  comes through verbatim, and we'd rather show a sane fallback
         *  than a half-substituted template string. */
        private val UNRESOLVED_TOKEN = Regex("%[a-zA-Z_][a-zA-Z0-9_]*%")

        /** Prefix prepended by [com.mangako.app.work.ProcessCbzWorker.stableHistoryId]
         *  to history rows that originated from an approval-mode pending
         *  row. Stripping it yields the original pending id. */
        private const val PENDING_HISTORY_PREFIX = "pending:"
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

/** Reconstruct the cleaned `%title%` value from a history record's audit
 *  trail — same string the user saw in LANraragi when the file landed.
 *  Used by the Processed card so the title shown there matches the History
 *  detail screen's Breakdown view. */
fun com.mangako.app.data.history.HistoryRecord.cleanedTitle(): String? {
    val vars = LinkedHashMap<String, String>()
    for (step in trail.steps) for ((k, v) in step.variablesAfter) vars[k] = v
    return vars["title"]?.takeIf {
        it.isNotBlank() && !Regex("%[a-zA-Z_][a-zA-Z0-9_]*%").containsMatchIn(it)
    }
}
