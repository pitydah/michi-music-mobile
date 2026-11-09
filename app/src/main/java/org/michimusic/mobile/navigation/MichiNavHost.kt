package org.michimusic.mobile.navigation

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
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
import org.michimusic.mobile.ui.motion.LocalAeroAlbumShareEnabled
import org.michimusic.mobile.ui.motion.LocalAeroAnimatedVisibilityScope
import org.michimusic.mobile.ui.motion.LocalAeroSharedTransitionScope
import org.michimusic.mobile.ui.motion.aeroAlbumRouteTransitionAllowed
import org.michimusic.mobile.ui.screens.AlbumsScreen
import org.michimusic.mobile.ui.screens.AlbumDetailScreen
import org.michimusic.mobile.ui.screens.ArtistDetailScreen
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
import org.michimusic.mobile.onboarding.OnboardingSnapshot
import org.michimusic.mobile.ui.theme.SurfaceObsidian
import org.michimusic.mobile.ui.theme.MichiSpacing
import org.michimusic.mobile.ui.theme.MichiMotion
import org.michimusic.mobile.ui.theme.LocalVisualPreferences
import org.michimusic.mobile.playback.PlaybackSessionManager
import org.michimusic.mobile.playback.resolvePlaybackPresentation
import org.michimusic.player.AudioController
import org.koin.compose.koinInject

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun MichiNavHost(
    onReopenOnboarding: () -> Unit = {},
    onboardingDestination: String? = null,
    completionRequestId: Int = 0,
    onboardingSnapshot: OnboardingSnapshot? = null,
) {
    val navController = rememberNavController()
    // These are real destinations from the current navigation graph, never
    // arbitrary strings or invented pairing/audio actions.
    LaunchedEffect(onboardingDestination, completionRequestId) {
        val destination = onboardingDestination?.takeIf {
            it in setOf("home", "devices", "settings/privacy", "audio-route")
        }
        if (destination != null) {
            navController.navigate(destination) {
                if (destination == "home") {
                    popUpTo(navController.graph.findStartDestination().id) { inclusive = false }
                }
                launchSingleTop = true
            }
        }
    }
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDest = backStackEntry?.destination
    val currentRoute = currentDest?.route
    val chrome = navigationPresentationFor(
        if (currentRoute == "settings/privacy") "settings" else currentRoute,
    )
    val immersive = chrome.level == MichiScreenLevel.IMMERSIVE
    val miniPlayerVisible = chrome.showMiniPlayer
    // Reserve mini player height only when a track actually exists.
    val sessionManager: PlaybackSessionManager = koinInject()
    val sessionState by sessionManager.sessionState.collectAsStateWithLifecycle()
    val pendingConfirmation by sessionManager.pendingQueueConfirmation.collectAsStateWithLifecycle()
    val audioController: AudioController = koinInject()
    val localState by audioController.state.collectAsStateWithLifecycle()
    val presentation = resolvePlaybackPresentation(sessionState, localState)
    val hasMiniPlayer = presentation.hasActiveTrack
    val reducedMotion = LocalVisualPreferences.current.reducedMotion || LocalVisualPreferences.current.highContrast

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
            // A single shared coordinate space includes the NavHost and the
            // persistent MiniPlayer chrome. Album transitions keep their existing scope.
            SharedTransitionLayout(modifier = Modifier.fillMaxSize()) {
                Box(modifier = Modifier.fillMaxSize()) {
            NavHost(
                navController = navController,
                startDestination = "home",
                // M52: deterministic hand-off prevents ghost text/cards in tab transitions.
                // Do not use crossfade for two full screen destinations rendered together.
                enterTransition = { EnterTransition.None },
                exitTransition = { ExitTransition.None },
                popEnterTransition = { EnterTransition.None },
                popExitTransition = { ExitTransition.None },
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
                // Route-local transitions only; all other destinations retain the M52 hard cut.
                composable(
                    route = "library",
                    enterTransition = {
                        if (aeroAlbumRouteTransitionAllowed(initialState.destination.route, targetState.destination.route, reducedMotion))
                            fadeIn(animationSpec = tween(MichiMotion.durationEmphasized))
                        else EnterTransition.None
                    },
                    exitTransition = {
                        if (aeroAlbumRouteTransitionAllowed(initialState.destination.route, targetState.destination.route, reducedMotion))
                            fadeOut(animationSpec = tween(MichiMotion.durationEmphasized))
                        else ExitTransition.None
                    },
                    popEnterTransition = {
                        if (aeroAlbumRouteTransitionAllowed(initialState.destination.route, targetState.destination.route, reducedMotion))
                            fadeIn(animationSpec = tween(MichiMotion.durationEmphasized))
                        else EnterTransition.None
                    },
                    popExitTransition = {
                        if (aeroAlbumRouteTransitionAllowed(initialState.destination.route, targetState.destination.route, reducedMotion))
                            fadeOut(animationSpec = tween(MichiMotion.durationEmphasized))
                        else ExitTransition.None
                    },
                ) {
                    CompositionLocalProvider(
                        LocalAeroSharedTransitionScope provides this@SharedTransitionLayout,
                        LocalAeroAnimatedVisibilityScope provides this@composable,
                        LocalAeroAlbumShareEnabled provides true,
                    ) {
                        AlbumsScreen(
                            onNavigateToPlaylists = { navController.navigate("playlist") },
                            onNavigateToAlbum = { id -> navController.navigate("album/${android.net.Uri.encode(id)}") },
                            onNavigateToArtist = { credit -> navController.navigate("artist/${encodeArtistCredit(credit)}?enrich=false") },
                            sessionManager = sessionManager,
                        )
                    }
                }
                composable(
                    route = "album/{albumId}",
                    arguments = listOf(navArgument("albumId") { type = NavType.StringType }),
                    enterTransition = {
                        if (aeroAlbumRouteTransitionAllowed(initialState.destination.route, targetState.destination.route, reducedMotion))
                            fadeIn(animationSpec = tween(MichiMotion.durationEmphasized))
                        else EnterTransition.None
                    },
                    exitTransition = {
                        if (aeroAlbumRouteTransitionAllowed(initialState.destination.route, targetState.destination.route, reducedMotion))
                            fadeOut(animationSpec = tween(MichiMotion.durationEmphasized))
                        else ExitTransition.None
                    },
                    popEnterTransition = {
                        if (aeroAlbumRouteTransitionAllowed(initialState.destination.route, targetState.destination.route, reducedMotion))
                            fadeIn(animationSpec = tween(MichiMotion.durationEmphasized))
                        else EnterTransition.None
                    },
                    popExitTransition = {
                        if (aeroAlbumRouteTransitionAllowed(initialState.destination.route, targetState.destination.route, reducedMotion))
                            fadeOut(animationSpec = tween(MichiMotion.durationEmphasized))
                        else ExitTransition.None
                    },
                ) { entry ->
                    val albumId = entry.arguments?.getString("albumId") ?: return@composable
                    // Artists can also open albums, but they have no matching mosaic tile.
                    val openedFromLibrary = navController.previousBackStackEntry?.destination?.route == "library"
                    CompositionLocalProvider(
                        LocalAeroSharedTransitionScope provides this@SharedTransitionLayout,
                        LocalAeroAnimatedVisibilityScope provides this@composable,
                        LocalAeroAlbumShareEnabled provides openedFromLibrary,
                    ) {
                        AlbumDetailScreen(
                            albumId = albumId,
                            onBack = { navController.popBackStack() },
                            backLabel = albumDetailBackLabel(navController.previousBackStackEntry?.destination?.route),
                            sessionManager = sessionManager,
                        )
                    }
                }
                composable(
                    route = "artist/{artistKey}?enrich={enrich}",
                    arguments = listOf(
                        navArgument("artistKey") { type = NavType.StringType },
                        navArgument("enrich") { type = NavType.BoolType; defaultValue = false },
                    ),
                ) { entry ->
                    val artistCredit = decodeArtistCredit(entry.arguments?.getString("artistKey")) ?: return@composable
                    ArtistDetailScreen(
                        artistCredit = artistCredit,
                        onBack = { navController.popBackStack() },
                        onNavigateToAlbum = { id -> navController.navigate("album/${android.net.Uri.encode(id)}") },
                        initialEnrichment = entry.arguments?.getBoolean("enrich") ?: false,
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
                        onNavigateToRemote = { navController.navigate("remote") },
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
                    NowPlayingScreen(onBack = { navController.popBackStack() })
                }
                composable("settings") {
                    SettingsScreen(
                        onNavigateToDiagnostics = { navController.navigate("diagnostics") },
                        onNavigateBack = { navController.popBackStack() },
                        onReopenOnboarding = onReopenOnboarding,
                        onboardingSnapshot = onboardingSnapshot,
                        onNavigateToDevices = { navController.navigate("devices") },
                        onNavigateToAudioRoute = { navController.navigate("audio-route") },
                    )
                }
                composable("settings/privacy") {
                    SettingsScreen(
                        initialSection = org.michimusic.mobile.ui.screens.SettingsSection.PRIVACY,
                        onNavigateToDiagnostics = { navController.navigate("diagnostics") },
                        onNavigateBack = { navController.popBackStack() },
                        onReopenOnboarding = onReopenOnboarding,
                        onboardingSnapshot = onboardingSnapshot,
                        onNavigateToDevices = { navController.navigate("devices") },
                        onNavigateToAudioRoute = { navController.navigate("audio-route") },
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
                composable("remote") {
                    RemoteScreen(
                        onNavigateToSync = { navController.navigate("devices") },
                        onBack = { navController.popBackStack() },
                    )
                }
                composable("sync") { SyncScreen(onNavigateToSynced = { navController.navigate("synced") }) }
            }

                    // Player artwork uses local transitions only. The shared
                    // coordinate space remains solely for the album route pair.
                    MiniPlayer(
                        modifier = Modifier.align(Alignment.BottomCenter),
                        onClick = {
                            if (currentRoute != "nowplaying") {
                                navController.navigate("nowplaying") { launchSingleTop = true }
                            }
                        },
                        visible = miniPlayerVisible && hasMiniPlayer,
                    )
                }
            }

            pendingConfirmation?.let { conf ->
                org.michimusic.mobile.ui.components.RemoteQueueConfirmationDialog(
                    confirmation = conf,
                    onConfirmReplace = { sessionManager.resolvePendingQueueConfirmation(org.michimusic.mobile.playback.RemoteQueueAction.REPLACE) },
                    onConfirmAppend = { sessionManager.resolvePendingQueueConfirmation(org.michimusic.mobile.playback.RemoteQueueAction.APPEND) },
                    onDismiss = { sessionManager.resolvePendingQueueConfirmation(org.michimusic.mobile.playback.RemoteQueueAction.CANCEL) },
                )
            }
        }
    }
}
