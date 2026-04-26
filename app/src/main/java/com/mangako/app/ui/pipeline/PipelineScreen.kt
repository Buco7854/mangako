package com.mangako.app.ui.pipeline

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DragHandle
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.FileOpen
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Reorder
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
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
    var showOverflow by remember { mutableStateOf(false) }
    var showTrySheet by remember { mutableStateOf(false) }
    var editingRule by remember { mutableStateOf<Rule?>(null) }
    var reorderMode by remember { mutableStateOf(false) }

    // SAF picker for the dry-run "test with a real .cbz" button. The actual
    // metadata extraction is done in the ViewModel — we just hand it the URI.
    val pickCbzLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        uri?.let { viewModel.previewFromUri(it) }
    }

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
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        // Zero out the system-bar insets on this inner Scaffold. The OUTER
        // Scaffold (in MainActivity) already pads the NavHost area for its
        // own NavigationBar slot, which itself absorbs the system gesture
        // nav inset. Default contentWindowInsets here would inset the body's
        // bottom by the system nav bar a SECOND time, leaving a visible gap
        // between the last card and the bottom NavigationBar — the
        // 'pipeline floats above the bar' look users have been reporting.
        // The TopAppBar handles its own status-bar inset internally so we
        // can drop the top inset too.
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                colors = androidx.compose.material3.TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
                title = {
                    Text(
                        text = if (reorderMode) stringResource(R.string.pipeline_title_reorder)
                        else stringResource(R.string.pipeline_title),
                        fontWeight = FontWeight.SemiBold,
                    )
                },
                actions = {
                    if (reorderMode) {
                        // 'Done' is a primary action — give it a tinted text
                        // button so the way out of reorder mode is obvious.
                        IconButton(onClick = { reorderMode = false }) {
                            Icon(Icons.Outlined.Check, stringResource(R.string.pipeline_reorder_done))
                        }
                    } else {
                        if (hasRules) {
                            IconButton(onClick = { showTrySheet = true }) {
                                Icon(
                                    Icons.Outlined.PlayArrow,
                                    stringResource(R.string.pipeline_try_cd),
                                )
                            }
                        }
                        IconButton(onClick = { showOverflow = true }) {
                            Icon(Icons.Outlined.MoreVert, stringResource(R.string.pipeline_overflow_cd))
                        }
                        DropdownMenu(expanded = showOverflow, onDismissRequest = { showOverflow = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.pipeline_reorder_enter)) },
                                leadingIcon = { Icon(Icons.Outlined.Reorder, null) },
                                enabled = rules.size > 1,
                                onClick = {
                                    showOverflow = false
                                    reorderMode = true
                                },
                            )
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
                    }
                },
            )
        },
        floatingActionButton = {
            // FAB only when the user actually has rules and isn't in reorder
            // mode — adding a rule mid-reorder would muddy the UX, and on the
            // empty state the hero card already owns 'add rules'.
            if (hasRules && !reorderMode) {
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
            EmptyState(
                onLoadDefaults = viewModel::loadLanraragiDefaults,
                onAdd = { showAdd = true },
                modifier = Modifier.padding(inner).fillMaxSize(),
            )
        } else {
            if (reorderMode) {
                ReorderableRulesList(
                    rules = rules,
                    onMove = { from, to -> viewModel.move(from, to) },
                    modifier = Modifier.padding(inner).fillMaxSize(),
                )
            } else {
                LazyColumn(
                    modifier = Modifier.padding(inner).fillMaxSize(),
                    // No bottom padding — last card's bottom edge meets the
                    // bottom navigation bar's top edge, no dead band between.
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 0.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(rules, key = { it.id }) { rule ->
                        val idx = rules.indexOf(rule)
                        if (rule is Rule.SectionHeader) {
                            // Section headers render as flat labels — no
                            // card chrome — so they read as 'this is the
                            // beginning of a group of rules' rather than
                            // as another step.
                            SectionHeaderRow(
                                rule = rule,
                                isFirst = idx == 0,
                                isLast = idx == rules.size - 1,
                                onEdit = { editingRule = rule },
                                onDelete = { viewModel.remove(rule.id) },
                                onMoveUp = { viewModel.moveUp(rule.id) },
                                onMoveDown = { viewModel.moveDown(rule.id) },
                                modifier = Modifier.animateItem(
                                    fadeInSpec = null,
                                    fadeOutSpec = null,
                                ),
                            )
                        } else {
                            RuleCard(
                                rule = rule,
                                isFirst = idx == 0,
                                isLast = idx == rules.size - 1,
                                reorderMode = false,
                                onToggle = { viewModel.toggleEnabled(rule.id) },
                                onEdit = { editingRule = rule },
                                onDelete = { viewModel.remove(rule.id) },
                                onMoveUp = { viewModel.moveUp(rule.id) },
                                onMoveDown = { viewModel.moveDown(rule.id) },
                                modifier = Modifier.animateItem(
                                    fadeInSpec = null,
                                    fadeOutSpec = null,
                                ),
                            )
                        }
                    }
                }
            }
        }
    }

    if (showTrySheet) {
        TryPipelineSheet(
            filename = state.preview.input,
            final = state.previewedFinal,
            vars = state.previewedVariables,
            sourceLabel = state.preview.sourceLabel,
            loading = state.preview.loading,
            onDismiss = { showTrySheet = false },
            onFilenameChange = viewModel::updatePreviewInput,
            onPickFile = {
                pickCbzLauncher.launch(
                    arrayOf("application/octet-stream", "application/x-cbz", "application/zip", "*/*"),
                )
            },
            onResetPreview = viewModel::resetPreview,
        )
    }

    if (showAdd) {
        AddRuleSheet(
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

/**
 * Slim two-mode preview bar:
 *   - collapsed: a single line "Preview: input.cbz → final.cbz" so it doesn't
 *     dominate the screen when the user is scanning rules.
 *   - expanded: the editable input field + the variable chips, for users who
 *     are tuning a specific filename or want to see what %language% resolves to.
 *
 * Default is collapsed so the rules list — the actual product surface —
 * gets the visual weight.
 */
/**
 * Flat label row for [Rule.SectionHeader] entries in the pipeline list.
 * No Card chrome — the whole point is to read as a group divider, not
 * another step. Tap the title text to edit the label, ⋮ menu has
 * Move up / down / Delete (no Toggle since SectionHeaders have no
 * runtime effect).
 */
@Composable
private fun SectionHeaderRow(
    rule: Rule.SectionHeader,
    isFirst: Boolean,
    isLast: Boolean,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 16.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = rule.label?.takeIf { it.isNotBlank() } ?: stringResource(R.string.pipeline_section_default),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .weight(1f)
                .clickable(onClick = onEdit)
                .padding(vertical = 4.dp),
        )
        IconButton(onClick = { menuOpen = true }) {
            Icon(
                Icons.Outlined.MoreVert,
                contentDescription = stringResource(R.string.rule_more_actions),
            )
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.rule_move_up)) },
                enabled = !isFirst,
                onClick = { menuOpen = false; onMoveUp() },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.rule_move_down)) },
                enabled = !isLast,
                onClick = { menuOpen = false; onMoveDown() },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.rule_delete_cd)) },
                onClick = { menuOpen = false; onDelete() },
            )
        }
    }
}

/**
 * Try-pipeline sheet — opens from the TopAppBar's play icon.
 *
 * Replaces the previous always-visible preview bar at the top of the rules
 * list. The bar was always-visible by default but the dry-run button was
 * buried inside the expand interaction, so users never found it. Splitting
 * 'glance at the result' from 'I want to test rules right now' into a
 * dedicated sheet:
 *   - frees the rules list from competing visual weight
 *   - puts 'Pick a real .cbz' as a primary button on the surface that
 *     opens specifically to test things
 *   - keeps editable sample input + variable chips for users tweaking a
 *     specific filename without touching disk
 */
@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun TryPipelineSheet(
    filename: String,
    final: String,
    vars: Map<String, String>,
    sourceLabel: String?,
    loading: Boolean,
    onDismiss: () -> Unit,
    onFilenameChange: (String) -> Unit,
    onPickFile: () -> Unit,
    onResetPreview: () -> Unit,
) {
    val sheetState = androidx.compose.material3.rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
    )
    androidx.compose.material3.ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        contentWindowInsets = { WindowInsets.systemBars.union(WindowInsets.displayCutout) },
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp),
        ) {
            Text(
                stringResource(R.string.pipeline_try_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.pipeline_try_subtitle),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))

            // Primary action: pick a real .cbz. Lifted to the top of the
            // sheet so it's the first thing the user reaches for.
            androidx.compose.material3.Button(
                onClick = onPickFile,
                enabled = !loading,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Outlined.FileOpen, null)
                Spacer(Modifier.width(8.dp))
                Text(
                    if (loading) stringResource(R.string.pipeline_preview_loading)
                    else stringResource(R.string.pipeline_preview_pick_file),
                )
            }
            if (sourceLabel != null && !loading) {
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(R.string.pipeline_preview_source, sourceLabel),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    androidx.compose.material3.TextButton(onClick = onResetPreview) {
                        Text(stringResource(R.string.pipeline_preview_reset))
                    }
                }
            }

            Spacer(Modifier.height(20.dp))
            androidx.compose.material3.HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.height(20.dp))

            // Manual sample for users who want to type a hypothetical
            // filename without touching disk. Edits here detach from any
            // picked file (see ViewModel.updatePreviewInput).
            Text(
                stringResource(R.string.pipeline_try_sample_header),
                style = MaterialTheme.typography.labelLarge,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = filename,
                onValueChange = onFilenameChange,
                label = { Text(stringResource(R.string.pipeline_input_filename)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                enabled = !loading,
            )

            Spacer(Modifier.height(16.dp))
            Text(
                stringResource(R.string.pipeline_try_result_header),
                style = MaterialTheme.typography.labelLarge,
            )
            Spacer(Modifier.height(8.dp))
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
            ) {
                Text(
                    text = final.ifEmpty { stringResource(R.string.pipeline_empty_preview) },
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            if (vars.isNotEmpty()) {
                Spacer(Modifier.height(16.dp))
                Text(
                    stringResource(R.string.pipeline_try_vars_header),
                    style = MaterialTheme.typography.labelLarge,
                )
                Spacer(Modifier.height(8.dp))
                androidx.compose.foundation.layout.FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    vars.entries.take(8).forEach { (k, v) ->
                        AssistChip(
                            onClick = {},
                            label = { Text("%$k% = ${v.take(20)}") },
                        )
                    }
                }
            }
            Spacer(Modifier.height(20.dp))
        }
    }
}

/**
 * The reorder list. Backed by sh.calvin.reorderable's lazy state but the
 * drag handle is a `longPressDraggableHandle` rather than `draggableHandle`
 * — touching the icon does nothing until the user holds it, which is the
 * "less sensitive" gate the previous always-on drag was missing.
 */
@Composable
private fun ReorderableRulesList(
    rules: List<Rule>,
    onMove: (Int, Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val reorder = rememberReorderableLazyListState(listState) { from, to ->
        onMove(from.index, to.index)
    }
    LazyColumn(
        state = listState,
        modifier = modifier,
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 0.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(rules, key = { it.id }) { rule ->
            ReorderableItem(reorder, key = rule.id) { _ ->
                val idx = rules.indexOf(rule)
                RuleCard(
                    rule = rule,
                    isFirst = idx == 0,
                    isLast = idx == rules.size - 1,
                    reorderMode = true,
                    onToggle = {},
                    onEdit = {},
                    onDelete = {},
                    onMoveUp = {},
                    onMoveDown = {},
                    dragHandle = {
                        // longPressDraggableHandle requires intent — a quick
                        // accidental touch while scrolling is ignored.
                        Box(
                            modifier = Modifier
                                .padding(8.dp)
                                .longPressDraggableHandle(),
                        ) {
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

