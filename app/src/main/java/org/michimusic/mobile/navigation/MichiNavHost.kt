package org.michimusic.mobile.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import org.michimusic.mobile.ui.components.BottomNavBar
import org.michimusic.mobile.ui.components.MiniPlayer
import org.michimusic.mobile.ui.screens.AlbumsScreen
import org.michimusic.mobile.ui.screens.AudioRouteScreen
import org.michimusic.mobile.ui.screens.DevicesScreen
import org.michimusic.mobile.ui.screens.DiagnosticsScreen
import org.michimusic.mobile.ui.screens.HomeScreen
import org.michimusic.mobile.ui.screens.NowPlayingScreen
import org.michimusic.mobile.ui.screens.PlaylistScreen
import org.michimusic.mobile.ui.screens.QueueScreen
import org.michimusic.mobile.ui.screens.RemoteScreen
import org.michimusic.mobile.ui.screens.SearchScreen
import org.michimusic.mobile.ui.screens.SettingsScreen
import org.michimusic.mobile.ui.screens.SyncScreen
import org.michimusic.mobile.ui.screens.SyncedTracksScreen
import org.michimusic.mobile.ui.theme.SurfaceObsidian

@Composable
fun MichiNavHost() {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDest = backStackEntry?.destination
    val currentRoute = currentDest?.route
    val isFullscreenNowPlaying = currentRoute == "nowplaying"
    val miniPlayerVisible = !isFullscreenNowPlaying

    Scaffold(
        containerColor = SurfaceObsidian,
        bottomBar = {
            if (!isFullscreenNowPlaying) {
                BottomNavBar(
                    currentRoute = currentRoute,
                    onTabSelected = { route ->
                        navController.navigate(route) {
                            popUpTo(navController.graph.findStartDestination().id) {
                                saveState = true
                            }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                )
            }
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(if (isFullscreenNowPlaying) androidx.compose.foundation.layout.PaddingValues(0.dp) else innerPadding),
        ) {
            NavHost(
                navController = navController,
                startDestination = "home",
                modifier = Modifier
                    .fillMaxSize()
                    .padding(bottom = if (miniPlayerVisible) 68.dp else 0.dp),
            ) {
                composable("home") {
                    HomeScreen(
                        onNavigateToSearch = { navController.navigate("search") },
                        onNavigateToSettings = { navController.navigate("settings") },
                        onNavigateToDevices = { navController.navigate("devices") },
                    )
                }
                composable("library") { AlbumsScreen() }
                composable("search") { SearchScreen() }
                composable("devices") {
                    DevicesScreen(
                        onNavigateToSettings = { navController.navigate("settings") },
                        onNavigateToSynced = { navController.navigate("synced") },
                    )
                }
                composable("nowplaying") {
                    NowPlayingScreen(
                        onBack = { navController.popBackStack() },
                    )
                }
                composable("settings") {
                    SettingsScreen(
                        onNavigateToDiagnostics = { navController.navigate("diagnostics") },
                    )
                }
                composable("playlist") { PlaylistScreen() }
                composable("queue") { QueueScreen() }
                composable("synced") { SyncedTracksScreen() }
                composable("diagnostics") { DiagnosticsScreen() }
                composable("audio-route") { AudioRouteScreen() }
                composable("remote") { RemoteScreen(onNavigateToSync = { navController.navigate("devices") }) }
                composable("sync") { SyncScreen(onNavigateToSynced = { navController.navigate("synced") }) }
            }

            MiniPlayer(
                modifier = Modifier.align(Alignment.BottomCenter),
                onClick = { navController.navigate("nowplaying") },
                visible = miniPlayerVisible,
            )
        }
    }
}
