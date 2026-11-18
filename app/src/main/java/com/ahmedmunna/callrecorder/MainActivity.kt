package com.ahmedmunna.callrecorder

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.ahmedmunna.callrecorder.data.prefs.AppSettings
import com.ahmedmunna.callrecorder.ui.MainViewModel
import com.ahmedmunna.callrecorder.ui.screens.DetailScreen
import com.ahmedmunna.callrecorder.ui.screens.EngineTestScreen
import com.ahmedmunna.callrecorder.ui.screens.HomeScreen
import com.ahmedmunna.callrecorder.ui.screens.LibraryScreen
import com.ahmedmunna.callrecorder.ui.screens.LockScreen
import com.ahmedmunna.callrecorder.ui.screens.SettingsMenu
import com.ahmedmunna.callrecorder.ui.screens.SettingsSection
import com.ahmedmunna.callrecorder.ui.theme.AppTheme
import com.ahmedmunna.callrecorder.work.WorkScheduler
import dagger.hilt.android.AndroidEntryPoint

private data class Tab(val route: String, val label: String, val icon: ImageVector)

private val tabs = listOf(
    Tab("home", "Home", Icons.Filled.Home),
    Tab("recordings", "Recordings", Icons.Filled.LibraryMusic),
    Tab("favorites", "Favorites", Icons.Filled.Favorite),
    Tab("engine", "Engine Test", Icons.Filled.GraphicEq),
    Tab("settings", "Settings", Icons.Filled.Settings),
)

@AndroidEntryPoint
class MainActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        WorkScheduler.scheduleMaintenance(this)
        setContent { Root() }
    }

    @Composable
    private fun Root() {
        val vm: MainViewModel = hiltViewModel()
        val loaded by produceState<AppSettings?>(null) { value = vm.settingsRepo.current() }
        val settings by vm.settings.collectAsState()
        if (loaded == null) return

        AppTheme(settings.themeMode, settings.accent) {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                var locked by rememberSaveable { mutableStateOf(true) }
                var stoppedAt by remember { mutableLongStateOf(0L) }
                val lockEnabled = settings.appLockEnabled

                // Hide the app from screenshots and the recents screen while a lock is configured.
                androidx.compose.runtime.LaunchedEffect(lockEnabled) {
                    if (lockEnabled) window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
                    else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                }
                // Re-lock after 30 s in the background (not immediately, so file pickers and permission dialogs don't lock you out).
                LifecycleEventEffect(Lifecycle.Event.ON_STOP) { stoppedAt = System.currentTimeMillis() }
                LifecycleEventEffect(Lifecycle.Event.ON_START) {
                    if (stoppedAt != 0L && System.currentTimeMillis() - stoppedAt > 30_000L) {
                        locked = true; vm.setPrivateUnlocked(false)
                    }
                }

                if (lockEnabled && locked) LockScreen(vm) { locked = false; vm.setPrivateUnlocked(true) }
                else AppScaffold(vm)
            }
        }
    }
}

@Composable
private fun AppScaffold(vm: MainViewModel) {
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route
    val isTop = route in tabs.map { it.route }
    val player by vm.player.state.collectAsState()
    val recs by vm.recordings.collectAsState()

    fun goTab(r: String) = nav.navigate(r) {
        popUpTo(nav.graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Box(Modifier.weight(1f)) {
            NavHost(nav, startDestination = "home") {
                composable("home") {
                    HomeScreen(vm, { nav.navigate("detail/$it") }, { sec -> nav.navigate("settings/$sec") }, { goTab("engine") })
                }
                composable("recordings") { LibraryScreen(vm, false) { nav.navigate("detail/$it") } }
                composable("favorites") { LibraryScreen(vm, true) { nav.navigate("detail/$it") } }
                composable("engine") { EngineTestScreen(vm) { sec -> nav.navigate("settings/$sec") } }
                composable("settings") { SettingsMenu { nav.navigate("settings/$it") } }
                composable("settings/{section}", listOf(navArgument("section") { type = NavType.StringType })) { e ->
                    SettingsSection(vm, e.arguments?.getString("section") ?: "", { nav.popBackStack() }) { goTab("engine") }
                }
                composable("detail/{id}", listOf(navArgument("id") { type = NavType.LongType })) { e ->
                    DetailScreen(vm, e.arguments?.getLong("id") ?: -1L) { nav.popBackStack() }
                }
            }
        }
        val playingId = player.currentId
        if (playingId != null && route?.startsWith("detail") != true) {
            val title = recs.firstOrNull { it.id == playingId }?.let { it.contactName ?: it.phoneNumber } ?: "Test recording"
            MiniPlayer(title, player.playing, { vm.player.toggle() }, { vm.player.stop() }) { if (playingId > 0) nav.navigate("detail/$playingId") }
        }
        if (isTop) {
            NavigationBar {
                tabs.forEach { t ->
                    NavigationBarItem(
                        selected = route == t.route || (t.route == "settings" && route?.startsWith("settings") == true),
                        onClick = { goTab(t.route) }, icon = { Icon(t.icon, t.label) }, label = { Text(t.label, maxLines = 1) },
                    )
                }
            }
        }
    }
}

@Composable
private fun MiniPlayer(title: String, playing: Boolean, onToggle: () -> Unit, onStop: () -> Unit, onOpen: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.secondaryContainer, tonalElevation = 3.dp, onClick = onOpen,
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, maxLines = 1)
            IconButton(onToggle) { Icon(if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, if (playing) "Pause" else "Play") }
            IconButton(onStop) { Icon(Icons.Filled.Stop, "Stop") }
        }
    }
}
