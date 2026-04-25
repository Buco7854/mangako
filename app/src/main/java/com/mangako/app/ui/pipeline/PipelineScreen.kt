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
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DragHandle
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
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
fun PipelineScreen(
    onBack: () -> Unit = {},
    viewModel: PipelineViewModel = hiltViewModel(),
) {
    val state by viewModel.ui.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val ctx = LocalContext.current

    var showAdd by remember { mutableStateOf(false) }
    var showImport by remember { mutableStateOf(false) }
    var showOverflow by remember { mutableStateOf(false) }
    var editingRule by remember { mutableStateOf<Rule?>(null) }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbar.showSnackbar(it)
            viewModel.consumeMessage()
        }
    }

    val jsonCopiedMsg = stringResource(R.string.pipeline_json_copied)
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        viewModel.exportJson { json ->
            ctx.contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray()) }
            val msg = ctx.getString(R.string.pipeline_exported_to, uri.lastPathSegment.orEmpty())
            scope.launch { snackbar.showSnackbar(msg) }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        ctx.contentResolver.openInputStream(uri)?.use { input ->
            scope.launch { viewModel.importJson(input.bufferedReader().readText()) }
        }
    }

    val rules = state.config.rules
    val hasRules = rules.isNotEmpty()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.pipeline_title), fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Outlined.ArrowBack, stringResource(R.string.pipeline_back_cd))
                    }
                },
                actions = {
                    IconButton(onClick = { showOverflow = true }) {
                        Icon(Icons.Outlined.MoreVert, stringResource(R.string.pipeline_overflow_cd))
                    }
                    DropdownMenu(expanded = showOverflow, onDismissRequest = { showOverflow = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.pipeline_load_defaults_cd)) },
                            leadingIcon = { Icon(Icons.Outlined.AutoAwesome, null) },
                            onClick = {
                                showOverflow = false
                                viewModel.loadLanraragiDefaults()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.pipeline_import)) },
                            leadingIcon = { Icon(Icons.Outlined.FileUpload, null) },
                            onClick = {
                                showOverflow = false
                                showImport = true
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.pipeline_export)) },
                            leadingIcon = { Icon(Icons.Outlined.FileDownload, null) },
                            onClick = {
                                showOverflow = false
                                exportLauncher.launch("mangako-pipeline.json")
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.pipeline_copy_json)) },
                            leadingIcon = { Icon(Icons.Outlined.ContentCopy, null) },
                            onClick = {
                                showOverflow = false
                                viewModel.exportJson { json ->
                                    clipboard.setText(AnnotatedString(json))
                                    scope.launch { snackbar.showSnackbar(jsonCopiedMsg) }
                                }
                            },
                        )
                    }
                },
            )
        },
        floatingActionButton = {
            // Only show the FAB once the user has rules — when the list is
            // empty, the hero card's primary action already covers "add rules"
            // and a FAB on top of the hero is redundant noise.
            if (hasRules) {
                ExtendedFloatingActionButton(
                    onClick = { showAdd = true },
                    icon = { Icon(Icons.Outlined.Add, null) },
                    text = { Text(stringResource(R.string.pipeline_add_rule)) },
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { inner ->
        if (!hasRules) {
            // Empty pipeline — show only the hero card. No preview to show
            // (running a 0-rule pipeline returns the input unchanged).
            EmptyState(
                onLoadDefaults = viewModel::loadLanraragiDefaults,
                onAdd = { showAdd = true },
                modifier = Modifier.padding(inner).fillMaxSize(),
            )
        } else {
            val listState = rememberLazyListState()
            val reorder = rememberReorderableLazyListState(listState) { from, to ->
                // Subtract 2 because items 0 and 1 are the preview card and
                // the section header — only rule items participate in reorder.
                viewModel.move(from.index - 2, to.index - 2)
            }
            LazyColumn(
                state = listState,
                modifier = Modifier.padding(inner).fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item(key = "_preview", contentType = "preview") {
                    PreviewCard(
                        filename = state.preview.input,
                        final = state.previewedFinal,
                        vars = state.previewedVariables,
                        onFilenameChange = viewModel::updatePreviewInput,
                    )
                }
                item(key = "_steps_header", contentType = "header") {
                    SectionHeader(stringResource(R.string.pipeline_steps_header, rules.size))
                }
                items(rules, key = { it.id }, contentType = { "rule" }) { rule ->
                    ReorderableItem(reorder, key = rule.id) { dragging ->
                        RuleCard(
                            rule = rule,
                            dragging = dragging,
                            onToggle = { viewModel.toggleEnabled(rule.id) },
                            onEdit = { editingRule = rule },
                            onDelete = { viewModel.remove(rule.id) },
                            dragHandle = {
                                IconButton(modifier = Modifier.draggableHandle(), onClick = {}) {
                                    Icon(
                                        Icons.Outlined.DragHandle,
                                        contentDescription = stringResource(R.string.rule_reorder_cd),
                                    )
                                }
                            },
                        )
                    }
                }
            }
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
private fun PreviewCard(
    filename: String,
    final: String,
    vars: Map<String, String>,
    onFilenameChange: (String) -> Unit,
) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                stringResource(R.string.pipeline_live_preview),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
private fun SectionHeader(text: String) {
    Text(
        text,
        modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
    )
}
