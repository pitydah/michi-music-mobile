package org.michimusic.mobile.navigation

import androidx.compose.foundation.layout.Box
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import org.michimusic.mobile.ui.components.BottomNavBar
import org.michimusic.mobile.ui.components.MiniPlayer
import org.michimusic.mobile.ui.screens.AlbumsScreen
import org.michimusic.mobile.ui.screens.AudioRouteScreen
import org.michimusic.mobile.ui.screens.DevicesScreen
import org.michimusic.mobile.ui.screens.DiagnosticsScreen
import org.michimusic.mobile.ui.screens.HomeScreen
import org.michimusic.mobile.ui.screens.NowPlayingScreen
import org.michimusic.mobile.ui.screens.PlaylistDetailScreen
import org.michimusic.mobile.ui.screens.PlaylistScreen
import org.michimusic.mobile.ui.screens.QueueScreen
import org.michimusic.mobile.ui.screens.RemoteScreen
import org.michimusic.mobile.ui.screens.SearchScreen
import org.michimusic.mobile.ui.screens.SettingsScreen
import org.michimusic.mobile.ui.screens.SyncScreen
import org.michimusic.mobile.ui.screens.SyncedTracksScreen
import org.michimusic.mobile.ui.theme.SurfaceObsidian
import org.michimusic.mobile.ui.theme.MichiSpacing
import org.michimusic.mobile.playback.PlaybackSessionManager
import org.michimusic.mobile.playback.resolvePlaybackPresentation
import org.michimusic.player.AudioController
import org.koin.compose.koinInject
import androidx.compose.runtime.collectAsState

@Composable
fun MichiNavHost() {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDest = backStackEntry?.destination
    val currentRoute = currentDest?.route
    val chrome = navigationPresentationFor(currentRoute)
    val immersive = chrome.level == MichiScreenLevel.IMMERSIVE
    val miniPlayerVisible = chrome.showMiniPlayer
    // Reserve mini player height only when a track actually exists.
    val sessionManager: PlaybackSessionManager = koinInject()
    val sessionState by sessionManager.sessionState.collectAsState()
    val audioController: AudioController = koinInject()
    val localState by audioController.state.collectAsState()
    val presentation = resolvePlaybackPresentation(sessionState, localState)
    val hasMiniPlayer = presentation.hasActiveTrack

    Scaffold(
        containerColor = SurfaceObsidian,
        bottomBar = {
            if (chrome.showDock) {
                BottomNavBar(
                    currentRoute = currentRoute,
                    selectedRoute = chrome.selectedPrimaryTab,
                    onTabSelected = { route ->
                        // No saveState/restoreState: this NavHost is a single flat graph, not
                        // one nested graph per tab, so secondary screens pushed on top of a tab
                        // (e.g. "settings", "playlist/{playlistId}") would otherwise get cached
                        // and incorrectly restored the next time that tab is reselected.
                        // Always land on the tab's root instead.
                        navController.navigate(route) {
                            popUpTo(navController.graph.findStartDestination().id)
                            launchSingleTop = true
                        }
                    },
                )
            }
        }
    ) { innerPadding ->
        val isFullBleed = currentRoute == "nowplaying"
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(if (isFullBleed) androidx.compose.foundation.layout.PaddingValues(0.dp) else innerPadding),
        ) {
            NavHost(
                navController = navController,
                startDestination = "home",
                modifier = Modifier
                    .fillMaxSize()
                    .padding(bottom = if (miniPlayerVisible && hasMiniPlayer) MichiSpacing.miniPlayerSpace else 0.dp),
            ) {
                composable("home") {
                    HomeScreen(
                        onNavigateToSearch = { navController.navigate("search") },
                        onNavigateToSettings = { navController.navigate("settings") },
                        onNavigateToDevices = { navController.navigate("devices") },
                        onNavigateToPlaylist = { id -> navController.navigate("playlist/$id") },
                    )
                }
                composable("library") {
                    AlbumsScreen(
                        onNavigateToPlaylists = { navController.navigate("playlist") },
                        sessionManager = sessionManager,
                    )
                }
                composable("search") {
                    SearchScreen(
                        sessionManager = sessionManager,
                    )
                }
                composable("devices") {
                    DevicesScreen(
                        onNavigateToSettings = { navController.navigate("settings") },
                        onNavigateToSynced = { navController.navigate("synced") },
                        onNavigateToSyncWizard = { deviceId -> navController.navigate("sync_wizard/$deviceId") },
                    )
                }
                composable(
                    route = "sync_wizard/{deviceId}",
                    arguments = listOf(navArgument("deviceId") { type = NavType.StringType }),
                ) { backStackEntry ->
                    val deviceId = backStackEntry.arguments?.getString("deviceId") ?: return@composable
                    val syncVm: org.michimusic.mobile.sync.SyncViewModel = org.koin.androidx.compose.koinViewModel()
                    val wizardState by syncVm.wizardUiState.collectAsStateWithLifecycle()

                    androidx.compose.runtime.LaunchedEffect(deviceId) {
                        syncVm.loadWizardData(deviceId)
                    }

                    org.michimusic.mobile.ui.screens.sync.SyncWizardScreen(
                        endpointId = deviceId,
                        endpointName = wizardState.deviceName.ifEmpty { "Micro Server" },
                        availableStorageBytes = wizardState.availableStorageBytes,
                        totalStorageBytes = wizardState.totalStorageBytes,
                        currentLocalMusicBytes = wizardState.currentLocalMusicBytes,
                        candidateTracks = wizardState.candidateTracks,
                        manifestPlaylists = wizardState.playlists,
                        serverCapabilities = wizardState.serverCapabilities,
                        serverRepresentations = wizardState.serverRepresentations,
                        localFingerprints = wizardState.localFingerprints,
                        existingPartials = wizardState.existingPartials,
                        isLoading = wizardState.isLoading,
                        errorMessage = wizardState.error,
                        onRetry = { syncVm.loadWizardData(deviceId) },
                        onStartSync = { plan ->
                            syncVm.syncPlannedLibrary(plan)
                            navController.popBackStack()
                        },
                        onBack = { navController.popBackStack() },
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
                composable("playlist") {
                    PlaylistScreen(
                        onNavigateToPlaylist = { playlistId -> navController.navigate("playlist/$playlistId") },
                        sessionManager = sessionManager,
                    )
                }
                composable(
                    route = "playlist/{playlistId}",
                    arguments = listOf(navArgument("playlistId") { type = NavType.StringType }),
                ) { backStackEntry ->
                    val playlistId = backStackEntry.arguments?.getString("playlistId") ?: return@composable
                    PlaylistDetailScreen(
                        playlistId = playlistId,
                        onBack = { navController.popBackStack() },
                        sessionManager = sessionManager,
                    )
                }
                composable("queue") {
                    QueueScreen(
                        sessionManager = sessionManager,
                    )
                }
                composable("synced") { SyncedTracksScreen() }
                composable("diagnostics") { DiagnosticsScreen() }
                composable("audio-route") { AudioRouteScreen() }
                composable("remote") { RemoteScreen(onNavigateToSync = { navController.navigate("devices") }) }
                composable("sync") { SyncScreen(onNavigateToSynced = { navController.navigate("synced") }) }
            }

            MiniPlayer(
                modifier = Modifier.align(Alignment.BottomCenter),
                onClick = { navController.navigate("nowplaying") },
                visible = miniPlayerVisible && hasMiniPlayer,
            )
        }
    }
}
