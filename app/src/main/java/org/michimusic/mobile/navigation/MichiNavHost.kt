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
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
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
import org.michimusic.mobile.ui.theme.AccentPink
import org.michimusic.mobile.ui.theme.SurfaceDark
import org.michimusic.mobile.ui.theme.TextDim
import org.michimusic.mobile.ui.theme.TextPrimary
import org.michimusic.mobile.ui.theme.michiAccentFor
import org.koin.compose.koinInject
import org.michimusic.player.AudioController

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
    val audioController: AudioController = koinInject()
    val playerState by audioController.state.collectAsState()
    val dockAccent = remember(playerState.currentTrack?.coverId, playerState.currentTrack?.title) {
        michiAccentFor(playerState.currentTrack?.coverId ?: playerState.currentTrack?.title)
    }

    Scaffold(
        containerColor = SurfaceDark,
        bottomBar = {
            FloatingBottomDock(
                currentRoute = currentRoute,
                accent = dockAccent,
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
    accent: Color,
    onNavigate: (String) -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    listOf(Color.Transparent, SurfaceDark.copy(alpha = 0.58f))
                )
            )
            .navigationBarsPadding()
            .padding(horizontal = 18.dp, vertical = 7.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.72f)
                    .height(1.dp)
                    .clip(RoundedCornerShape(1.dp))
                    .background(Color.White.copy(alpha = 0.12f)),
            )
            Spacer(modifier = Modifier.height(6.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 54.dp)
                    .shadow(22.dp, RoundedCornerShape(30.dp), clip = false)
                    .clip(RoundedCornerShape(30.dp))
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                Color.White.copy(alpha = 0.12f),
                                Color(0xB00F1117),
                                Color(0xD5080A0F),
                            )
                        )
                    )
                    .border(0.6.dp, Color.White.copy(alpha = 0.18f), RoundedCornerShape(30.dp))
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                navItems.forEach { entry ->
                    val selected = currentRoute == entry.route
                    FloatingDockItem(
                        entry = entry,
                        selected = selected,
                        accent = accent,
                        onClick = { onNavigate(entry.route) },
                    )
                }
            }
        }
    }
}

@Composable
private fun FloatingDockItem(
    entry: BottomNavEntry,
    selected: Boolean,
    accent: Color,
    onClick: () -> Unit,
) {
    val tint = if (selected) accent else TextDim

    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(22.dp))
            .background(
                if (selected) {
                    Brush.verticalGradient(
                        listOf(
                            Color.White.copy(alpha = 0.11f),
                            accent.copy(alpha = 0.08f),
                        )
                    )
                } else {
                    Brush.verticalGradient(listOf(Color.Transparent, Color.Transparent))
                }
            )
            .border(
                0.5.dp,
                if (selected) accent.copy(alpha = 0.34f) else Color.Transparent,
                RoundedCornerShape(22.dp),
            )
            .padding(horizontal = 5.dp, vertical = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        IconButton(onClick = onClick, modifier = Modifier.size(34.dp)) {
            Icon(
                imageVector = entry.icon,
                contentDescription = entry.label,
                tint = tint,
                modifier = Modifier.size(20.dp),
            )
        }
        if (selected) {
            Text(
                text = entry.label,
                style = MaterialTheme.typography.labelSmall,
                color = TextPrimary,
                maxLines = 1,
            )
        } else {
            Spacer(modifier = Modifier.height(12.dp))
        }
        Spacer(modifier = Modifier.height(2.dp))
        Box(
            modifier = Modifier
                .size(width = if (selected) 15.dp else 3.dp, height = 2.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(if (selected) Brush.horizontalGradient(listOf(accent, AccentPink)) else Brush.horizontalGradient(listOf(Color.Transparent, Color.Transparent))),
        ) {
        }
    }
}
