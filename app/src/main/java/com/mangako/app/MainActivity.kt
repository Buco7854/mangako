package com.mangako.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalFocusManager
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.mangako.app.data.pending.PendingRepository
import com.mangako.app.ui.history.HistoryDetailScreen
import com.mangako.app.ui.history.HistoryScreen
import com.mangako.app.ui.inbox.InboxScreen
import com.mangako.app.ui.pipeline.PipelineScreen
import com.mangako.app.ui.settings.SettingsScreen
import com.mangako.app.ui.theme.MangakoTheme
import com.mangako.app.work.notify.Notifications
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    // Android 13 (API 33) added a runtime-request requirement for POST_NOTIFICATIONS.
    // Without it the approval-via-notification flow silently doesn't work. We
    // don't treat denial as fatal — users can still approve from the Inbox tab
    // — but we ask once on launch to keep the golden path frictionless.
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* granted or not, either way we carry on */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Notifications.ensureChannels(this)
        requestNotificationPermissionIfNeeded()
        enableEdgeToEdge()
        setContent { MangakoTheme { MangakoRoot() } }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}

@HiltViewModel
class RootViewModel @Inject constructor(
    pendingRepo: PendingRepository,
) : ViewModel() {
    val pendingCount: Flow<Int> = pendingRepo.countPending()
}

private data class Dest(
    val route: String,
    val label: String,
    val icon: @Composable () -> Unit,
)

@Composable
private fun MangakoRoot(viewModel: RootViewModel = hiltViewModel()) {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route
    val pendingCount by viewModel.pendingCount.collectAsState(initial = 0)

    // Scan watched folders whenever the app comes back to the foreground.
    // SAF content-uri triggers don't fire when downloaders (Mihon, browsers,
    // etc.) write directly to the filesystem rather than through the
    // DocumentsProvider's API — which is the common case. The user's
    // mental model is "I downloaded a file, now I open Mangako and expect
    // to see it"; a scan on resume makes that mental model match reality.
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                com.mangako.app.work.DirectoryScanWorker.runIfWatching(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Single navigation function for any top-level tab destination.
    // Used by both the bottom-nav buttons and the Inbox setup-banner
    // links, so tapping "Open Settings" from the banner produces the
    // same back-stack shape as tapping the Settings tab — which in
    // turn lets the user tap Inbox again to come back.
    val navigateToTab: (String) -> Unit = { route ->
        nav.navigate(route) {
            popUpTo(nav.graph.findStartDestination().id) {
                saveState = true
            }
            launchSingleTop = true
            restoreState = true
        }
    }

    val destinations = listOf(
        Dest("inbox", stringResource(R.string.nav_inbox)) {
            BadgedBox(badge = {
                if (pendingCount > 0) Badge { Text(pendingCount.toString()) }
            }) { Icon(Icons.Outlined.Inbox, null) }
        },
        Dest("pipeline", stringResource(R.string.nav_pipeline)) { Icon(Icons.Outlined.Tune, null) },
        Dest("history", stringResource(R.string.nav_history)) { Icon(Icons.Outlined.History, null) },
        Dest("settings", stringResource(R.string.nav_settings)) { Icon(Icons.Outlined.Settings, null) },
    )

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        // Match the body and TopAppBar colour so the chrome reads as one
        // continuous surface — no visible band between the last card and the
        // navigation bar. Default Material3 NavigationBar layers a 3.dp tonal
        // tint on top of surfaceContainer, which made it visibly distinct
        // even though the underlying colour token was the same.
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        bottomBar = {
            val showBar = destinations.any { it.route == currentRoute }
            if (showBar) {
                val focus = LocalFocusManager.current
                NavigationBar(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                    tonalElevation = 0.dp,
                ) {
                    destinations.forEach { dest ->
                        val isSelected = backStack?.destination?.hierarchy?.any { it.route == dest.route } == true
                        NavigationBarItem(
                            selected = isSelected,
                            onClick = {
                                // Clear focus on every tab tap. Without this,
                                // tapping the bar while a TextField (e.g. the
                                // Settings URL field) was focused consumed the
                                // first tap as 'dismiss the IME / blur', and
                                // navigation only happened on the second tap.
                                focus.clearFocus()
                                // Reselecting the current tab is a no-op so we
                                // don't churn the back stack with redundant
                                // launchSingleTop entries.
                                if (isSelected) return@NavigationBarItem
                                navigateToTab(dest.route)
                            },
                            icon = dest.icon,
                            label = { Text(dest.label) },
                        )
                    }
                }
            }
        },
    ) { inner ->
        NavHost(
            navController = nav,
            startDestination = "inbox",
            modifier = Modifier.padding(inner),
        ) {
            composable("inbox") {
                InboxScreen(
                    onOpenSettings = { navigateToTab("settings") },
                    onOpenPipeline = { navigateToTab("pipeline") },
                )
            }
            composable("pipeline") { PipelineScreen() }
            composable("history") { HistoryScreen(onOpen = { id -> nav.navigate("history/$id") }) }
            composable("settings") { SettingsScreen() }
            composable("history/{id}") { entry ->
                val id = entry.arguments?.getString("id").orEmpty()
                HistoryDetailScreen(historyId = id, onBack = { nav.popBackStack() })
            }
        }
    }
}
