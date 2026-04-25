package com.mangako.app.ui.pipeline

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
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
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        ElevatedCard(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            colors = CardDefaults.elevatedCardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer,
            ),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(
                    Icons.Outlined.AutoAwesome,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    stringResource(R.string.pipeline_hero_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    stringResource(R.string.pipeline_hero_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = onLoadDefaults,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Outlined.AutoAwesome, null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.pipeline_hero_use_standard))
                }
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = onAdd, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Outlined.Add, null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.pipeline_hero_build_own))
                }
            }
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
                        supportingContent = { Text(kind.humanBlurb()) },
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

private fun RuleKind.humanBlurb(): String = when (this) {
    RuleKind.ExtractXml -> "Read Title, Series, Writer, etc. from ComicInfo.xml so other rules can use them."
    RuleKind.ExtractRegex -> "Pull a value out of one variable into another (e.g. language out of Summary)."
    RuleKind.Regex -> "Find text matching a pattern and replace it."
    RuleKind.Append -> "Add text to the end of the filename."
    RuleKind.Prepend -> "Add text to the start of the filename."
    RuleKind.Relocator -> "Move a tag like (C96) to the front or back of the filename."
    RuleKind.Conditional -> "Run different rules depending on a condition."
    RuleKind.CleanWs -> "Collapse double spaces and trim the ends."
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
