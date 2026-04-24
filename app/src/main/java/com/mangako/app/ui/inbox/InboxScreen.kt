package com.mangako.app.ui.inbox

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import com.mangako.app.data.pending.PendingFile
import com.mangako.app.work.DirectoryScanWorker
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InboxScreen(viewModel: InboxViewModel = hiltViewModel()) {
    val items by viewModel.items.collectAsStateWithLifecycle()
    val ctx = LocalContext.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.nav_inbox), fontWeight = FontWeight.SemiBold) },
                actions = {
                    IconButton(onClick = { DirectoryScanWorker.runOnce(ctx) }) {
                        Icon(Icons.Outlined.Refresh, stringResource(R.string.inbox_scan_now_cd))
                    }
                },
            )
        },
    ) { inner ->
        Column(Modifier.padding(inner).fillMaxSize()) {
            if (items.isEmpty()) {
                EmptyInbox()
                return@Scaffold
            }
            BulkBar(
                count = items.size,
                onApproveAll = viewModel::approveAll,
                onRejectAll = viewModel::rejectAll,
            )
            LazyColumn(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(items, key = { it.id }) { file ->
                    PendingCard(
                        file = file,
                        onApprove = { viewModel.approve(file) },
                        onReject = { viewModel.reject(file) },
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyInbox() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                stringResource(R.string.inbox_empty_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                stringResource(R.string.inbox_empty_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
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
private fun PendingCard(
    file: PendingFile,
    onApprove: () -> Unit,
    onReject: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
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
            Spacer(Modifier.height(4.dp))
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
                FilledTonalButton(onClick = onApprove, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Outlined.Check, null)
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.inbox_process))
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
