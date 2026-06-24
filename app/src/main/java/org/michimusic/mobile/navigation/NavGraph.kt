package org.michimusic.mobile.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sync
import androidx.compose.ui.Alignment
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import org.michimusic.mobile.ui.screens.SettingsScreen
import org.michimusic.mobile.ui.screens.SyncScreen
data class BottomNavItem(
    val route: String,
    val label: String,
    val icon: ImageVector,
)

val bottomNavItems = listOf(
    BottomNavItem("home", "Inicio", Icons.Default.Home),
    BottomNavItem("library", "Biblioteca", Icons.Default.LibraryMusic),
    BottomNavItem("nowplaying", "Reproduciendo", Icons.Default.MusicNote),
    BottomNavItem("sync", "Sync", Icons.Default.Sync),
    BottomNavItem("settings", "Ajustes", Icons.Default.Settings),
)

@Composable
fun MichiNavHost() {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination

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
            NavHost(
                navController = navController,
                startDestination = "home",
                modifier = Modifier.fillMaxSize(),
            ) {
                composable("home") { HomeScreen() }
                composable("library") { AlbumsScreen() }
                composable("playlist") { PlaylistScreen() }
                composable("nowplaying") { NowPlayingScreen() }
                composable("sync") { SyncScreen() }
                composable("settings") { SettingsScreen() }
            }

            MiniPlayer(
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }
}
