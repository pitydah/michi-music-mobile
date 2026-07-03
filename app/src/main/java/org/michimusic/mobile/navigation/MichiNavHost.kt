package org.michimusic.mobile.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CastConnected
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import org.michimusic.mobile.ui.theme.TextPrimary

data class BottomNavEntry(
    val route: String,
    val label: String,
    val icon: ImageVector,
)

private val navItems = listOf(
    BottomNavEntry("home", "Inicio", Icons.Default.Home),
    BottomNavEntry("library", "Biblioteca", Icons.Default.LibraryMusic),
    BottomNavEntry("nowplaying", "Ahora", Icons.Default.MusicNote),
    BottomNavEntry("remote", "Remoto", Icons.Default.CastConnected),
    BottomNavEntry("sync", "Sync", Icons.Default.Sync),
    BottomNavEntry("settings", "Ajustes", Icons.Default.Settings),
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
                .clip(RoundedCornerShape(30.dp))
                .background(SurfaceElevated.copy(alpha = 0.78f))
                .border(0.5.dp, SurfaceBorder.copy(alpha = 0.82f), RoundedCornerShape(30.dp))
                .padding(horizontal = 8.dp, vertical = 7.dp),
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
    val tint = if (selected) SurfaceDark else TextDim
    val labelColor = if (selected) TextPrimary else TextDim

    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(24.dp))
            .background(
                if (selected) {
                    Brush.horizontalGradient(listOf(AccentCoral, AccentPink))
                } else {
                    Brush.horizontalGradient(listOf(Color.Transparent, Color.Transparent))
                }
            )
            .padding(horizontal = if (selected) 10.dp else 2.dp, vertical = 2.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            IconButton(onClick = onClick, modifier = Modifier.size(38.dp)) {
                Icon(
                    imageVector = entry.icon,
                    contentDescription = entry.label,
                    tint = tint,
                    modifier = Modifier.size(21.dp),
                )
            }
            if (selected) {
                Text(
                    text = entry.label,
                    color = labelColor,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(end = 8.dp),
                    maxLines = 1,
                )
            }
        }
    }
}
