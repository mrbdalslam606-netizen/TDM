package com.tdm.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavHostController
import androidx.navigation.compose.*
import com.tdm.app.di.AppContainer
import com.tdm.app.ui.screens.login.*
import com.tdm.app.ui.screens.downloads.*
import com.tdm.app.ui.screens.history.*
import com.tdm.app.ui.screens.schedules.*
import com.tdm.app.ui.screens.settings.*
import com.tdm.app.ui.screens.sources.*
import com.tdm.app.ui.screens.statistics.*
import com.tdm.app.ui.screens.templates.*
import com.tdm.app.ui.theme.TdmTheme

/**
 * MainActivity — Compose host. Navigation bar on phones (spec §45).
 * Sections: Downloads, Sources, Schedules, Templates, History, Statistics, Settings.
 */
class MainActivity : ComponentActivity() {

    private lateinit var container: AppContainer

    private val treePicker =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) {
                contentResolver.takePersistableUriPermission(
                    uri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
                kotlinx.coroutines.runBlocking {
                    container.settingsRepository.update { it.copy(storageTreeUri = uri.toString()) }
                    val db = container.database
                    db.storageProfileDao().deactivateAll()
                    db.storageProfileDao().upsert(
                        com.tdm.app.data.db.StorageProfileEntity(treeUri = uri.toString(), isActive = true)
                    )
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        container = AppContainer.get(this)
        setContent {
            val appSettings = container.settingsRepository.settings.collectAsState(initial = null).value
            TdmTheme(darkTheme = appSettings?.darkMode ?: androidx.compose.foundation.isSystemInDarkTheme()) {
                Surface(Modifier.fillMaxSize()) {
                    RootNav(container, ::pickStorageTree)
                }
            }
        }
    }

    fun pickStorageTree() = treePicker.launch(null)
}

private data class Section(val route: String, val label: String, val icon: ImageVector)

private val sections = listOf(
    Section("downloads", "Downloads", Icons.Filled.Download),
    Section("sources", "Sources", Icons.Filled.Groups),
    Section("schedules", "Schedules", Icons.Filled.Schedule),
    Section("templates", "Templates", Icons.Filled.Dashboard),
    Section("history", "History", Icons.Filled.History),
    Section("statistics", "Statistics", Icons.Filled.BarChart),
    Section("settings", "Settings", Icons.Filled.Settings),
)

@Composable
fun RootNav(container: AppContainer, pickTree: () -> Unit) {
    val nav = rememberNavController()
    val settings = container.settingsRepository.settings.collectAsState(initial = null).value
    val ctx = androidx.compose.ui.platform.LocalContext.current

    // Start the engine service whenever the app opens while logged in (foreground start is allowed)
    LaunchedEffect(settings?.loggedIn) {
        if (settings?.loggedIn == true) {
            com.tdm.app.service.DownloadForegroundService.start(ctx, "app-open")
        }
    }

    if (settings == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) {
            androidx.compose.material3.CircularProgressIndicator()
        }
        return
    }
    val startRoute = if (settings.loggedIn) "downloads" else "login"

    Scaffold(
        bottomBar = {
            if (settings.loggedIn) {
                NavigationBar {
                    sections.forEach { sec ->
                        NavigationBarItem(
                            selected = currentRoute(nav) == sec.route,
                            onClick = {
                                nav.navigate(sec.route) {
                                    popUpTo(nav.graph.startDestinationId) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(sec.icon, contentDescription = sec.label) },
                            label = { Text(sec.label) },
                        )
                    }
                }
            }
        }
    ) { pad ->
        NavHost(
            navController = nav,
            startDestination = startRoute,
            modifier = Modifier.padding(pad),
        ) {
            composable("login") { LoginScreen(container, onDone = { nav.navigate("downloads") { popUpTo(0) } }) }
            composable("downloads") { DownloadsScreen(container, onReliability = { nav.navigate("reliability") }, onPreview = { nav.navigate("preview") }) }
            composable("sources") { SourcesScreen(container) }
            composable("schedules") { SchedulesScreen(container) }
            composable("templates") { TemplatesScreen(container) }
            composable("history") { HistoryScreen(container) }
            composable("statistics") { StatisticsScreen(container) }
            composable("settings") {
                SettingsScreen(container, pickTree, onAccountRemoved = {
                    hasRemainingAccount -> nav.navigate(if (hasRemainingAccount) "downloads" else "login") { popUpTo(0) }
                })
            }
            composable("reliability") { ReliabilityScreen(container, onBack = { nav.popBackStack() }) }
            composable("preview") { PreviewScreen(container, onBack = { nav.popBackStack() }) }
        }
    }
}

@Composable
private fun currentRoute(nav: NavHostController): String? =
    nav.currentBackStackEntryAsState().value?.destination?.route
