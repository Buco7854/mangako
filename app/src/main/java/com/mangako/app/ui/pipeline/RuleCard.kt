package com.mangako.app.ui.pipeline

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DragHandle
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mangako.app.R
import com.mangako.app.domain.rule.Rule
import com.mangako.app.ui.format.humanSubtitle
import com.mangako.app.ui.format.humanTitle

/**
 * One card per rule in the pipeline list.
 *
 * Layout is a single header row: [icon] [title + subtitle] [switch] [⋮ or
 * drag handle]. The bottom-strip-with-state-text from the previous pass was
 * dropped — it cut the card off awkwardly without adding information that
 * the switch's own visual state didn't already carry.
 *
 * In normal mode the trailing slot is the ⋮ menu (Edit / Move up / Move
 * down / Delete). In reorder mode (toggled from the screen's TopAppBar) the
 * trailing slot becomes a drag handle and the menu/switch are hidden — that
 * gives accidental-drag-while-scrolling a hard stop and lets users opt in
 * when they actually want to reorder.
 */
@Composable
fun RuleCard(
    rule: Rule,
    isFirst: Boolean,
    isLast: Boolean,
    reorderMode: Boolean,
    onToggle: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    modifier: Modifier = Modifier,
    dragHandle: @Composable (() -> Unit)? = null,
) {
    var menuOpen by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    Card(
        modifier = modifier.fillMaxWidth(),
        // surfaceContainerHigh sits one tonal step above the screen background
        // (and the bottom navigation bar), so the cards have a clear edge
        // against the bar — the previous surfaceContainer was the same tone
        // as the nav bar and the two visually merged.
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .let { if (!reorderMode) it.clickable(onClick = onEdit) else it }
                .padding(start = 12.dp, end = 4.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconBadge(rule)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    rule.humanTitle(),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = if (rule.enabled) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    rule.humanSubtitle(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (reorderMode) {
                if (dragHandle != null) {
                    dragHandle()
                } else {
                    // Fallback for callers that haven't wired a real drag handle.
                    Icon(
                        Icons.Outlined.DragHandle,
                        contentDescription = stringResource(R.string.rule_reorder_cd),
                        modifier = Modifier.padding(8.dp),
                    )
                }
            } else {
                Switch(
                    checked = rule.enabled,
                    onCheckedChange = { onToggle() },
                )
                Spacer(Modifier.width(4.dp))
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Outlined.MoreVert, stringResource(R.string.rule_more_actions))
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.rule_edit_cd)) },
                        leadingIcon = { Icon(Icons.Outlined.Edit, null) },
                        onClick = { menuOpen = false; onEdit() },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.rule_move_up)) },
                        leadingIcon = { Icon(Icons.Outlined.ArrowUpward, null) },
                        enabled = !isFirst,
                        onClick = { menuOpen = false; onMoveUp() },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.rule_move_down)) },
                        leadingIcon = { Icon(Icons.Outlined.ArrowDownward, null) },
                        enabled = !isLast,
                        onClick = { menuOpen = false; onMoveDown() },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.rule_delete_cd)) },
                        leadingIcon = { Icon(Icons.Outlined.Delete, null) },
                        onClick = { menuOpen = false; confirmDelete = true },
                    )
                }
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.rule_delete_confirm_title)) },
            text = { Text(stringResource(R.string.rule_delete_confirm_body, rule.humanTitle())) },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; onDelete() }) {
                    Text(stringResource(R.string.rule_delete_cd))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) {
                    Text(stringResource(R.string.rule_cancel))
                }
            },
        )
    }
}

@Composable
private fun IconBadge(rule: Rule) {
    Surface(
        modifier = Modifier.size(40.dp).clip(CircleShape),
        color = if (rule.enabled) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surfaceContainerHighest,
        contentColor = if (rule.enabled) MaterialTheme.colorScheme.onPrimaryContainer
        else MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
        androidx.compose.foundation.layout.Box(
            modifier = Modifier.padding(8.dp),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = rule.icon(),
                contentDescription = null,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}
