package org.michimusic.mobile.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CastConnected
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sync
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import org.michimusic.mobile.ui.components.MichiBottomNavigation
import org.michimusic.mobile.ui.components.MichiNavItem
import org.michimusic.mobile.ui.components.MiniPlayer
import org.michimusic.mobile.ui.screens.AlbumsScreen
import org.michimusic.mobile.ui.screens.AudioRouteScreen
import org.michimusic.mobile.ui.screens.DiagnosticsScreen
import org.michimusic.mobile.ui.screens.HomeScreen
import org.michimusic.mobile.ui.screens.NowPlayingScreen
import org.michimusic.mobile.ui.screens.PlaylistScreen
import org.michimusic.mobile.ui.screens.PlaylistsScreen
import org.michimusic.mobile.ui.screens.QueueScreen
import org.michimusic.mobile.ui.screens.RemoteScreen
import org.michimusic.mobile.ui.screens.SearchScreen
import org.michimusic.mobile.ui.screens.SettingsScreen
import org.michimusic.mobile.ui.screens.SyncScreen
import org.michimusic.mobile.ui.screens.SyncedTracksScreen

val bottomNavItems = listOf(
    MichiNavItem("home", "Inicio", Icons.Default.Home),
    MichiNavItem("library", "Biblioteca", Icons.Default.LibraryMusic),
    MichiNavItem("nowplaying", "Reproduciendo", Icons.Default.MusicNote),
    MichiNavItem("remote", "Control", Icons.Default.CastConnected),
    MichiNavItem("sync", "Sync", Icons.Default.Sync),
    MichiNavItem("settings", "Ajustes", Icons.Default.Settings),
)

@Composable
fun MichiNavHost() {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route
    val isNowPlaying = currentRoute == "nowplaying"

    Box(modifier = Modifier.fillMaxSize()) {
        NavHost(
            navController = navController,
            startDestination = "home",
            modifier = Modifier.fillMaxSize(),
        ) {
            composable("home") { HomeScreen(onNavigateToSearch = { navController.navigate("search") }) }
            composable("library") { AlbumsScreen() }
            composable("playlist") { PlaylistScreen() }
            composable("playlists") { PlaylistsScreen() }
            composable("queue") { QueueScreen() }
            composable("nowplaying") { NowPlayingScreen() }
            composable("sync") { SyncScreen(onNavigateToSynced = { navController.navigate("synced") }) }
            composable("synced") { SyncedTracksScreen() }
            composable("search") { SearchScreen() }
            composable("remote") { RemoteScreen(onNavigateToSync = { navController.navigate("sync") }) }
            composable("audio-route") { AudioRouteScreen() }
            composable("diagnostics") { DiagnosticsScreen() }
            composable("settings") { SettingsScreen() }
        }

        if (!isNowPlaying) {
            MiniPlayer(
                modifier = Modifier.padding(bottom = 64.dp),
                onClick = { navController.navigate("nowplaying") },
            )

            MichiBottomNavigation(
                items = bottomNavItems,
                currentRoute = currentRoute,
                onItemClick = { item ->
                    navController.navigate(item.route) {
                        popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                },
                modifier = Modifier,
            )
        }
    }
}
