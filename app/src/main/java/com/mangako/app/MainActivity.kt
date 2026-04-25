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
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
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

    val destinations = listOf(
        Dest("inbox", stringResource(R.string.nav_inbox)) {
            BadgedBox(badge = {
                if (pendingCount > 0) Badge { Text(pendingCount.toString()) }
            }) { Icon(Icons.Outlined.Inbox, null) }
        },
        Dest("history", stringResource(R.string.nav_history)) { Icon(Icons.Outlined.History, null) },
        Dest("settings", stringResource(R.string.nav_settings)) { Icon(Icons.Outlined.Settings, null) },
    )

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        bottomBar = {
            val showBar = destinations.any { it.route == currentRoute }
            if (showBar) {
                NavigationBar {
                    destinations.forEach { dest ->
                        NavigationBarItem(
                            selected = backStack?.destination?.hierarchy?.any { it.route == dest.route } == true,
                            onClick = {
                                nav.navigate(dest.route) {
                                    popUpTo(nav.graph.startDestinationId) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
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
                InboxScreen(onOpenSettings = { nav.navigate("settings") })
            }
            composable("history") { HistoryScreen(onOpen = { id -> nav.navigate("history/$id") }) }
            composable("settings") {
                SettingsScreen(onOpenPipeline = { nav.navigate("settings/pipeline") })
            }
            composable("settings/pipeline") {
                PipelineScreen(onBack = { nav.popBackStack() })
            }
            composable("history/{id}") { entry ->
                val id = entry.arguments?.getString("id").orEmpty()
                HistoryDetailScreen(historyId = id, onBack = { nav.popBackStack() })
            }
        }
    }
}
