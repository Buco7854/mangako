package com.mangako.app.ui.inbox

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.Refresh
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
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
            )
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(subtitleRes),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
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
            if (item.previewedFinal != file.name) {
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.inbox_rename_to),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.AutoMirrored.Outlined.ArrowForward,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        item.previewedFinal,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "${humanSize(file.sizeBytes)} · ${DateFormat.getDateTimeInstance().format(Date(file.detectedAt))}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            // The button row swaps shape per-filter:
            //  - Pending  → [ Ignore ] [ Process ]   (the daily decision)
            //  - Processed/Ignored → [ Forget ] [ Reprocess ]  (revisit a past
            //    decision; Reprocess just resets the row to PENDING so the
            //    user can re-decide via the same Approve flow)
            when (filter) {
                InboxViewModel.Filter.PENDING -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
                InboxViewModel.Filter.PROCESSED, InboxViewModel.Filter.IGNORED ->
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
}

private fun humanSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return "%.1f KB".format(kb)
    val mb = kb / 1024.0
    if (mb < 1024) return "%.1f MB".format(mb)
    return "%.2f GB".format(mb / 1024.0)
}
