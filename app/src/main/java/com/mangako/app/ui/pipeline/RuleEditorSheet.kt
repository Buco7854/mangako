package com.mangako.app.ui.pipeline

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
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
import com.mangako.app.domain.rule.Condition
import com.mangako.app.domain.rule.Rule

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RuleEditorSheet(
    rule: Rule,
    onDismiss: () -> Unit,
    onSave: (Rule) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var draft by remember(rule.id) { mutableStateOf(rule) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Text(rule.displayName(), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(12.dp))

            LabelField(
                value = draft.label.orEmpty(),
                onChange = { draft = draft.withMeta(label = it.ifBlank { null }) },
            )

            Spacer(Modifier.height(12.dp))
            when (val r = draft) {
                is Rule.ExtractXmlMetadata -> MappingEditor(r) { draft = it }
                is Rule.ExtractRegex -> ExtractRegexEditor(r) { draft = it }
                is Rule.RegexReplace -> RegexEditor(r) { draft = it }
                is Rule.StringAppend -> SingleTextEditor(
                    stringResource(R.string.editor_append_label), r.text,
                ) { draft = r.copy(text = it) }
                is Rule.StringPrepend -> SingleTextEditor(
                    stringResource(R.string.editor_prepend_label), r.text,
                ) { draft = r.copy(text = it) }
                is Rule.TagRelocator -> TagRelocatorEditor(r) { draft = it }
                is Rule.ConditionalFormat -> ConditionalEditor(r) { draft = it }
                is Rule.CleanWhitespace -> CleanWsEditor(r) { draft = it }
            }

            Spacer(Modifier.height(24.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.rule_cancel))
                }
                Button(onClick = { onSave(draft) }, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.rule_save))
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun LabelField(value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(stringResource(R.string.editor_label_optional)) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
    )
}

@Composable
private fun SingleTextEditor(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun RegexEditor(rule: Rule.RegexReplace, onChange: (Rule.RegexReplace) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(
            value = rule.pattern,
            onValueChange = { onChange(rule.copy(pattern = it)) },
            label = { Text(stringResource(R.string.editor_regex_pattern)) },
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = rule.replacement,
            onValueChange = { onChange(rule.copy(replacement = it)) },
            label = { Text(stringResource(R.string.editor_regex_replacement)) },
            modifier = Modifier.fillMaxWidth(),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = rule.ignoreCase,
                onCheckedChange = { onChange(rule.copy(ignoreCase = it)) },
            )
            Text(stringResource(R.string.editor_case_insensitive))
        }
    }
}

@Composable
private fun MappingEditor(rule: Rule.ExtractXmlMetadata, onChange: (Rule.ExtractXmlMetadata) -> Unit) {
    Text(
        stringResource(R.string.editor_xml_description),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(8.dp))
    rule.mappings.entries.forEachIndexed { _, (key, xml) ->
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = "%$key%",
                onValueChange = {},
                readOnly = true,
                modifier = Modifier.weight(1f),
                label = { Text(stringResource(R.string.editor_xml_mapping_variable)) },
            )
            OutlinedTextField(
                value = xml,
                onValueChange = { updated ->
                    onChange(rule.copy(mappings = rule.mappings + (key to updated)))
                },
                modifier = Modifier.weight(1f),
                label = { Text(stringResource(R.string.editor_xml_mapping_element)) },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TagRelocatorEditor(rule: Rule.TagRelocator, onChange: (Rule.TagRelocator) -> Unit) {
    var posExpanded by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(
            value = rule.pattern,
            onValueChange = { onChange(rule.copy(pattern = it)) },
            label = { Text(stringResource(R.string.editor_regex_pattern)) },
            modifier = Modifier.fillMaxWidth(),
        )
        ExposedDropdownMenuBox(expanded = posExpanded, onExpandedChange = { posExpanded = it }) {
            OutlinedTextField(
                value = rule.position.name,
                onValueChange = {},
                readOnly = true,
                label = { Text(stringResource(R.string.editor_relocator_move_to)) },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(posExpanded) },
                modifier = Modifier.menuAnchor().fillMaxWidth(),
            )
            DropdownMenu(expanded = posExpanded, onDismissRequest = { posExpanded = false }) {
                Rule.TagRelocator.Position.values().forEach { p ->
                    DropdownMenuItem(
                        text = { Text(p.name) },
                        onClick = { onChange(rule.copy(position = p)); posExpanded = false },
                    )
                }
            }
        }
        OutlinedTextField(
            value = rule.separator,
            onValueChange = { onChange(rule.copy(separator = it)) },
            label = { Text(stringResource(R.string.editor_relocator_separator)) },
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = rule.group.toString(),
            onValueChange = { s -> s.toIntOrNull()?.let { onChange(rule.copy(group = it)) } },
            label = { Text(stringResource(R.string.editor_relocator_group)) },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConditionalEditor(rule: Rule.ConditionalFormat, onChange: (Rule.ConditionalFormat) -> Unit) {
    val cond = rule.condition
    var opExpanded by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.editor_condition), style = MaterialTheme.typography.labelLarge)
        OutlinedTextField(
            value = cond.variable,
            onValueChange = { onChange(rule.copy(condition = cond.copy(variable = it))) },
            label = { Text(stringResource(R.string.editor_condition_variable)) },
            modifier = Modifier.fillMaxWidth(),
        )
        ExposedDropdownMenuBox(expanded = opExpanded, onExpandedChange = { opExpanded = it }) {
            OutlinedTextField(
                value = cond.op.name,
                onValueChange = {},
                readOnly = true,
                label = { Text(stringResource(R.string.editor_condition_operator)) },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(opExpanded) },
                modifier = Modifier.menuAnchor().fillMaxWidth(),
            )
            DropdownMenu(expanded = opExpanded, onDismissRequest = { opExpanded = false }) {
                Condition.Op.values().forEach { op ->
                    DropdownMenuItem(
                        text = { Text(op.name) },
                        onClick = { onChange(rule.copy(condition = cond.copy(op = op))); opExpanded = false },
                    )
                }
            }
        }
        if (cond.op != Condition.Op.IS_EMPTY && cond.op != Condition.Op.IS_NOT_EMPTY) {
            OutlinedTextField(
                value = cond.value,
                onValueChange = { onChange(rule.copy(condition = cond.copy(value = it))) },
                label = { Text(stringResource(R.string.editor_condition_value)) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = cond.ignoreCase,
                onCheckedChange = { onChange(rule.copy(condition = cond.copy(ignoreCase = it))) },
            )
            Text(stringResource(R.string.editor_case_insensitive))
        }
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.editor_conditional_nested_hint, rule.thenRules.size, rule.elseRules.size),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun CleanWsEditor(rule: Rule.CleanWhitespace, onChange: (Rule.CleanWhitespace) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(
            checked = rule.trim,
            onCheckedChange = { onChange(rule.copy(trim = it)) },
        )
        Text(stringResource(R.string.editor_cleanws_trim))
    }
}

@Composable
private fun ExtractRegexEditor(rule: Rule.ExtractRegex, onChange: (Rule.ExtractRegex) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            stringResource(R.string.editor_extract_description),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = rule.source,
                onValueChange = { onChange(rule.copy(source = it)) },
                label = { Text(stringResource(R.string.editor_extract_source)) },
                modifier = Modifier.weight(1f),
                singleLine = true,
            )
            OutlinedTextField(
                value = rule.target,
                onValueChange = { onChange(rule.copy(target = it)) },
                label = { Text(stringResource(R.string.editor_extract_target)) },
                modifier = Modifier.weight(1f),
                singleLine = true,
            )
        }
        OutlinedTextField(
            value = rule.pattern,
            onValueChange = { onChange(rule.copy(pattern = it)) },
            label = { Text(stringResource(R.string.editor_extract_pattern)) },
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = rule.group.toString(),
                onValueChange = { s -> s.toIntOrNull()?.let { onChange(rule.copy(group = it)) } },
                label = { Text(stringResource(R.string.editor_extract_group)) },
                modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = rule.defaultValue,
                onValueChange = { onChange(rule.copy(defaultValue = it)) },
                label = { Text(stringResource(R.string.editor_extract_default)) },
                modifier = Modifier.weight(1f),
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = rule.ignoreCase,
                onCheckedChange = { onChange(rule.copy(ignoreCase = it)) },
            )
            Text(stringResource(R.string.editor_case_insensitive))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = rule.onlyIfEmpty,
                onCheckedChange = { onChange(rule.copy(onlyIfEmpty = it)) },
            )
            Text(stringResource(R.string.editor_extract_only_if_empty, rule.target))
        }
    }
}
