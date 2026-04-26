package com.mangako.app.ui.inbox

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mangako.app.R
import com.mangako.app.work.DirectoryScanWorker
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InboxScreen(
    onOpenSettings: () -> Unit = {},
    onOpenPipeline: () -> Unit = {},
    viewModel: InboxViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val scanningMsg = stringResource(R.string.inbox_scanning_started)
    val scanDisabledMsg = stringResource(R.string.inbox_scanning_disabled)

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

            if (state.items.isEmpty()) {
                EmptyInbox(filter = state.filter, modifier = Modifier.weight(1f))
                return@Scaffold
            }

            // The Process-all / Ignore-all bulk buttons only make sense in the
            // Pending view; in the Processed/Ignored views the per-card
            // 'Reprocess' / 'Forget' actions cover what users actually want.
            if (state.filter == InboxViewModel.Filter.PENDING) {
                BulkBar(
                    count = state.items.size,
                    onApproveAll = viewModel::approveAll,
                    onRejectAll = viewModel::rejectAll,
                )
            }

            LazyColumn(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(state.items, key = { it.file.id }) { item ->
                    InboxCard(
                        item = item,
                        filter = state.filter,
                        onApprove = { viewModel.approve(item.file) },
                        onReject = { viewModel.reject(item.file) },
                        onReprocess = { viewModel.reprocess(item.file) },
                        onForget = { viewModel.forget(item.file) },
                        onRename = { newName -> viewModel.setNameOverride(item.file, newName) },
                    )
                }
            }
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

@Composable
private fun InboxCard(
    item: InboxViewModel.InboxItem,
    filter: InboxViewModel.Filter,
    onApprove: () -> Unit,
    onReject: () -> Unit,
    onReprocess: () -> Unit,
    onForget: () -> Unit,
    onRename: (String?) -> Unit,
) {
    when (filter) {
        InboxViewModel.Filter.PENDING -> PendingCard(item, onApprove, onReject, onRename)
        InboxViewModel.Filter.PROCESSED -> ProcessedCard(item, onForget)
        InboxViewModel.Filter.IGNORED -> IgnoredCard(item, onReprocess, onForget)
    }
}

/** The full-detail card — rename preview, size, timestamp, decide buttons.
 *  Tapping the pencil opens a single-field dialog so the user can fix a
 *  bad detection before tapping Process; the edited name then becomes the
 *  pipeline's input filename. */
@Composable
private fun PendingCard(
    item: InboxViewModel.InboxItem,
    onApprove: () -> Unit,
    onReject: () -> Unit,
    onRename: (String?) -> Unit,
) {
    val file = item.file
    var editing by remember(file.id) { mutableStateOf(false) }
    // The displayed source name is the user's override when set,
    // otherwise the detected filename. Both flow through the same
    // pipeline preview, so the "Renames to" line always reflects what
    // Process will actually upload.
    val displayedName = file.nameOverride?.takeIf { it.isNotBlank() } ?: file.name

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    if (file.nameOverride?.isNotBlank() == true) {
                        Text(
                            stringResource(R.string.inbox_edited_label),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.height(2.dp))
                    }
                    Text(
                        displayedName,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        fontFamily = FontFamily.Monospace,
                    )
                    if (file.nameOverride?.isNotBlank() == true && file.nameOverride != file.name) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            stringResource(R.string.inbox_original_was, file.name),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }
                IconButton(onClick = { editing = true }) {
                    Icon(
                        Icons.Outlined.Edit,
                        contentDescription = stringResource(R.string.inbox_edit_name_cd),
                    )
                }
            }
            if (item.previewedFinal != displayedName) {
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.inbox_rename_to),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                RenameLine(item.previewedFinal)
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "${humanSize(file.sizeBytes)} · ${DateFormat.getDateTimeInstance().format(Date(file.detectedAt))}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
        EditNameDialog(
            initial = displayedName,
            hadOverride = !file.nameOverride.isNullOrBlank(),
            onDismiss = { editing = false },
            onSave = { newName ->
                editing = false
                onRename(newName)
            },
            onReset = {
                editing = false
                onRename(null)
            },
        )
    }
}

@Composable
private fun EditNameDialog(
    initial: String,
    hadOverride: Boolean,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
    onReset: () -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.inbox_edit_name_title)) },
        text = {
            Column {
                Text(
                    stringResource(R.string.inbox_edit_name_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = text.isNotBlank() && text != initial,
                onClick = { onSave(text.trim()) },
            ) {
                Text(stringResource(R.string.inbox_edit_name_save))
            }
        },
        dismissButton = {
            Row {
                if (hadOverride) {
                    TextButton(onClick = onReset) {
                        Text(stringResource(R.string.inbox_edit_name_reset))
                    }
                }
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.inbox_edit_name_cancel))
                }
            }
        },
    )
}

/**
 * Minimal view for already-processed files. The user wanted just the
 * before/after filenames — no size, no timestamp, no Reprocess button:
 * the source .cbz is typically deleted on success (deleteOnSuccess
 * defaults true), so 'Reprocess' would silently fail. Only Forget here.
 *
 * The 'after' filename comes from the row's own finalName column (set at
 * upload time) and falls back to the matching history record when the
 * column is null (legacy rows). Crucially we do NOT fall back to a
 * re-run of today's pipeline against the original name — the user
 * reported that mismatching the History detail, and a fresh pipeline
 * run after rule edits would lie about what actually landed on the
 * server.
 */
@Composable
private fun ProcessedCard(
    item: InboxViewModel.InboxItem,
    onForget: () -> Unit,
) {
    val file = item.file
    val finalName = item.recordedFinal
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    file.name,
                    style = if (finalName != null) MaterialTheme.typography.bodySmall
                    else MaterialTheme.typography.titleSmall,
                    fontWeight = if (finalName != null) FontWeight.Normal else FontWeight.SemiBold,
                    fontFamily = FontFamily.Monospace,
                    color = if (finalName != null) MaterialTheme.colorScheme.onSurfaceVariant
                    else MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (finalName != null) {
                    Spacer(Modifier.height(2.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.AutoMirrored.Outlined.ArrowForward,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            finalName,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
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

/** Ignored items keep both Reprocess + Forget — the on-disk file still
 *  exists (we don't touch a file on Ignore), so Reprocess is a real action. */
@Composable
private fun IgnoredCard(
    item: InboxViewModel.InboxItem,
    onReprocess: () -> Unit,
    onForget: () -> Unit,
) {
    val file = item.file
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                file.name,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                fontFamily = FontFamily.Monospace,
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onForget, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Outlined.Delete, null)
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.inbox_forget))
                }
                Button(onClick = onReprocess, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Outlined.Refresh, null)
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.inbox_reprocess))
                }
            }
        }
    }
}

@Composable
private fun RenameLine(target: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            Icons.AutoMirrored.Outlined.ArrowForward,
            contentDescription = null,
            modifier = Modifier.size(14.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.width(6.dp))
        Text(
            target,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
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
