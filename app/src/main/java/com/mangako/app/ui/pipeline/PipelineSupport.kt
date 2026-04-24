package com.mangako.app.ui.pipeline

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mangako.app.R

@Composable
fun EmptyState(
    onLoadDefaults: () -> Unit,
    onAdd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                stringResource(R.string.pipeline_empty_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                stringResource(R.string.pipeline_empty_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = onLoadDefaults) {
                    Icon(Icons.Outlined.AutoAwesome, null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.pipeline_lanraragi_standard))
                }
                OutlinedButton(onClick = onAdd) {
                    Icon(Icons.Outlined.Add, null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.pipeline_add_rule))
                }
            }
        }
    }
}

@Composable
fun AddRuleRow(onAdd: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(12.dp),
        horizontalArrangement = Arrangement.Center,
    ) {
        Button(onClick = onAdd) {
            Icon(Icons.Outlined.Add, null)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.pipeline_add_rule))
        }
    }
}

@Composable
fun AddRuleDialog(onDismiss: () -> Unit, onPick: (RuleKind) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.rule_cancel)) } },
        title = { Text(stringResource(R.string.dialog_add_rule_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                RuleKind.values().forEach { kind ->
                    ListItem(
                        headlineContent = { Text(stringResource(kind.labelRes)) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp)
                            .clickable { onPick(kind) },
                    )
                }
            }
        },
    )
}

@Composable
fun ImportDialog(
    onDismiss: () -> Unit,
    /**
     * Invoked when the user taps Import. We pass a callback so the caller can
     * signal success/failure back — only a success should dismiss the dialog,
     * otherwise the user loses whatever they pasted.
     */
    onPaste: (String, (Boolean) -> Unit) -> Unit,
    onFile: () -> Unit,
) {
    var text by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var errorShown by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                enabled = text.isNotBlank() && !busy,
                onClick = {
                    busy = true
                    errorShown = false
                    onPaste(text) { ok ->
                        busy = false
                        if (!ok) errorShown = true
                    }
                },
            ) {
                Text(stringResource(if (busy) R.string.dialog_import_button_busy else R.string.dialog_import_button))
            }
        },
        dismissButton = { TextButton(onClick = onFile) { Text(stringResource(R.string.dialog_import_pick_file)) } },
        title = { Text(stringResource(R.string.dialog_import_title)) },
        text = {
            Column {
                Text(stringResource(R.string.dialog_import_body), style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it; errorShown = false },
                    modifier = Modifier.fillMaxWidth().height(220.dp),
                    label = { Text(stringResource(R.string.dialog_import_json_label)) },
                    isError = errorShown,
                    supportingText = if (errorShown) {
                        { Text(stringResource(R.string.dialog_import_error)) }
                    } else null,
                )
            }
        },
    )
}
