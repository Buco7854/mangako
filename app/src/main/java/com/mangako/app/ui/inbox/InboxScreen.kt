package com.mangako.app.ui.inbox

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Book
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.mangako.app.domain.cbz.CbzProcessor
import com.mangako.app.domain.cbz.ThumbnailService
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.mangako.app.R
import com.mangako.app.data.history.HistoryRecord
import com.mangako.app.ui.common.FullscreenImageDialog
import com.mangako.app.work.DirectoryScanWorker
import java.io.File
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InboxScreen(
    onOpenSettings: () -> Unit = {},
    onOpenPipeline: () -> Unit = {},
    onOpenHistory: (String) -> Unit = {},
    viewModel: InboxViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val scanningMsg = stringResource(R.string.inbox_scanning_started)
    val scanDisabledMsg = stringResource(R.string.inbox_scanning_disabled)

    val filters = remember { InboxViewModel.Filter.values().toList() }
    val pagerState = rememberPagerState(initialPage = state.filter.ordinal) { filters.size }

    // Filter chip → pager: when the user taps a chip, animate-scroll to
    // that page. Only triggers when the chip selection precedes the swipe
    // (otherwise the launchSwipe→setFilter→animateScroll cycle would loop).
    LaunchedEffect(state.filter) {
        if (pagerState.currentPage != state.filter.ordinal) {
            pagerState.animateScrollToPage(state.filter.ordinal)
        }
    }
    // Pager → filter chip: when the swipe settles on a new page, sync the
    // chip. snapshotFlow + settledPage avoids fighting the chip → pager
    // path during an in-flight animation.
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.settledPage }.collect { page ->
            val newFilter = filters[page]
            if (newFilter != state.filter) viewModel.setFilter(newFilter)
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                colors = androidx.compose.material3.TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
                title = { Text(stringResource(R.string.nav_inbox), fontWeight = FontWeight.SemiBold) },
                actions = {
                    IconButton(onClick = {
                        if (!state.setup.foldersConfigured) {
                            scope.launch { snackbar.showSnackbar(scanDisabledMsg) }
                        } else {
                            DirectoryScanWorker.runOnce(ctx)
                            scope.launch { snackbar.showSnackbar(scanningMsg) }
                        }
                    }) {
                        Icon(Icons.Outlined.Refresh, stringResource(R.string.inbox_scan_now_cd))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { inner ->
        Column(Modifier.padding(inner).fillMaxSize()) {
            if (!state.setup.isComplete) {
                SetupBanner(
                    setup = state.setup,
                    onOpenSettings = onOpenSettings,
                    onOpenPipeline = onOpenPipeline,
                )
            }

            FilterChipRow(
                selected = state.filter,
                counts = state.counts,
                onSelect = viewModel::setFilter,
            )

            // The Process-all / Ignore-all bulk buttons only make sense in
            // the Pending view; in the Processed / Ignored views the
            // per-card actions cover what users actually want. Hidden by
            // a height-zero spacer rather than removed so the pager doesn't
            // shift y-position when the user swipes off Pending.
            if (state.filter == InboxViewModel.Filter.PENDING && state.pendingItems.isNotEmpty()) {
                BulkBar(
                    count = state.pendingItems.size,
                    onApproveAll = viewModel::approveAll,
                    onRejectAll = viewModel::rejectAll,
                )
            }

            HorizontalPager(
                state = pagerState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
            ) { page ->
                when (filters[page]) {
                    InboxViewModel.Filter.PENDING -> PendingPage(
                        items = state.pendingItems,
                        thumbnails = viewModel.thumbnailService,
                        onApprove = { viewModel.approve(it) },
                        onReject = { viewModel.reject(it) },
                        onSaveOverrides = { file, overrides, removals ->
                            viewModel.saveOverrides(file, overrides, removals)
                        },
                    )
                    InboxViewModel.Filter.PROCESSED -> ProcessedPage(
                        records = state.processedRecords,
                        onOpen = onOpenHistory,
                        onForget = viewModel::forgetProcessed,
                    )
                    InboxViewModel.Filter.IGNORED -> IgnoredPage(
                        items = state.ignoredItems,
                        thumbnails = viewModel.thumbnailService,
                        onForget = { viewModel.forget(it) },
                    )
                }
            }
        }
    }
}

@Composable
private fun PendingPage(
    items: List<InboxViewModel.InboxItem>,
    thumbnails: ThumbnailService,
    onApprove: (com.mangako.app.data.pending.PendingFile) -> Unit,
    onReject: (com.mangako.app.data.pending.PendingFile) -> Unit,
    onSaveOverrides: (com.mangako.app.data.pending.PendingFile, Map<String, String>, Set<String>) -> Unit,
) {
    if (items.isEmpty()) {
        EmptyInbox(InboxViewModel.Filter.PENDING)
        return
    }
    LazyColumn(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        items(items, key = { it.file.id }) { item ->
            PendingCard(
                item = item,
                thumbnails = thumbnails,
                onApprove = { onApprove(item.file) },
                onReject = { onReject(item.file) },
                onSaveOverrides = { ov, rm -> onSaveOverrides(item.file, ov, rm) },
            )
        }
    }
}

@Composable
private fun ProcessedPage(
    records: List<HistoryRecord>,
    onOpen: (String) -> Unit,
    onForget: (String) -> Unit,
) {
    if (records.isEmpty()) {
        EmptyInbox(InboxViewModel.Filter.PROCESSED)
        return
    }
    var preview by remember { mutableStateOf<File?>(null) }
    LazyColumn(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        items(records, key = { it.id }) { rec ->
            ProcessedHistoryCard(
                record = rec,
                onClick = { onOpen(rec.id) },
                onPreviewCover = { file -> preview = file },
                onForget = { onForget(rec.id) },
            )
        }
    }
    FullscreenImageDialog(file = preview, onDismiss = { preview = null })
}

@Composable
private fun IgnoredPage(
    items: List<InboxViewModel.InboxItem>,
    thumbnails: ThumbnailService,
    onForget: (com.mangako.app.data.pending.PendingFile) -> Unit,
) {
    if (items.isEmpty()) {
        EmptyInbox(InboxViewModel.Filter.IGNORED)
        return
    }
    LazyColumn(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        items(items, key = { it.file.id }) { item ->
            IgnoredCard(
                item = item,
                thumbnails = thumbnails,
                onForget = { onForget(item.file) },
            )
        }
    }
}

/**
 * Pending / Processed / Ignored chip row at the top of the Inbox. Each chip
 * carries the live count for its bucket so the user can scan inbox state at a
 * glance — same gesture as Mihon's library / updates filter rows.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FilterChipRow(
    selected: InboxViewModel.Filter,
    counts: Map<InboxViewModel.Filter, Int>,
    onSelect: (InboxViewModel.Filter) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        InboxViewModel.Filter.values().forEach { f ->
            val count = counts[f] ?: 0
            val labelRes = when (f) {
                InboxViewModel.Filter.PENDING -> R.string.inbox_filter_pending
                InboxViewModel.Filter.PROCESSED -> R.string.inbox_filter_processed
                InboxViewModel.Filter.IGNORED -> R.string.inbox_filter_ignored
            }
            FilterChip(
                selected = f == selected,
                onClick = { onSelect(f) },
                label = { Text("${stringResource(labelRes)} · $count") },
            )
        }
    }
}

@Composable
private fun SetupBanner(
    setup: InboxViewModel.SetupStatus,
    onOpenSettings: () -> Unit,
    onOpenPipeline: () -> Unit,
) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
        ),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                stringResource(R.string.inbox_setup_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.inbox_setup_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
            Spacer(Modifier.height(12.dp))
            // Each step is tappable when incomplete and routes the user to the
            // exact place that fixes it — Settings for server/folder, Pipeline
            // for rules. Cuts the "ok now where do I go?" hop.
            SetupStep(
                label = stringResource(R.string.inbox_setup_step_server),
                done = setup.serverConnected,
                onTap = onOpenSettings.takeUnless { setup.serverConnected },
            )
            SetupStep(
                label = stringResource(R.string.inbox_setup_step_folder),
                done = setup.foldersConfigured,
                onTap = onOpenSettings.takeUnless { setup.foldersConfigured },
            )
            SetupStep(
                label = stringResource(R.string.inbox_setup_step_rules),
                done = setup.rulesConfigured,
                onTap = onOpenPipeline.takeUnless { setup.rulesConfigured },
            )
        }
    }
}

@Composable
private fun SetupStep(label: String, done: Boolean, onTap: (() -> Unit)?) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .let { if (onTap != null) it.clickable(onClick = onTap) else it }
            .padding(vertical = 8.dp),
    ) {
        Icon(
            imageVector = if (done) Icons.Outlined.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
            contentDescription = null,
            tint = if (done) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            label,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onTertiaryContainer,
        )
    }
}

@Composable
private fun EmptyInbox(filter: InboxViewModel.Filter, modifier: Modifier = Modifier) {
    val (titleRes, subtitleRes) = when (filter) {
        InboxViewModel.Filter.PENDING -> R.string.inbox_empty_title to R.string.inbox_empty_subtitle
        InboxViewModel.Filter.PROCESSED -> R.string.inbox_empty_processed_title to R.string.inbox_empty_processed_subtitle
        InboxViewModel.Filter.IGNORED -> R.string.inbox_empty_ignored_title to R.string.inbox_empty_ignored_subtitle
    }
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                Icons.Outlined.Inbox,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(48.dp),
            )
            Spacer(Modifier.height(12.dp))
            Text(
                stringResource(titleRes),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(subtitleRes),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.padding(horizontal = 32.dp),
            )
        }
    }
}

@Composable
private fun BulkBar(count: Int, onApproveAll: () -> Unit, onRejectAll: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(R.string.inbox_pending_count, count),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.weight(1f),
        )
        OutlinedButton(onClick = onRejectAll) { Text(stringResource(R.string.inbox_ignore_all)) }
        Spacer(Modifier.width(8.dp))
        FilledTonalButton(onClick = onApproveAll) { Text(stringResource(R.string.inbox_process_all)) }
    }
}

/** The full-detail card — cover thumbnail + simulated title + raw
 *  filename + decide buttons. Tapping the pencil opens an edit sheet
 *  so the user can override pipeline-input variables (title, series,
 *  writer, language, event tag, trailing tags…). The simulated title
 *  is what the pipeline would write into ComicInfo if Process was
 *  tapped right now, so the card never lies about what's coming. */
@Composable
private fun PendingCard(
    item: InboxViewModel.InboxItem,
    thumbnails: ThumbnailService,
    onApprove: () -> Unit,
    onReject: () -> Unit,
    onSaveOverrides: (Map<String, String>, Set<String>) -> Unit,
) {
    val file = item.file
    var editing by remember(file.id) { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                CoverThumbnail(file.uri, file.sizeBytes, thumbnails)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    if (file.metadataOverrides.isNotEmpty() || file.metadataRemovals.isNotEmpty()) {
                        Text(
                            stringResource(R.string.inbox_edited_label),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.height(2.dp))
                    }
                    Text(
                        item.displayTitle,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        file.name,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "${humanSize(file.sizeBytes)} · ${DateFormat.getDateTimeInstance().format(Date(file.detectedAt))}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = { editing = true }) {
                    Icon(
                        Icons.Outlined.Edit,
                        contentDescription = stringResource(R.string.inbox_edit_name_cd),
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onReject, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Outlined.Close, null)
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.inbox_ignore))
                }
                Button(onClick = onApprove, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Outlined.Check, null)
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.inbox_process))
                }
            }
        }
    }

    if (editing) {
        EditDetectionSheet(
            detectedMetadata = file.metadata,
            initialOverrides = file.metadataOverrides,
            initialRemovals = file.metadataRemovals,
            onDismiss = { editing = false },
            onSave = { overrides, removals ->
                editing = false
                onSaveOverrides(overrides, removals)
            },
            onReset = {
                editing = false
                onSaveOverrides(emptyMap(), emptySet())
            },
        )
    }
}

/**
 * Square-ish cover preview. Asks [ThumbnailService] for the cover on
 * each composition: cached → instant; cache miss → background extract
 * from the .cbz at [uri], save, then render. This means the Inbox
 * never shows a placeholder when a .cbz exists at the URI — even
 * after the OS evicts cacheDir or the row predates thumbnail support.
 *
 * The fallback icon shows for the brief window before the first state
 * arrives, and as the terminal state when the .cbz is unreachable
 * (cloud-only URI offline, archive corrupted, etc.).
 */
@Composable
private fun CoverThumbnail(
    uri: String,
    sizeBytes: Long,
    thumbnails: ThumbnailService,
    modifier: Modifier = Modifier,
) {
    // Two-stage state: synchronous cache hit on the first frame so
    // re-entering the screen doesn't flash an empty box, then async
    // generation for the cache-miss path.
    val file by produceState<File?>(
        initialValue = thumbnails.cachedThumbnail(uri, sizeBytes),
        key1 = uri,
        key2 = sizeBytes,
    ) {
        value = thumbnails.thumbnailFor(android.net.Uri.parse(uri), sizeBytes)
    }
    var fullscreen by remember(uri, sizeBytes) { mutableStateOf(false) }
    val resolved = file
    Box(
        modifier = modifier
            .size(width = 56.dp, height = 80.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .let { if (resolved != null) it.clickable { fullscreen = true } else it },
        contentAlignment = Alignment.Center,
    ) {
        if (resolved != null) {
            AsyncImage(
                model = resolved,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Icon(
                Icons.Outlined.Book,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp),
            )
        }
    }
    if (fullscreen) {
        com.mangako.app.ui.common.FullscreenImageDialog(
            file = resolved,
            onDismiss = { fullscreen = false },
        )
    }
}

/**
 * Bottom sheet for editing the ComicInfo-shaped metadata that feeds
 * the pipeline. Shows ONLY the fields actually present in the .cbz
 * (plus any the user previously overrode or trashed in this row).
 * Empty standard fields don't auto-appear — the user adds them
 * deliberately via the "Add field" picker.
 *
 *   - Edit any value (treated as INPUT to the pipeline — overrides
 *     ExtractXmlMetadata's read). Blank values are preserved as
 *     "explicitly empty" rather than dropped, so a cleared title
 *     reaches the pipeline as %title% = "".
 *   - Tap the trash icon to remove a field. Custom / override-only
 *     fields just disappear from the list; fields that exist in the
 *     .cbz move to a "Will be removed from the file" section so the
 *     user can restore any they removed by accident.
 *   - Tap "Add ComicInfo field" to open a picker showing every
 *     ComicInfo element not currently in the list (Title, Series,
 *     Writer, Penciller, …, Summary). The sheet is a "virtual
 *     ComicInfo" the pipeline reads as input — pipeline-computed
 *     variables (event tag, trailing tags, etc.) are produced by
 *     upstream rules and are NOT user-editable here. The full
 *     allowlist lives in [CbzProcessor.COMIC_INFO_VARIABLES].
 *
 * On save we emit two structures:
 *   - `overrides`: the diff against detection (changed values + any
 *     blanks the user explicitly cleared, plus newly-added fields).
 *   - `removals`: pipeline-variable names the user trashed that
 *     existed in detection — the worker drops these from pipeline
 *     input AND strips the matching elements from the archive's
 *     ComicInfo.xml.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditDetectionSheet(
    detectedMetadata: Map<String, String>,
    initialOverrides: Map<String, String>,
    initialRemovals: Set<String>,
    onDismiss: () -> Unit,
    onSave: (Map<String, String>, Set<String>) -> Unit,
    onReset: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // The canonical ComicInfo schema + display labels live on
    // [CbzProcessor]. The Edit sheet's allowlist is just that list —
    // variables outside of it (legacy `%event%` / `%extra_tags%`
    // overrides from older builds, custom %vars% the user added) still
    // render so they can be cleared, but the picker only offers
    // ComicInfo fields + an explicit "Custom variable" entry.
    val standardKeys = CbzProcessor.COMIC_INFO_VARIABLES

    // Build the initial visible field list from what the .cbz actually
    // carries plus any override values the user already typed in (so a
    // blank explicit override survives reopening the sheet). Standard
    // fields without a value DO NOT auto-appear — the user adds them
    // through the picker. Sorted with standard keys in their canonical
    // order first, then any custom keys alphabetically — keeps the
    // sheet visually predictable without forcing empty schema slots
    // on the user.
    //
    // No `remember` keys: the parent gates this composable on `editing`
    // so a brand-new EditDetectionSheet is created every time the user
    // opens it. Keying on the input maps caused the field state to be
    // rebuilt mid-edit when the parent flow re-emitted the same
    // PendingFile (new map identities, identical content) — recreating
    // the underlying TextField state and snapping the IME cursor back
    // to position 0 on first focus, which is the bug the user hit.
    //
    // Values are TextFieldValue so the cursor + selection survive the
    // recompositions caused by IME insets, ModalBottomSheet height
    // animation, and LazyColumn item remeasure. With a plain String the
    // first focus tick can lose the selection and snap the caret to 0.
    val fields = remember {
        val merged = LinkedHashMap<String, String>()
        for ((k, v) in detectedMetadata) {
            if (k in initialRemovals) continue
            merged[k] = displayValue(k, initialOverrides[k] ?: v)
        }
        for ((k, v) in initialOverrides) {
            if (k in initialRemovals || k in merged) continue
            merged[k] = displayValue(k, v)
        }
        val ordered = LinkedHashMap<String, String>()
        for (k in standardKeys) merged[k]?.let { ordered[k] = it }
        for ((k, v) in merged) if (k !in standardKeys) ordered[k] = v
        ordered.entries
            .map { (k, v) ->
                mutableStateOf(k) to mutableStateOf(
                    TextFieldValue(text = v, selection = TextRange(v.length)),
                )
            }
            .toMutableStateList()
    }
    val removals = remember { initialRemovals.toMutableStateList() }
    // null = picker closed; PICKER = the dropdown is open;
    // CUSTOM = the user chose "Custom variable…" and is typing a name.
    var addingField by remember { mutableStateOf<AddFieldMode?>(null) }
    var newKey by remember { mutableStateOf("") }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        contentWindowInsets = {
            WindowInsets.systemBars.union(WindowInsets.displayCutout)
        },
    ) {
        // LazyColumn (not Column + verticalScroll): ModalBottomSheet's
        // nested-scroll handler integrates cleanly with LazyColumn but
        // sometimes mis-routes Column.verticalScroll gestures into a
        // sheet-drag, causing the sheet to dismiss when the user only
        // meant to scroll the content.
        LazyColumn(
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 20.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            item("header") {
                Column {
                    Text(
                        stringResource(R.string.inbox_edit_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        stringResource(R.string.inbox_edit_body),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(16.dp))
                }
            }

            itemsIndexed(
                items = fields,
                key = { _, (keyState, _) -> "field-${keyState.value}" },
            ) { index, (keyState, valueState) ->
                val key = keyState.value
                val isStandard = key in standardKeys
                val inFile = key in detectedMetadata
                val displayLabel = CbzProcessor.comicInfoLabelFor(key)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(top = 8.dp),
                ) {
                    Column(Modifier.weight(1f)) {
                        OutlinedTextField(
                            value = valueState.value,
                            onValueChange = { valueState.value = it },
                            label = { Text(displayLabel) },
                            singleLine = key != "summary",
                            modifier = Modifier.fillMaxWidth(),
                        )
                        // Annotate non-standard keys so the user knows this
                        // came from a custom variable, not the ComicInfo
                        // schema. Use a single consistent caption style for
                        // both this and the "in file" hint.
                        val captions = buildList {
                            if (!isStandard) add(stringResource(R.string.inbox_edit_field_custom_marker))
                            if (inFile) add(stringResource(R.string.inbox_edit_field_in_file))
                        }
                        if (captions.isNotEmpty()) {
                            Text(
                                captions.joinToString(" · "),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = 4.dp, top = 2.dp),
                            )
                        }
                    }
                    IconButton(
                        onClick = {
                            fields.removeAt(index)
                            // Custom fields simply vanish — nothing to strip
                            // from the file. Detected fields move to the
                            // removals set so the worker knows to delete the
                            // ComicInfo element on Process.
                            if (inFile) removals.add(key)
                        },
                    ) {
                        Icon(
                            Icons.Outlined.Delete,
                            contentDescription = stringResource(R.string.inbox_edit_field_remove_cd),
                        )
                    }
                }
            }

            // Show what's queued for stripping so the user has a chance to
            // undo before tapping Save. Restore puts the field back with its
            // detected value; the user can re-edit or re-remove from there.
            if (removals.isNotEmpty()) {
                item("removed-header") {
                    Column {
                        Spacer(Modifier.height(20.dp))
                        Text(
                            stringResource(R.string.inbox_edit_removed_section),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                items(
                    items = removals.toList(),
                    key = { "removed-$it" },
                ) { key ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(top = 4.dp),
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                CbzProcessor.comicInfoLabelFor(key),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                            )
                            Text(
                                stringResource(R.string.inbox_edit_removed_caption),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        TextButton(
                            onClick = {
                                removals.remove(key)
                                val restored = displayValue(key, detectedMetadata[key].orEmpty())
                                fields.add(
                                    mutableStateOf(key) to mutableStateOf(
                                        TextFieldValue(restored, TextRange(restored.length)),
                                    ),
                                )
                            },
                        ) { Text(stringResource(R.string.inbox_edit_field_restore)) }
                    }
                }
            }

            // "Add field" surface. The dropdown lists every ComicInfo field
            // that isn't already shown, plus a "Custom variable…" entry for
            // arbitrary pipeline variables (translator, mh:sourcemihon, …)
            // a custom rule reads. ComicInfo is the schema this sheet is
            // built around, so it's offered first; custom is the explicit
            // escape hatch.
            item("add-field") {
                Spacer(Modifier.height(12.dp))
                if (addingField == AddFieldMode.CUSTOM) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = newKey,
                            // Variable names allow letters, digits,
                            // underscore, and colon — ComicInfo extensions
                            // like <mh:SourceMihon> map to %mh:sourcemihon%
                            // so the colon must round-trip.
                            onValueChange = { newKey = it.filter { c -> c.isLetterOrDigit() || c == '_' || c == ':' } },
                            label = { Text(stringResource(R.string.inbox_edit_field_new_key_label)) },
                            placeholder = { Text(stringResource(R.string.inbox_edit_field_new_key_placeholder)) },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(8.dp))
                        TextButton(
                            enabled = newKey.isNotBlank() && fields.none { it.first.value == newKey },
                            onClick = {
                                removals.remove(newKey)
                                fields.add(
                                    mutableStateOf(newKey) to
                                        mutableStateOf(TextFieldValue("")),
                                )
                                newKey = ""
                                addingField = null
                            },
                        ) { Text(stringResource(R.string.inbox_edit_field_add_confirm)) }
                    }
                } else {
                    val visibleKeys = fields.map { it.first.value }.toSet()
                    val available = standardKeys.filter { it !in visibleKeys }
                    Box {
                        TextButton(onClick = { addingField = AddFieldMode.PICKER }) {
                            Icon(Icons.Outlined.Add, null)
                            Spacer(Modifier.width(4.dp))
                            Text(stringResource(R.string.inbox_edit_field_add))
                        }
                        DropdownMenu(
                            expanded = addingField == AddFieldMode.PICKER,
                            onDismissRequest = { addingField = null },
                        ) {
                            available.forEach { key ->
                                DropdownMenuItem(
                                    text = { Text(CbzProcessor.comicInfoLabelFor(key)) },
                                    onClick = {
                                        // Re-adding a previously-removed key
                                        // cancels its pending removal.
                                        removals.remove(key)
                                        fields.add(
                                            mutableStateOf(key) to
                                                mutableStateOf(TextFieldValue("")),
                                        )
                                        addingField = null
                                    },
                                )
                            }
                            if (available.isNotEmpty()) HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.inbox_edit_field_add_custom)) },
                                onClick = {
                                    addingField = AddFieldMode.CUSTOM
                                    newKey = ""
                                },
                            )
                        }
                    }
                }
            }

            item("actions") {
                Spacer(Modifier.height(20.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (initialOverrides.isNotEmpty() || initialRemovals.isNotEmpty()) {
                        TextButton(onClick = onReset) {
                            Text(stringResource(R.string.inbox_edit_reset))
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    OutlinedButton(onClick = onDismiss) {
                        Text(stringResource(R.string.inbox_edit_cancel))
                    }
                    Button(
                        onClick = {
                            // Build the override map by diffing the current
                            // field values against detection. A field that
                            // still matches detection is implicit (no
                            // override); a changed value — including a
                            // deliberately blank one — becomes an explicit
                            // override entry so the worker treats it as
                            // pipeline input.
                            val overrides = LinkedHashMap<String, String>()
                            for ((keyState, valueState) in fields) {
                                val key = keyState.value
                                if (key.isBlank()) continue
                                val raw = valueState.value.text
                                // Keep the user's exact string — only strip
                                // trailing newlines from any keyboard slips.
                                // The repo handles extra_tags leading-space
                                // normalisation centrally on save.
                                val cleaned = raw.trimEnd('\n', '\r')
                                val detected = detectedMetadata[key].orEmpty()
                                if (cleaned == displayValue(key, detected)) continue
                                overrides[key] = cleaned
                            }
                            onSave(overrides, removals.toSet())
                        },
                    ) {
                        Text(stringResource(R.string.inbox_edit_save))
                    }
                }
            }
        }
    }
}

/** Strip the leading-space convention off %extra_tags% so the
 *  user sees a clean "[Decensored]" rather than " [Decensored]".
 *  Persistence re-adds the space at save time via the repository. */
private fun displayValue(key: String, raw: String): String =
    if (key == "extra_tags" && raw.startsWith(" ")) raw.trimStart() else raw


/**
 * Processed-tab card backed by an audit-trail [HistoryRecord]. The whole
 * card is clickable — tapping opens the History detail screen with the
 * full per-step breakdown that used to live behind the (now-removed)
 * History tab. The cover thumbnail is its own click target so a tap there
 * opens the full-screen preview without navigating away.
 *
 * Status is shown as a leading colored stripe (Mihon-style) instead of
 * the old little dot — easier to scan from a distance and uses the
 * Material colour roles instead of hard-coded hex.
 */
@Composable
private fun ProcessedHistoryCard(
    record: HistoryRecord,
    onClick: () -> Unit,
    onPreviewCover: (File) -> Unit,
    onForget: () -> Unit,
) {
    val cleanTitle = record.cleanedTitle()
        ?.takeIf { it.isNotBlank() }
        ?: record.finalName.removeSuffix(".cbz")
    val stripeColor = if (record.success) MaterialTheme.colorScheme.primary
    else MaterialTheme.colorScheme.error
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Row(modifier = Modifier.height(IntrinsicSize.Min)) {
            // Status stripe — full card height, low-key but always visible.
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .fillMaxHeight()
                    .background(stripeColor),
            )
            Row(
                modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 12.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ProcessedThumbnail(record.thumbnailPath, onTap = onPreviewCover)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        cleanTitle,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        record.originalName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(4.dp))
                    val suffix = record.httpCode?.let { " · HTTP $it" }.orEmpty()
                    Text(
                        relativeTime(record.createdAt) + suffix,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onForget) {
                    Icon(
                        Icons.Outlined.Delete,
                        contentDescription = stringResource(R.string.inbox_forget),
                    )
                }
            }
        }
    }
}

/** Cover preview for the Processed view — taps open full-screen, missing
 *  files fall back to a neutral icon. Mirrors the History thumbnail used
 *  on the (now-removed) History list so the visual stays consistent. */
@Composable
private fun ProcessedThumbnail(path: String?, onTap: (File) -> Unit) {
    val file = path?.let { File(it).takeIf(File::exists) }
    Box(
        modifier = Modifier
            .size(width = 48.dp, height = 64.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .let { if (file != null) it.clickable { onTap(file) } else it },
        contentAlignment = Alignment.Center,
    ) {
        if (file != null) {
            AsyncImage(
                model = file,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Icon(
                Icons.Outlined.Book,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/**
 * Cheap relative-time formatter for the Processed card timestamps —
 * "just now", "12m ago", "3h ago", "yesterday", "Apr 12". A relative
 * label gives a faster glance-readable signal than the absolute datetime
 * the History list used to show, and matches what most modern Android
 * inbox UIs (Mihon Updates, Gmail, etc.) do.
 */
private fun relativeTime(epochMs: Long): String {
    val now = System.currentTimeMillis()
    val diff = now - epochMs
    return when {
        diff < 60_000 -> "just now"
        diff < 60 * 60_000 -> "${diff / 60_000}m ago"
        diff < 24 * 60 * 60_000 -> "${diff / (60 * 60_000)}h ago"
        diff < 7L * 24 * 60 * 60_000 -> "${diff / (24 * 60 * 60_000)}d ago"
        else -> DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(epochMs))
    }
}

/** Ignored items get a Forget action only — the on-disk file is left
 *  alone; Forget just clears Mangako's tracking row. */
@Composable
private fun IgnoredCard(
    item: InboxViewModel.InboxItem,
    thumbnails: ThumbnailService,
    onForget: () -> Unit,
) {
    val file = item.file
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                CoverThumbnail(file.uri, file.sizeBytes, thumbnails)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        item.displayTitle,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        file.name,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            OutlinedButton(onClick = onForget, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Outlined.Delete, null)
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.inbox_forget))
            }
        }
    }
}

private fun humanSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return "%.1f KB".format(kb)
    val mb = kb / 1024.0
    if (mb < 1024) return "%.1f MB".format(mb)
    return "%.2f GB".format(mb / 1024.0)
}

/** State machine for the [EditDetectionSheet] "Add field" surface.
 *  PICKER = the dropdown menu of ComicInfo fields + Custom is open.
 *  CUSTOM = the user picked Custom and is typing a free-form variable name. */
private enum class AddFieldMode { PICKER, CUSTOM }

