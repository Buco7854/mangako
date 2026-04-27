package com.mangako.app.ui.history

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.mangako.app.R
import com.mangako.app.data.history.HistoryRecord
import com.mangako.app.domain.pipeline.AuditStep
import com.mangako.app.domain.pipeline.AuditTrail
import com.mangako.app.ui.format.humanize
import java.io.File
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryDetailScreen(
    historyId: String,
    onBack: () -> Unit,
    viewModel: HistoryDetailViewModel = hiltViewModel(),
) {
    LaunchedEffect(historyId) { viewModel.load(historyId) }
    val record by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                colors = androidx.compose.material3.TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
                title = { Text(stringResource(R.string.history_detail_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.history_detail_back_cd))
                    }
                },
            )
        },
    ) { inner ->
        val rec = record
        if (rec == null) {
            Box(Modifier.padding(inner).fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    stringResource(R.string.history_loading),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 32.dp),
                )
            }
            return@Scaffold
        }
        LazyColumn(
            modifier = Modifier.padding(inner).fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item { Header(rec) }
            item { BreakdownCard(rec.trail) }
            item { SectionHeader(stringResource(R.string.history_section_rules)) }
            items(rec.trail.steps, key = { "${it.index}-${it.ruleId}" }) { step ->
                StepCard(step)
            }
            item { SectionHeader(stringResource(R.string.history_section_upload)) }
            item { FinalCard(rec.trail) }
        }
    }
}

@Composable
private fun Header(rec: HistoryRecord) {
    val trail = rec.trail
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Row(Modifier.padding(16.dp)) {
            DetailThumbnail(rec.thumbnailPath)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Label(stringResource(R.string.history_label_original))
                Mono(trail.sourceFile)
                Spacer(Modifier.height(6.dp))
                Label(stringResource(R.string.history_label_final))
                Mono(trail.finalName)
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(
                        R.string.history_ran_in,
                        DateFormat.getDateTimeInstance().format(Date(trail.finishedAt)),
                        trail.finishedAt - trail.startedAt,
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun DetailThumbnail(path: String?) {
    val ctx = LocalContext.current
    val file = path?.let { File(it).takeIf(File::exists) }
    Box(
        modifier = Modifier
            .size(width = 80.dp, height = 110.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surface),
        contentAlignment = Alignment.Center,
    ) {
        if (file != null) {
            AsyncImage(
                model = ImageRequest.Builder(ctx).data(file).build(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/** Card showing the variables the pipeline derived for this run, in
 *  build-template order. Lets the user see at a glance what each
 *  bracket-tag in the final filename came from. */
@Composable
private fun BreakdownCard(trail: AuditTrail) {
    val vars = trail.steps.lastOrNull()?.variablesAfter.orEmpty().toMutableMap()
    // Aggregate the per-step variable updates across the trail so we
    // get the final value for each (the last step in the trail only
    // carries that one step's updates, not the cumulative state).
    for (step in trail.steps) for ((k, v) in step.variablesAfter) vars[k] = v

    val ordered = listOf(
        "event" to "(C…)",
        "writer" to "[Writer]",
        "title" to "Title",
        "language" to "[Language]",
        "extra_tags" to "[…trailing]",
        "series" to "Series",
        "number" to "Number",
        "genre" to "Genre",
    ).mapNotNull { (k, label) -> vars[k]?.takeIf { it.isNotBlank() }?.let { Triple(k, label, it) } }
    if (ordered.isEmpty()) return
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                stringResource(R.string.history_section_breakdown),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(8.dp))
            ordered.forEach { (_, label, value) ->
                Row(
                    modifier = Modifier.padding(vertical = 4.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Text(
                        label,
                        modifier = Modifier.width(110.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        value,
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text,
        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
    )
}

@Composable
private fun StepCard(step: AuditStep) {
    val indent = (step.depth.coerceAtMost(4) * 16).dp
    val human = step.humanize()
    Card(
        modifier = Modifier.fillMaxWidth().padding(start = indent),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StepBadge(step)
                Spacer(Modifier.width(10.dp))
                Text(
                    stringResource(R.string.history_step_header, step.index + 1, human.title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    stringResource(R.string.history_step_duration_ms, step.durationMs),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                human.verdict,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (step.changed && !step.skipped) {
                Spacer(Modifier.height(8.dp))
                Mono(step.before)
                Text(" ↓ ", style = MaterialTheme.typography.bodySmall)
                Mono(step.after, accent = true)
            }
        }
    }
}

@Composable
private fun StepBadge(step: AuditStep) {
    val color = when {
        step.skipped -> Color(0xFF9E9E9E)
        step.changed -> MaterialTheme.colorScheme.primary
        else -> Color(0xFFB0BEC5)
    }
    Box(Modifier.size(10.dp).clip(CircleShape).background(color))
}

@Composable
private fun FinalCard(trail: AuditTrail) {
    val status = trail.uploadStatus
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (status.success) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.errorContainer,
        ),
    ) {
        Column(Modifier.padding(16.dp)) {
            val statusText = when {
                status.success -> stringResource(R.string.history_upload_success, status.httpCode ?: 200)
                status.httpCode != null -> stringResource(R.string.history_upload_fail_code, status.httpCode)
                else -> stringResource(R.string.history_upload_fail)
            }
            Text(statusText, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            status.message?.let {
                Spacer(Modifier.height(4.dp))
                Text(it, style = MaterialTheme.typography.bodySmall)
            }
            if (status.success) {
                Spacer(Modifier.height(6.dp))
                Text(
                    stringResource(
                        if (status.deletedSource) R.string.history_source_deleted
                        else R.string.history_source_kept,
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun Label(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun Mono(text: String, accent: Boolean = false) {
    val bg = if (accent) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.surface
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(bg)
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Text(
            text,
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
