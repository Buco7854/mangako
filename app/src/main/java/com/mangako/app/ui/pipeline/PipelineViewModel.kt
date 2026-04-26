package com.mangako.app.ui.pipeline

import android.content.Context
import android.net.Uri
import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Article
import androidx.compose.material.icons.automirrored.outlined.CallSplit
import androidx.compose.material.icons.automirrored.outlined.FormatListBulleted
import androidx.compose.material.icons.outlined.CleaningServices
import androidx.compose.material.icons.outlined.ContentPasteSearch
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FindReplace
import androidx.compose.material.icons.outlined.FormatQuote
import androidx.compose.material.icons.outlined.DriveFileRenameOutline
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mangako.app.R
import com.mangako.app.data.pipeline.PipelineRepository
import com.mangako.app.domain.cbz.CbzProcessor
import com.mangako.app.domain.pipeline.PipelineExecutor
import com.mangako.app.domain.rule.Condition
import com.mangako.app.domain.rule.PipelineConfig
import com.mangako.app.domain.rule.Rule
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

@HiltViewModel
class PipelineViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val repo: PipelineRepository,
    private val cbzProcessor: CbzProcessor,
) : ViewModel() {

    data class PreviewState(
        val input: String = "[Example] Chapter 1 🇺🇸.cbz",
        val sampleMetadata: Map<String, String> = mapOf(
            "title" to "Chapter 1",
            "series" to "Example Series",
            "writer" to "Author Name",
            "number" to "1",
            "genre" to "Manhwa",
        ),
        /** True while a real .cbz is being copied to cache + parsed. */
        val loading: Boolean = false,
        /**
         * Display name of the user-picked .cbz, so the preview bar can show
         * "from <filename>" instead of leaving the user wondering where the
         * sample metadata came from.
         */
        val sourceLabel: String? = null,
    )

    data class UiState(
        val config: PipelineConfig = PipelineConfig(),
        val preview: PreviewState = PreviewState(),
        val previewedFinal: String = "",
        val previewedVariables: Map<String, String> = emptyMap(),
        val message: String? = null,
    )

    private val preview = MutableStateFlow(PreviewState())
    private val message = MutableStateFlow<String?>(null)

    val ui: StateFlow<UiState> = combine(repo.flow, preview, message) { cfg, p, m ->
        val out = PipelineExecutor().run(
            cfg,
            PipelineExecutor.Input(originalFilename = p.input, metadata = p.sampleMetadata),
        )
        UiState(
            config = cfg,
            preview = p,
            previewedFinal = out.finalFilename,
            previewedVariables = out.variables.filterKeys { !it.startsWith("__") },
            message = m,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UiState())

    fun add(rule: Rule) = viewModelScope.launch {
        repo.update { it.copy(rules = it.rules + rule) }
    }

    fun remove(id: String) = viewModelScope.launch {
        repo.update { it.copy(rules = it.rules.filterNot { r -> r.id == id }) }
    }

    fun toggleEnabled(id: String) = viewModelScope.launch {
        repo.update { cfg ->
            cfg.copy(rules = cfg.rules.map { r -> if (r.id == id) r.withMeta(enabled = !r.enabled) else r })
        }
    }

    fun move(from: Int, to: Int) = viewModelScope.launch {
        repo.update { cfg ->
            val list = cfg.rules.toMutableList()
            if (from in list.indices && to in list.indices) {
                list.add(to, list.removeAt(from))
                cfg.copy(rules = list)
            } else cfg
        }
    }

    fun moveUp(id: String) = viewModelScope.launch {
        repo.update { cfg ->
            val idx = cfg.rules.indexOfFirst { it.id == id }
            if (idx <= 0) cfg
            else {
                val list = cfg.rules.toMutableList()
                list.add(idx - 1, list.removeAt(idx))
                cfg.copy(rules = list)
            }
        }
    }

    fun moveDown(id: String) = viewModelScope.launch {
        repo.update { cfg ->
            val idx = cfg.rules.indexOfFirst { it.id == id }
            if (idx < 0 || idx >= cfg.rules.size - 1) cfg
            else {
                val list = cfg.rules.toMutableList()
                list.add(idx + 1, list.removeAt(idx))
                cfg.copy(rules = list)
            }
        }
    }

    fun replace(id: String, updated: Rule) = viewModelScope.launch {
        repo.update { cfg ->
            cfg.copy(rules = cfg.rules.map { r -> if (r.id == id) updated else r })
        }
    }

    fun loadLanraragiDefaults() = viewModelScope.launch {
        repo.loadLanraragiStandard()
        message.value = appContext.getString(R.string.msg_defaults_loaded)
    }

    fun clear() = viewModelScope.launch {
        repo.replace(PipelineConfig())
    }

    fun updatePreviewInput(filename: String) {
        // Manual edit unties the preview from any previously-picked file —
        // the sample metadata reverts to the placeholder so users don't
        // wonder why their typed filename still seems to "know" %title%.
        preview.value = PreviewState(input = filename)
    }

    /**
     * Async dry-run: copies [uri] to a temp cache file (so [CbzProcessor] can
     * read it as a local zip), pulls its ComicInfo metadata, and updates the
     * preview state with the real filename + variables. The preview Flow
     * downstream automatically re-runs the pipeline against this new input.
     *
     * Falls back to the picked URI's display name with empty metadata if the
     * archive has no readable ComicInfo.xml.
     */
    fun previewFromUri(uri: Uri) = viewModelScope.launch {
        val displayName = DocumentFile.fromSingleUri(appContext, uri)?.name ?: "(picked file)"
        preview.value = preview.value.copy(loading = true, sourceLabel = displayName, input = displayName)

        val metadata = withContext(Dispatchers.IO) {
            val tempFile = File(appContext.cacheDir, "preview_${System.currentTimeMillis()}.cbz")
            try {
                appContext.contentResolver.openInputStream(uri)?.use { input ->
                    tempFile.outputStream().use { output -> input.copyTo(output) }
                }
                cbzProcessor.extractMetadata(tempFile)
            } catch (t: Throwable) {
                emptyMap()
            } finally {
                tempFile.delete()
            }
        }
        preview.value = PreviewState(
            input = displayName,
            sampleMetadata = metadata,
            loading = false,
            sourceLabel = displayName,
        )
    }

    /** Drop any user-picked file and revert to the canned sample preview. */
    fun resetPreview() {
        preview.value = PreviewState()
    }

    fun exportJson(onDone: (String) -> Unit) = viewModelScope.launch {
        onDone(repo.exportJson())
    }

    /**
     * Parses [raw] and swaps it in on success. Returns whether it succeeded so
     * the calling UI can decide whether to dismiss its dialog — we don't want
     * to throw away the user's pasted JSON on a parse error.
     */
    suspend fun importJson(raw: String): Result<PipelineConfig> {
        val result = repo.importJson(raw)
        result
            .onSuccess { message.value = appContext.getString(R.string.msg_imported, it.name, it.rules.size) }
            .onFailure { message.value = appContext.getString(R.string.msg_import_failed, it.message.orEmpty()) }
        return result
    }

    fun consumeMessage() { message.value = null }

    fun newRule(kind: RuleKind): Rule {
        val id = UUID.randomUUID().toString()
        return when (kind) {
            RuleKind.ExtractXml -> Rule.ExtractXmlMetadata(id = id)
            RuleKind.ExtractRegex -> Rule.ExtractRegex(
                id = id, source = "summary", target = "language", pattern = "",
            )
            RuleKind.SetVariable -> Rule.SetVariable(id = id, target = "title", value = "%series%")
            RuleKind.Regex -> Rule.RegexReplace(id = id, pattern = "", replacement = "")
            RuleKind.Group -> Rule.Group(id = id, label = "Group", rules = emptyList())
            RuleKind.WriteComicInfo -> Rule.WriteComicInfo(
                id = id,
                label = "Write to ComicInfo.xml",
                fields = mapOf("Title" to "%title%"),
            )
            RuleKind.Append -> Rule.StringAppend(id = id, text = "")
            RuleKind.Prepend -> Rule.StringPrepend(id = id, text = "")
            RuleKind.Relocator -> Rule.TagRelocator(id = id, pattern = "")
            RuleKind.Conditional -> Rule.ConditionalFormat(
                id = id,
                condition = Condition(variable = "genre", op = Condition.Op.CONTAINS, value = ""),
            )
            RuleKind.CleanWs -> Rule.CleanWhitespace(id = id)
        }
    }
}

enum class RuleKind(
    @StringRes val labelRes: Int,
    @StringRes val blurbRes: Int,
    val icon: ImageVector,
) {
    ExtractXml(R.string.rule_kind_extract_xml, R.string.rule_kind_extract_xml_blurb, Icons.AutoMirrored.Outlined.Article),
    ExtractRegex(R.string.rule_kind_extract_regex, R.string.rule_kind_extract_regex_blurb, Icons.Outlined.ContentPasteSearch),
    SetVariable(R.string.rule_kind_setvariable, R.string.rule_kind_setvariable_blurb, Icons.Outlined.DriveFileRenameOutline),
    Regex(R.string.rule_kind_regex, R.string.rule_kind_regex_blurb, Icons.Outlined.FindReplace),
    Append(R.string.rule_kind_append, R.string.rule_kind_append_blurb, Icons.Outlined.FormatQuote),
    Prepend(R.string.rule_kind_prepend, R.string.rule_kind_prepend_blurb, Icons.Outlined.FormatQuote),
    Relocator(R.string.rule_kind_relocator, R.string.rule_kind_relocator_blurb, Icons.Outlined.SwapHoriz),
    Conditional(R.string.rule_kind_conditional, R.string.rule_kind_conditional_blurb, Icons.AutoMirrored.Outlined.CallSplit),
    CleanWs(R.string.rule_kind_cleanws, R.string.rule_kind_cleanws_blurb, Icons.Outlined.CleaningServices),
    Group(R.string.rule_kind_group, R.string.rule_kind_group_blurb, Icons.Outlined.Folder),
    WriteComicInfo(R.string.rule_kind_writecomicinfo, R.string.rule_kind_writecomicinfo_blurb, Icons.Outlined.Edit),
}

/** The visual identity shown in rule cards, the picker, and the audit log. */
fun Rule.icon(): ImageVector = when (this) {
    is Rule.ExtractXmlMetadata -> Icons.AutoMirrored.Outlined.Article
    is Rule.ExtractRegex -> Icons.Outlined.ContentPasteSearch
    is Rule.SetVariable -> Icons.Outlined.DriveFileRenameOutline
    is Rule.RegexReplace -> Icons.Outlined.FindReplace
    is Rule.Group -> Icons.Outlined.Folder
    is Rule.WriteComicInfo -> Icons.Outlined.Edit
    is Rule.StringAppend -> Icons.Outlined.FormatQuote
    is Rule.StringPrepend -> Icons.Outlined.FormatQuote
    is Rule.TagRelocator -> Icons.Outlined.SwapHoriz
    is Rule.ConditionalFormat -> Icons.AutoMirrored.Outlined.CallSplit
    is Rule.CleanWhitespace -> Icons.Outlined.CleaningServices
}

