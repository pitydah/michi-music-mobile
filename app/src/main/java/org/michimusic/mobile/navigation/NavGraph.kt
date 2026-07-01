package org.michimusic.mobile.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CastConnected
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sync
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
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
import org.michimusic.mobile.ui.screens.HomeScreen
import org.michimusic.mobile.ui.screens.NowPlayingScreen
import org.michimusic.mobile.ui.screens.PlaylistScreen
import org.michimusic.mobile.ui.screens.AudioRouteScreen
import org.michimusic.mobile.ui.screens.RemoteScreen
import org.michimusic.mobile.ui.screens.SearchScreen
import org.michimusic.mobile.ui.screens.SettingsScreen
import org.michimusic.mobile.ui.screens.SyncScreen
import org.michimusic.mobile.ui.screens.SyncedTracksScreen
import org.michimusic.mobile.ui.getAudioController
data class BottomNavItem(
    val route: String,
    val label: String,
    val icon: ImageVector,
)

val bottomNavItems = listOf(
    BottomNavItem("home", "Inicio", Icons.Default.Home),
    BottomNavItem("library", "Biblioteca", Icons.Default.LibraryMusic),
    BottomNavItem("nowplaying", "Reproduciendo", Icons.Default.MusicNote),
    BottomNavItem("remote", "Control", Icons.Default.CastConnected),
    BottomNavItem("sync", "Sync", Icons.Default.Sync),
    BottomNavItem("settings", "Ajustes", Icons.Default.Settings),
)

@Composable
fun MichiNavHost() {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination

    val controller = remember { getAudioController() }
    val playerState by controller?.state?.collectAsState() ?: remember {
        androidx.compose.runtime.mutableStateOf(org.michimusic.player.PlayerState())
    }
    val hasCurrentTrack = playerState.currentTrack != null

    Scaffold(
        bottomBar = {
            NavigationBar {
                bottomNavItems.forEach { item ->
                    NavigationBarItem(
                        icon = { Icon(item.icon, contentDescription = item.label) },
                        label = { Text(item.label) },
                        selected = currentDestination?.hierarchy?.any {
                            it.route == item.route
                        } == true,
                        onClick = {
                            navController.navigate(item.route) {
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
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(bottom = if (hasCurrentTrack) 72.dp else 0.dp),
            ) {
                NavHost(
                    navController = navController,
                    startDestination = "home",
                    modifier = Modifier.fillMaxSize(),
                ) {
                    composable("home") { HomeScreen(onNavigateToSearch = { navController.navigate("search") }) }
                    composable("library") { AlbumsScreen() }
                    composable("playlist") { PlaylistScreen() }
                    composable("nowplaying") { NowPlayingScreen() }
                    composable("sync") { SyncScreen(onNavigateToSynced = { navController.navigate("synced") }) }
                    composable("synced") { SyncedTracksScreen() }
                    composable("search") { SearchScreen() }
                    composable("remote") { RemoteScreen(onNavigateToSync = { navController.navigate("sync") }) }
                    composable("audio-route") { AudioRouteScreen() }
                    composable("settings") { SettingsScreen() }
                }
            }

            if (hasCurrentTrack) {
                MiniPlayer(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .navigationBarsPadding(),
                    onClick = { navController.navigate("nowplaying") },
                )
            }
        }
    }
}
