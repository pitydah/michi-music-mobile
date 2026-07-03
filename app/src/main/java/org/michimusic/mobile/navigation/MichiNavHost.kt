package org.michimusic.mobile.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.LocalOffer
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
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
import org.michimusic.mobile.ui.theme.AccentCoral
import org.michimusic.mobile.ui.theme.AccentPink
import org.michimusic.mobile.ui.theme.SurfaceDark
import org.michimusic.mobile.ui.theme.SurfaceElevated
import org.michimusic.mobile.ui.theme.SurfaceBorder
import org.michimusic.mobile.ui.theme.TextDim

data class BottomNavEntry(
    val route: String,
    val label: String,
    val icon: ImageVector,
)

private val navItems = listOf(
    BottomNavEntry("home", "Inicio", Icons.Rounded.Folder),
    BottomNavEntry("library", "Biblioteca", Icons.Rounded.Album),
    BottomNavEntry("nowplaying", "Ahora", Icons.Rounded.PlayCircle),
    BottomNavEntry("remote", "Remoto", Icons.Rounded.Person),
    BottomNavEntry("sync", "Sync", Icons.Rounded.LocalOffer),
    BottomNavEntry("settings", "Ajustes", Icons.Rounded.MoreVert),
)

@Composable
fun MichiNavHost() {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDest = backStackEntry?.destination
    val currentRoute = currentDest?.route
    val miniPlayerVisible = currentRoute != "nowplaying"

    Scaffold(
        containerColor = SurfaceDark,
        bottomBar = {
            FloatingBottomDock(
                currentRoute = currentRoute,
                onNavigate = { route ->
                    navController.navigate(route) {
                        popUpTo(navController.graph.findStartDestination().id) {
                            saveState = true
                        }
                        launchSingleTop = true
                        restoreState = true
                    }
                }
            )
        },
    ) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            NavHost(
                navController = navController,
                startDestination = "home",
                modifier = Modifier
                    .fillMaxSize()
                    .padding(bottom = if (miniPlayerVisible) 76.dp else 0.dp),
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
                visible = miniPlayerVisible,
            )
        }
    }
}

@Composable
private fun FloatingBottomDock(
    currentRoute: String?,
    onNavigate: (String) -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    listOf(Color.Transparent, SurfaceDark.copy(alpha = 0.82f))
                )
            )
            .navigationBarsPadding()
            .padding(horizontal = 14.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(28.dp))
                .background(SurfaceElevated.copy(alpha = 0.70f))
                .border(0.5.dp, SurfaceBorder.copy(alpha = 0.92f), RoundedCornerShape(28.dp))
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            navItems.forEach { entry ->
                val selected = currentRoute == entry.route
                FloatingDockItem(
                    entry = entry,
                    selected = selected,
                    onClick = { onNavigate(entry.route) },
                )
            }
        }
    }
}

@Composable
private fun FloatingDockItem(
    entry: BottomNavEntry,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val tint = if (selected) AccentCoral else TextDim

    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(22.dp))
            .background(if (selected) AccentCoral.copy(alpha = 0.13f) else Color.Transparent)
            .padding(horizontal = 5.dp, vertical = 3.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        IconButton(onClick = onClick, modifier = Modifier.size(38.dp)) {
            Icon(
                imageVector = entry.icon,
                contentDescription = entry.label,
                tint = tint,
                modifier = Modifier.size(22.dp),
            )
        }
        Spacer(modifier = Modifier.height(2.dp))
        Box(
            modifier = Modifier
                .size(width = if (selected) 16.dp else 4.dp, height = 3.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(if (selected) Brush.horizontalGradient(listOf(AccentCoral, AccentPink)) else Brush.horizontalGradient(listOf(Color.Transparent, Color.Transparent))),
        ) {
        }
    }
}
