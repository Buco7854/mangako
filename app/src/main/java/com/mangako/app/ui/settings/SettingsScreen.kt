package com.mangako.app.ui.settings

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Surface
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.VisualTransformation
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mangako.app.R
import com.mangako.app.data.settings.SettingsRepository

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(viewModel: SettingsViewModel = hiltViewModel()) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val test by viewModel.testResult.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    val snackbar = remember { SnackbarHostState() }

    val pickFolder = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        ctx.contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        )
        viewModel.addFolder(uri.toString())
    }

    val okPrefix = stringResource(R.string.settings_test_ok_prefix)
    val failPrefix = stringResource(R.string.settings_test_fail_prefix)
    LaunchedEffect(test) {
        test?.let {
            snackbar.showSnackbar("${if (it.ok) okPrefix else failPrefix} ${it.message}")
            viewModel.consumeTestResult()
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        topBar = { TopAppBar(title = { Text(stringResource(R.string.settings_title), fontWeight = FontWeight.SemiBold) }) },
        snackbarHost = { SnackbarHost(snackbar) { Snackbar(it) } },
    ) { inner ->
        Column(
            modifier = Modifier
                .padding(inner)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
        ) {
            Section(stringResource(R.string.settings_section_server)) {
                DebouncedTextField(
                    initial = settings.lanraragiUrl,
                    onCommit = viewModel::setUrl,
                    label = stringResource(R.string.settings_base_url),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (isInsecureUrl(settings.lanraragiUrl)) {
                    Text(
                        stringResource(R.string.settings_insecure_warning),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                DebouncedTextField(
                    initial = settings.lanraragiApiKey,
                    onCommit = viewModel::setApiKey,
                    label = stringResource(R.string.settings_api_key),
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                    supportingText = {
                        Text(
                            stringResource(R.string.settings_api_key_hint),
                            style = MaterialTheme.typography.labelSmall,
                        )
                    },
                )
                OutlinedButton(onClick = viewModel::testConnection) {
                    Text(stringResource(R.string.settings_test_connection))
                }
            }

            Section(stringResource(R.string.settings_section_folders)) {
                Text(
                    stringResource(R.string.settings_folders_intro),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (settings.watchFolderUris.isEmpty()) {
                    Text(
                        stringResource(R.string.settings_folders_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    settings.watchFolderUris.forEach { uri ->
                        FolderRow(uri = uri, onRemove = { viewModel.removeFolder(uri) })
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { pickFolder.launch(null) }) {
                        Icon(Icons.Outlined.Add, null)
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.settings_add_folder))
                    }
                    OutlinedButton(
                        onClick = viewModel::scanNow,
                        enabled = settings.watchFolderUris.isNotEmpty(),
                    ) {
                        Icon(Icons.Outlined.PlayArrow, null)
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.settings_scan_now))
                    }
                }
            }

            Section(stringResource(R.string.settings_section_watcher)) {
                SwitchRow(
                    title = stringResource(R.string.settings_watcher_enabled),
                    subtitle = stringResource(R.string.settings_watcher_enabled_sub),
                    checked = settings.watcherEnabled,
                    onChange = viewModel::setWatcherEnabled,
                )
                HorizontalDivider()

                Text(stringResource(R.string.settings_mode_label), style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = settings.mode == SettingsRepository.Mode.APPROVAL,
                        onClick = { viewModel.setMode(SettingsRepository.Mode.APPROVAL) },
                        label = { Text(stringResource(R.string.settings_mode_approval)) },
                    )
                    FilterChip(
                        selected = settings.mode == SettingsRepository.Mode.AUTO,
                        onClick = { viewModel.setMode(SettingsRepository.Mode.AUTO) },
                        label = { Text(stringResource(R.string.settings_mode_auto)) },
                    )
                }
                Text(
                    stringResource(
                        when (settings.mode) {
                            SettingsRepository.Mode.APPROVAL -> R.string.settings_mode_approval_desc
                            SettingsRepository.Mode.AUTO -> R.string.settings_mode_auto_desc
                        },
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                HorizontalDivider()
                SwitchRow(
                    title = stringResource(R.string.settings_notify_enabled),
                    subtitle = stringResource(R.string.settings_notify_enabled_sub),
                    checked = settings.notifyOnDetected,
                    onChange = viewModel::setNotify,
                    enabled = settings.mode == SettingsRepository.Mode.APPROVAL,
                )
                HorizontalDivider()
                SwitchRow(
                    title = stringResource(R.string.settings_delete_enabled),
                    subtitle = stringResource(R.string.settings_delete_enabled_sub),
                    checked = settings.deleteOnSuccess,
                    onChange = viewModel::setDeleteOnSuccess,
                )
                HorizontalDivider()
                Text(
                    stringResource(R.string.settings_debounce_label, settings.debounceSeconds),
                    style = MaterialTheme.typography.labelLarge,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(1, 2, 5, 10).forEach { v ->
                        FilterChip(
                            selected = settings.debounceSeconds == v,
                            onClick = { viewModel.setDebounce(v) },
                            label = { Text(stringResource(R.string.settings_debounce_value, v)) },
                        )
                    }
                }
                Text(
                    stringResource(R.string.settings_debounce_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Section(stringResource(R.string.settings_section_realtime)) {
                RealtimeWatchingControl()
            }
        }
    }
}

/**
 * Surfaces the optional `MANAGE_EXTERNAL_STORAGE` ("All files access")
 * permission that lets the app run kernel-level FileObservers on the
 * watched folders' real filesystem paths — same trick Nextcloud uses for
 * auto-upload. When granted the app notices new .cbz files within a
 * second; without it we fall back to SAF + WorkManager content-uri
 * triggers + scan-on-resume + 15-min periodic.
 *
 * The permission isn't a runtime permission — Android sends the user to
 * a system Settings page and we observe the ON_RESUME after that page
 * closes to refresh our state.
 */
@Composable
private fun RealtimeWatchingControl() {
    val ctx = LocalContext.current
    val granted = rememberAllFilesAccessState()
    Text(
        stringResource(R.string.settings_realtime_intro),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(12.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        val statusText = if (granted) {
            stringResource(R.string.settings_realtime_status_on)
        } else {
            stringResource(R.string.settings_realtime_status_off)
        }
        Text(
            statusText,
            style = MaterialTheme.typography.titleSmall,
            color = if (granted) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        OutlinedButton(onClick = { openAllFilesAccessSettings(ctx) }) {
            Text(
                stringResource(
                    if (granted) R.string.settings_realtime_action_manage
                    else R.string.settings_realtime_action_grant,
                ),
            )
        }
    }
}

/**
 * Re-reads `Environment.isExternalStorageManager()` every time the screen
 * resumes — the user comes back here from the system Settings page where
 * they (in)granted the permission, and the regular Compose state hasn't
 * changed. Lifecycle observer is the cleanest hook for that.
 */
@Composable
private fun rememberAllFilesAccessState(): Boolean {
    var granted by remember { mutableStateOf(com.mangako.app.work.observer.canUseFileObserver()) }
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                granted = com.mangako.app.work.observer.canUseFileObserver()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    return granted
}

private fun openAllFilesAccessSettings(ctx: android.content.Context) {
    // ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION lands on the per-app
    // toggle page; ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION is the broad
    // list. Try app-specific first; if the OEM has stripped that intent,
    // fall back to the list page so the user still has a way through.
    val appSpecific = android.content.Intent(
        android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
        android.net.Uri.parse("package:${ctx.packageName}"),
    ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
    val fallback = android.content.Intent(
        android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION,
    ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)

    runCatching { ctx.startActivity(appSpecific) }
        .onFailure { runCatching { ctx.startActivity(fallback) } }
}

/**
 * A URL is "insecure" if it uses the plain http:// scheme AND the host looks
 * like a routable address (not localhost or RFC1918). LAN addresses are fine
 * because the user is their own adversary — but "http://lrr.example.com"
 * leaks the API key.
 */
private fun isInsecureUrl(url: String): Boolean {
    if (!url.startsWith("http://", ignoreCase = true)) return false
    val host = runCatching { java.net.URI(url).host }.getOrNull().orEmpty().lowercase()
    if (host.isBlank()) return false
    if (host == "localhost" || host == "127.0.0.1" || host == "::1") return false
    if (host.startsWith("10.") || host.startsWith("192.168.")) return false
    if (host.startsWith("172.")) {
        val second = host.removePrefix("172.").substringBefore('.').toIntOrNull()
        if (second != null && second in 16..31) return false
    }
    return true
}

/**
 * Mihon-style section: a primary-tinted, all-caps-ish label sitting directly
 * on the screen background — no per-section Card wrap. Rows below it sit
 * directly on the Scaffold body so the screen reads as one continuous
 * surface rather than a stack of nested cards-inside-cards.
 *
 * Compared to the previous Card-wrapping `Section`, this trims roughly 32dp
 * of nested card padding from each section and matches the Settings layouts
 * users get used to in apps like Mihon, Aniyomi, AnkiDroid, etc.
 */
@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 16.dp, bottom = 4.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            title,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
        )
        content()
    }
}

@Composable
private fun FolderRow(uri: String, onRemove: () -> Unit) {
    val context = LocalContext.current
    val display = remember(uri) {
        runCatching {
            androidx.documentfile.provider.DocumentFile.fromTreeUri(context, android.net.Uri.parse(uri))?.name
        }.getOrNull() ?: android.net.Uri.parse(uri).lastPathSegment ?: uri
    }
    // Custom Surface row instead of the flat ListItem, which had no rounded
    // corners and clashed with the rounded Section card it was nested in.
    // Sits one tonal step above the parent Section so it reads as an inset
    // chip rather than another full card.
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Outlined.FolderOpen,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    display,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    uri,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            IconButton(onClick = onRemove) {
                Icon(Icons.Outlined.Close, stringResource(R.string.settings_remove_folder_cd))
            }
        }
    }
}

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

/**
 * Settings text field that decouples its display state from the persistence
 * layer.
 *
 * The previous implementation bound the OutlinedTextField directly to
 * `settings.lanraragiUrl` (a StateFlow value driven by DataStore). Each
 * keystroke triggered an async DataStore write whose flow re-emit raced
 * the recomposition and yanked the cursor back to the start of the field.
 *
 * Local state is the source of truth for what's drawn. The persisted value
 * is observed only to *seed* the field once on first arrival — typically
 * the moment DataStore finishes its initial read after the screen opens.
 * After that, only the user's edits drive `local`, and we push to
 * [onCommit] after a typing pause so the round-trip never lands on the
 * typing hot path.
 */
@Composable
private fun DebouncedTextField(
    initial: String,
    onCommit: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    supportingText: @Composable (() -> Unit)? = null,
) {
    var local by rememberSaveable { mutableStateOf(initial) }
    // If the screen opened before DataStore had a chance to deliver the
    // saved value, `initial` starts empty and arrives later. Seed exactly
    // once on first non-empty arrival so the field reflects what's stored.
    var seeded by rememberSaveable { mutableStateOf(initial.isNotEmpty()) }

    LaunchedEffect(initial) {
        if (!seeded && initial.isNotEmpty()) {
            local = initial
            seeded = true
        }
    }

    LaunchedEffect(local) {
        // Debounce: only commit after the user pauses typing for 300ms.
        // Cancels-and-restarts on every keystroke because the LaunchedEffect
        // is keyed on `local`.
        delay(300)
        if (local != initial) onCommit(local)
    }

    OutlinedTextField(
        value = local,
        onValueChange = {
            local = it
            // Any explicit edit "claims" the field so a late DataStore
            // emission can't clobber the user's in-progress input.
            seeded = true
        },
        label = { Text(label) },
        modifier = modifier,
        singleLine = true,
        visualTransformation = visualTransformation,
        supportingText = supportingText,
    )
}
