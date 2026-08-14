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
    val miniPlayerVisible = currentRoute != "nowplaying"

    Scaffold(
        containerColor = SurfaceObsidian,
        bottomBar = {
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
        },
    ) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            NavHost(
                navController = navController,
                startDestination = "home",
                modifier = Modifier
                    .fillMaxSize()
                    .padding(bottom = if (miniPlayerVisible) 68.dp else 0.dp),
            ) {
                composable("home") { HomeScreen(onNavigateToSearch = { navController.navigate("search") }) }
                composable("library") { AlbumsScreen() }
                composable("nowplaying") {
                    NowPlayingScreen(
                        onBack = { navController.popBackStack() },
                    )
                }
                composable("playlist") { PlaylistScreen() }
                composable("queue") { QueueScreen() }
                composable("remote") { RemoteScreen(onNavigateToSync = { navController.navigate("sync") }) }
                composable("sync") { SyncScreen(onNavigateToSynced = { navController.navigate("synced") }) }
                composable("synced") { SyncedTracksScreen() }
                composable("search") { SearchScreen() }
                composable("settings") { SettingsScreen(onNavigateToDiagnostics = { navController.navigate("diagnostics") }) }
                composable("diagnostics") { DiagnosticsScreen() }
                composable("audio-route") { AudioRouteScreen() }
            }

            MiniPlayer(
                modifier = Modifier.align(Alignment.BottomCenter),
                onClick = { navController.navigate("nowplaying") },
                visible = miniPlayerVisible,
            )
        }
    }
}
