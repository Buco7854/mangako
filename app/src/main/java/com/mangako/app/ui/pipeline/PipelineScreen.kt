package com.mangako.app.ui.pipeline

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.DragHandle
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mangako.app.R
import com.mangako.app.domain.rule.Rule
import kotlinx.coroutines.launch
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PipelineScreen(viewModel: PipelineViewModel = hiltViewModel()) {
    val state by viewModel.ui.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val ctx = LocalContext.current

    var showAdd by remember { mutableStateOf(false) }
    var showImport by remember { mutableStateOf(false) }
    var editingRule by remember { mutableStateOf<Rule?>(null) }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbar.showSnackbar(it)
            viewModel.consumeMessage()
        }
    }

    val jsonCopiedMsg = stringResource(R.string.pipeline_json_copied)
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        viewModel.exportJson { json ->
            ctx.contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray()) }
            val msg = ctx.getString(R.string.pipeline_exported_to, uri.lastPathSegment.orEmpty())
            scope.launch { snackbar.showSnackbar(msg) }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        ctx.contentResolver.openInputStream(uri)?.use { input ->
            scope.launch { viewModel.importJson(input.bufferedReader().readText()) }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name), fontWeight = FontWeight.SemiBold) },
                actions = {
                    IconButton(onClick = { exportLauncher.launch("mangako-pipeline.json") }) {
                        Icon(Icons.Outlined.FileDownload, stringResource(R.string.pipeline_export))
                    }
                    IconButton(onClick = {
                        viewModel.exportJson { json ->
                            clipboard.setText(AnnotatedString(json))
                            scope.launch { snackbar.showSnackbar(jsonCopiedMsg) }
                        }
                    }) { Icon(Icons.Outlined.FileDownload, stringResource(R.string.pipeline_copy_json)) }
                    IconButton(onClick = { showImport = true }) {
                        Icon(Icons.Outlined.FileUpload, stringResource(R.string.pipeline_import))
                    }
                    IconButton(onClick = { viewModel.loadLanraragiDefaults() }) {
                        Icon(Icons.Outlined.AutoAwesome, stringResource(R.string.pipeline_load_defaults_cd))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { inner ->
        Column(Modifier.padding(inner).fillMaxSize()) {
            PreviewBar(
                filename = state.preview.input,
                final = state.previewedFinal,
                vars = state.previewedVariables,
                onFilenameChange = viewModel::updatePreviewInput,
            )
            RulesList(
                rules = state.config.rules,
                onMove = viewModel::move,
                onToggle = viewModel::toggleEnabled,
                onEdit = { editingRule = it },
                onDelete = viewModel::remove,
                onLoadDefaults = viewModel::loadLanraragiDefaults,
                onAdd = { showAdd = true },
                modifier = Modifier.weight(1f),
            )
            AddRuleRow(onAdd = { showAdd = true })
        }
    }

    if (showAdd) {
        AddRuleDialog(
            onDismiss = { showAdd = false },
            onPick = { kind ->
                showAdd = false
                val rule = viewModel.newRule(kind)
                viewModel.add(rule)
                editingRule = rule
            },
        )
    }

    if (showImport) {
        ImportDialog(
            onDismiss = { showImport = false },
            // Keep the dialog open on failure so the user doesn't lose their
            // pasted JSON — only close on a successful parse.
            onPaste = { raw, onResult ->
                scope.launch {
                    val ok = viewModel.importJson(raw).isSuccess
                    onResult(ok)
                    if (ok) showImport = false
                }
            },
            onFile = {
                showImport = false
                importLauncher.launch(arrayOf("application/json", "text/plain", "*/*"))
            },
        )
    }

    editingRule?.let { rule ->
        RuleEditorSheet(
            rule = rule,
            onDismiss = { editingRule = null },
            onSave = { updated ->
                viewModel.replace(rule.id, updated)
                editingRule = null
            },
        )
    }
}

@Composable
private fun PreviewBar(
    filename: String,
    final: String,
    vars: Map<String, String>,
    onFilenameChange: (String) -> Unit,
) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(stringResource(R.string.pipeline_live_preview), style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = filename,
                onValueChange = onFilenameChange,
                label = { Text(stringResource(R.string.pipeline_input_filename)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("→ ", style = MaterialTheme.typography.titleMedium)
                Text(
                    final.ifEmpty { stringResource(R.string.pipeline_empty_preview) },
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (vars.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    vars.entries.take(5).forEach { (k, v) ->
                        AssistChip(
                            onClick = {},
                            label = { Text("%$k%=${v.take(16)}") },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RulesList(
    rules: List<Rule>,
    onMove: (Int, Int) -> Unit,
    onToggle: (String) -> Unit,
    onEdit: (Rule) -> Unit,
    onDelete: (String) -> Unit,
    onLoadDefaults: () -> Unit,
    onAdd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (rules.isEmpty()) {
        EmptyState(onLoadDefaults, onAdd, modifier)
        return
    }
    val listState = rememberLazyListState()
    val reorder = rememberReorderableLazyListState(listState) { from, to ->
        onMove(from.index, to.index)
    }
    LazyColumn(
        state = listState,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier.fillMaxSize(),
    ) {
        items(rules, key = { it.id }) { rule ->
            ReorderableItem(reorder, key = rule.id) { dragging ->
                RuleCard(
                    rule = rule,
                    dragging = dragging,
                    onToggle = { onToggle(rule.id) },
                    onEdit = { onEdit(rule) },
                    onDelete = { onDelete(rule.id) },
                    dragHandle = {
                        IconButton(modifier = Modifier.draggableHandle(), onClick = {}) {
                            Icon(Icons.Outlined.DragHandle, contentDescription = stringResource(R.string.rule_reorder_cd))
                        }
                    },
                )
            }
        }
    }
}
