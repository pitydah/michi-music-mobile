package org.michimusic.mobile.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.filled.CastConnected
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import org.michimusic.mobile.ui.components.MiniPlayer
import org.michimusic.mobile.ui.screens.AlbumsScreen
import org.michimusic.mobile.ui.screens.AudioRouteScreen
import org.michimusic.mobile.ui.screens.HomeScreen
import org.michimusic.mobile.ui.screens.NowPlayingScreen
import org.michimusic.mobile.ui.screens.PlaylistScreen
import org.michimusic.mobile.ui.screens.QueueScreen
import org.michimusic.mobile.ui.screens.RemoteScreen
import org.michimusic.mobile.ui.screens.SearchScreen
import org.michimusic.mobile.ui.screens.SettingsScreen
import org.michimusic.mobile.ui.screens.SyncScreen
import org.michimusic.mobile.ui.screens.SyncedTracksScreen

data class BottomNavEntry(
    val route: String,
    val label: String,
    val icon: ImageVector,
)

private val navItems = listOf(
    BottomNavEntry("home", "Inicio", Icons.Default.Home),
    BottomNavEntry("library", "Biblioteca", Icons.Default.LibraryMusic),
    BottomNavEntry("nowplaying", "Reproduciendo", Icons.Default.MusicNote),
    BottomNavEntry("remote", "Remoto", Icons.Default.CastConnected),
    BottomNavEntry("sync", "Sync", Icons.Default.Sync),
    BottomNavEntry("settings", "Ajustes", Icons.Default.Settings),
)

@Composable
fun MichiNavHost() {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDest = backStackEntry?.destination

    Scaffold(
        bottomBar = {
            NavigationBar {
                navItems.forEach { entry ->
                    NavigationBarItem(
                        icon = { Icon(entry.icon, contentDescription = entry.label) },
                        label = { Text(entry.label) },
                        selected = currentDest?.hierarchy?.any { it.route == entry.route } == true,
                        onClick = {
                            navController.navigate(entry.route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                    )
                }
            }
        },
    ) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            NavHost(
                navController = navController,
                startDestination = "home",
                modifier = Modifier.fillMaxSize().padding(bottom = 72.dp),
            ) {
                composable("home") { HomeScreen(onNavigateToSearch = { navController.navigate("search") }) }
                composable("library") { AlbumsScreen() }
                composable("nowplaying") {
                    NowPlayingScreen(
                        onNavigateToSettings = { navController.navigate("settings") },
                        onNavigateToAudioRoute = { navController.navigate("audio-route") },
                    )
                }
                composable("playlist") { PlaylistScreen() }
                composable("queue") { QueueScreen() }
                composable("remote") { RemoteScreen(onNavigateToSync = { navController.navigate("sync") }) }
                composable("sync") { SyncScreen(onNavigateToSynced = { navController.navigate("synced") }) }
                composable("synced") { SyncedTracksScreen() }
                composable("search") { SearchScreen() }
                composable("settings") { SettingsScreen(onNavigateToDiagnostics = { navController.navigate("diagnostics") }) }
                composable("diagnostics") { org.michimusic.mobile.ui.screens.DiagnosticsScreen() }
                composable("audio-route") { AudioRouteScreen() }
            }

            MiniPlayer(
                modifier = Modifier.align(Alignment.BottomCenter),
                onClick = { navController.navigate("nowplaying") },
                visible = currentDest?.route != "nowplaying",
            )
        }
    }
}
