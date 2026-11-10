package org.michimusic.mobile.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import org.michimusic.mobile.playback.resolvePlaybackPresentation
import org.michimusic.mobile.ui.components.premium.MichiIconAction
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.pluralStringResource
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import org.michimusic.core.models.Playlist
import org.michimusic.core.models.Track
import org.michimusic.mobile.playback.PlaybackEndpoint
import org.michimusic.mobile.playback.PlaybackSessionManager
import org.michimusic.mobile.screens.AlbumsViewModel
import org.michimusic.mobile.ui.components.AlbumArtView
import org.michimusic.mobile.ui.components.CreatePlaylistDialog
import org.michimusic.mobile.ui.components.EqualizerWaveBars
import org.michimusic.mobile.ui.components.GlassCard
import org.michimusic.mobile.ui.components.AeroCard
import org.michimusic.mobile.ui.components.AeroSurface
import org.michimusic.mobile.ui.components.AeroArtworkEmphasis
import org.michimusic.mobile.ui.components.AeroHomeHeroCarousel
import org.michimusic.mobile.ui.components.AeroSectionHeading
import org.michimusic.mobile.ui.components.AeroPremiumBackdrop
import org.michimusic.mobile.ui.components.coverStyleFor
import org.michimusic.mobile.ui.theme.AeroFinish
import org.michimusic.mobile.ui.theme.AeroSurfaceRole
import org.michimusic.mobile.ui.theme.LocalVisualPreferences
import org.michimusic.mobile.ui.theme.GlassBorderLow
import org.michimusic.mobile.ui.theme.GlassFillHigh
import org.michimusic.mobile.ui.theme.GlassFillLow
import org.michimusic.mobile.ui.theme.MichiShapes
import org.michimusic.mobile.ui.theme.MichiSpacing
import org.michimusic.mobile.ui.theme.MichiTypography
import org.michimusic.mobile.ui.theme.OnSurfaceVariant
import org.michimusic.mobile.ui.theme.PrimaryPinkContainer
import org.michimusic.mobile.ui.theme.PureWhite
import org.michimusic.mobile.ui.theme.SecondaryPurple
import org.michimusic.mobile.ui.theme.SurfaceObsidian
import org.michimusic.mobile.ui.theme.TertiaryCyan
import org.michimusic.mobile.ui.theme.TextSecondary
import org.michimusic.player.AudioController
import org.michimusic.mobile.R

@Composable
fun HomeScreen(
    onNavigateToSearch: () -> Unit = {},
    onNavigateToSettings: () -> Unit = {},
    onNavigateToDevices: () -> Unit = {},
    onNavigateToPlaylist: (String) -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: AlbumsViewModel = koinViewModel(),
    sessionManager: PlaybackSessionManager = koinInject(),
    audioController: AudioController = koinInject(),
    historyDao: org.michimusic.data.cache.AppDao = koinInject(),
) {
    val allTracks by viewModel.allTracks.collectAsStateWithLifecycle()
    val albums by viewModel.albums.collectAsStateWithLifecycle()
    val recentTracks by viewModel.recentTracks.collectAsStateWithLifecycle()
    val topTracks by viewModel.topTracks.collectAsStateWithLifecycle()
    val favoriteTracks by viewModel.favoriteTracks.collectAsStateWithLifecycle()
    val playlists by viewModel.playlists.collectAsStateWithLifecycle()
    val appContext = LocalContext.current.applicationContext
    val playlistVisualStore = remember(appContext) { PlaylistVisualStore(appContext) }
    val visualRevision by PlaylistVisualStore.revision.collectAsStateWithLifecycle()
    var pinnedPlaylistIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    LaunchedEffect(playlists, visualRevision) {
        pinnedPlaylistIds = playlistVisualStore.readMany(playlists.map { it.id })
            .filterValues { it.pinned }.keys
    }

    val sessionState by sessionManager.sessionState.collectAsStateWithLifecycle()
    val activeEndpoint = sessionState.activeEndpoint

    val playerState by audioController.state.collectAsStateWithLifecycle()
    val presentation = resolvePlaybackPresentation(sessionState, playerState)
    val currentTrack = presentation.currentTrack
    val isPlaying = presentation.isPlaying
    // The hero already represents the active song. Avoid repeating it below.
    val visibleRecent = remember(recentTracks, currentTrack?.id) {
        recentTracks.filterNot { it.id == currentTrack?.id }.distinctBy { it.id }.take(8)
    }
    // No recommendation engine is wired to this screen yet. Present honest library picks,
    // never replay the active or recently-played tracks as fake suggestions.
    val libraryPicks = remember(allTracks, recentTracks, currentTrack?.id) {
        val excluded = recentTracks.mapTo(mutableSetOf()) { it.id }
        currentTrack?.id?.let(excluded::add)
        val ids = variedHomeLibraryPickIds(
            allTracks.map { HomeEditorialCandidate(it.id, it.artist, it.album) }, excluded,
        )
        val indexed = allTracks.associateBy { it.id }
        ids.mapNotNull { indexed[it] }
    }
    val homeHeroItems = remember(allTracks, albums, playlists, recentTracks, currentTrack?.id) {
        buildHomeHeroItems(
            allTracks = allTracks,
            albums = albums,
            playlists = playlists,
            recentIds = recentTracks.mapTo(mutableSetOf()) { it.id },
            currentTrackId = currentTrack?.id,
        )
    }
    val tracksById = remember(allTracks) { allTracks.associateBy { it.id } }

    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
    val errorMessage by viewModel.error.collectAsStateWithLifecycle()
    val mediaState by viewModel.mediaState.collectAsStateWithLifecycle()
    org.michimusic.mobile.ui.components.MediaPermissionWatcher(mediaState) {
        viewModel.loadMedia(forceRefresh = true)
    }
    var retryPending by remember { mutableStateOf(false) }
    LaunchedEffect(isLoading, errorMessage) {
        if (isLoading || errorMessage == null) retryPending = false
    }

    var showCreatePlaylist by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val launchHistoryStore = remember(appContext) { PlaylistLaunchHistoryStore(appContext) }
    var lastAcceptedPlaylistId by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(viewModel) {
        viewModel.loadMedia()
    }
    LaunchedEffect(playlists, historyDao) {
        val enabled = runCatching {
            historyDao.getSetting("listening_history_enabled") != "false"
        }.getOrDefault(false)
        if (!enabled) launchHistoryStore.clear()
        val history = if (enabled) launchHistoryStore.read() else null
        lastAcceptedPlaylistId = validPlaylistContinuityId(history, enabled,
            playlists.associate { it.id to it.trackCount })
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(SurfaceObsidian),
    ) {
        AeroPremiumBackdrop(accent = PrimaryPinkContainer)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding(),
        ) {
            // Simplified Top App Bar - stays visible during loading
            HomeTopBar(
                onSettingsClick = onNavigateToSettings,
            )

            // Error message
            errorMessage?.let { errMsg ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 10.dp)
                        .clip(MichiShapes.md)
                        .background(org.michimusic.mobile.ui.theme.GlassFillHigh)
                        .border(
                            1.dp,
                            org.michimusic.mobile.ui.theme.ErrorColor.copy(alpha = 0.62f),
                            MichiShapes.md,
                        )
                        .testTag("error_message")
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            text = if (mediaState == org.michimusic.mobile.screens.MediaLibraryState.QUERY_ERROR ||
                                mediaState == org.michimusic.mobile.screens.MediaLibraryState.PERMISSION_DENIED) {
                                "No se pudo cargar tu biblioteca"
                            } else "Biblioteca parcialmente disponible",
                            color = PureWhite,
                            fontWeight = FontWeight.SemiBold,
                            style = MichiTypography.cardTitle,
                        )
                        Text(
                            text = errMsg,
                            color = PureWhite.copy(alpha = 0.84f),
                            style = MichiTypography.metadata,
                        )
                        androidx.compose.material3.TextButton(
                            onClick = {
                                if (!isLoading && !retryPending) {
                                    retryPending = true
                                    viewModel.loadMedia()
                                }
                            },
                            enabled = !isLoading && !retryPending,
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .clip(MichiShapes.sm)
                                .background(PrimaryPinkContainer.copy(alpha = 0.10f))
                                .border(0.75.dp, PrimaryPinkContainer.copy(alpha = 0.42f), MichiShapes.sm)
                                .testTag("home_retry_loading_button"),
                        ) {
                            Text(
                                "Reintentar carga",
                                color = if (!isLoading && !retryPending) PureWhite else PureWhite.copy(alpha = 0.64f),
                            )
                        }
                    }
                }
            }

            // Media truth: a denied permission or a failed query is never an empty library.
            // The panel owns the permission request, App info shortcut and resume check.
            if (mediaState == org.michimusic.mobile.screens.MediaLibraryState.PERMISSION_DENIED ||
                mediaState == org.michimusic.mobile.screens.MediaLibraryState.QUERY_ERROR
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 10.dp),
                ) {
                    org.michimusic.mobile.ui.components.MediaTruthPanel(
                        state = mediaState,
                        onPermissionAvailable = { viewModel.loadMedia(forceRefresh = true) },
                        onRetry = { viewModel.loadMedia(forceRefresh = true) },
                    )
                }
                return@Column
            }

            if (isLoading) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag("loading_indicator"),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(
                        color = PrimaryPinkContainer,
                        strokeWidth = 4.dp
                    )
                }
                return@Column
            }

            // Editorial music-first composition; only content scrolls, dock stays global.
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("home_scroll_list"),
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                // M52: editorial collection hero; the current local track already lives
                // in the MiniPlayer and must not occupy a second redundant hero.
                item {
                    if (homeHeroItems.isNotEmpty()) {
                        AeroHomeHeroCarousel(
                            items = homeHeroItems,
                            onPrimary = { item ->
                                when (item.kind) {
                                    HomeHeroKind.TRACK -> {
                                        val index = allTracks.indexOfFirst { it.id == item.sourceId }
                                        if (index >= 0) sessionManager.playQueue(allTracks, index)
                                        else scope.launch {
                                            snackbarHostState.showSnackbar("La pista ya no está disponible")
                                        }
                                    }
                                    HomeHeroKind.ALBUM -> {
                                        val albumTracks = item.trackIds.mapNotNull(tracksById::get)
                                        if (albumTracks.isNotEmpty()) {
                                            sessionManager.playQueue(albumTracks, 0)
                                        } else {
                                            scope.launch {
                                                snackbarHostState.showSnackbar("El álbum ya no está disponible")
                                            }
                                        }
                                    }
                                    HomeHeroKind.PLAYLIST -> onNavigateToPlaylist(item.sourceId)
                                }
                            },
                            onPlayAll = { sessionManager.playQueue(allTracks, 0) },
                            onShuffle = { sessionManager.playQueue(allTracks.shuffled(), 0) },
                        )
                    } else if (mediaState == org.michimusic.mobile.screens.MediaLibraryState.EMPTY) {
                        EmptyLibraryHomeCard()
                    }
                }
                // Remote playback has distinct handoff semantics and retains a
                // contextual control card rather than duplicating local controls.
                if (currentTrack != null && sessionState.isRemoteActive) {
                    item {
                        ContinueListeningCard(
                            track = currentTrack,
                            endpoint = activeEndpoint,
                            isPlaying = isPlaying,
                            isRemote = true,
                            onPlayPause = { sessionManager.playPause() },
                            onContinueHere = {
                                sessionManager.handoffTo(PlaybackEndpoint.LocalPhone) { _, msg ->
                                    scope.launch { snackbarHostState.showSnackbar(msg) }
                                }
                            },
                        )
                    }
                }

                // 2. Recently Played Carousel (Honest labels: "Recientemente Escuchadas" strictly for history, or "De tu biblioteca" as general suggestions)
                if (visibleRecent.isNotEmpty()) {
                    item {
                        RecentlyPlayedSection(
                            title = "Recientemente Escuchadas",
                            tracks = visibleRecent,
                            currentTrack = currentTrack,
                            isPlaying = isPlaying,
                            onTrackClick = { track ->
                                val index = allTracks.indexOfFirst { it.id == track.id }
                                // A stale suggestion must never play the first unrelated track.
                                if (index >= 0) {
                                    sessionManager.playQueue(allTracks, index)
                                } else {
                                    scope.launch { snackbarHostState.showSnackbar("Esta canción ya no está disponible en la biblioteca") }
                                }
                            },
                        )
                    }
                } else if (recentTracks.isEmpty() && allTracks.any { it.id != currentTrack?.id }) {
                    item {
                        RecentlyPlayedSection(
                            title = "De tu biblioteca",
                            tracks = allTracks.filterNot { it.id == currentTrack?.id }.take(8),
                            currentTrack = currentTrack,
                            isPlaying = isPlaying,
                            onTrackClick = { track ->
                                val index = allTracks.indexOfFirst { it.id == track.id }
                                // A stale suggestion must never play the first unrelated track.
                                if (index >= 0) {
                                    sessionManager.playQueue(allTracks, index)
                                } else {
                                    scope.launch { snackbarHostState.showSnackbar("Esta canción ya no está disponible en la biblioteca") }
                                }
                            },
                        )
                    }
                }

                // 3. Your Music / Playlists Grid
                item {
                    YourPlaylistsSection(
                        totalTracks = allTracks.size,
                        favoritesCount = favoriteTracks.size,
                        topTracksCount = topTracks.size,
                        recentTracksCount = recentTracks.size,
                        playlists = playlists,
                        pinnedPlaylistIds = pinnedPlaylistIds,
                        onCreatePlaylist = { showCreatePlaylist = true },
                        onPlaylistClick = { type ->
                            when (type) {
                                "favorites" -> if (favoriteTracks.isNotEmpty()) sessionManager.playQueue(favoriteTracks, 0)
                                "top" -> if (topTracks.isNotEmpty()) sessionManager.playQueue(topTracks, 0)
                                "recent" -> if (recentTracks.isNotEmpty()) sessionManager.playQueue(recentTracks, 0)
                                else -> if (allTracks.isNotEmpty()) sessionManager.playQueue(allTracks, 0)
                            }
                        },
                        onNavigateToPlaylist = onNavigateToPlaylist,
                    )
                }

                // A real previously accepted queue request is the ONLY authority for
                // this continuity shortcut. Never infer playlist provenance from a track.
                val lastPlaylist = lastAcceptedPlaylistId?.let { id ->
                    playlists.singleOrNull { it.id == id && it.trackCount > 0 }
                }
                if (lastPlaylist != null) {
                    item(key = "playlist_continuity") {
                        AeroCard(
                            modifier = Modifier.fillMaxWidth().testTag("home_playlist_continuity"),
                            finish = AeroFinish.Satin,
                            onClick = { onNavigateToPlaylist(lastPlaylist.id) },
                        ) {
                            Column(Modifier.fillMaxWidth().padding(14.dp)) {
                                Text("ÚLTIMA PLAYLIST ENVIADA", style = MichiTypography.screenEyebrow)
                                Text(lastPlaylist.name, style = MichiTypography.sectionTitle,
                                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text("Abre la lista para continuar. La posición exacta no se conserva aquí.",
                                    style = MichiTypography.metadata, color = TextSecondary)
                            }
                        }
                    }
                }

                // The honest empty state remains discoverable when all library tracks
                // are already recent. Never recycle them as invented "new picks".
                if (libraryPicks.isEmpty() && allTracks.isNotEmpty() && recentTracks.isNotEmpty()) {
                    item(key = "home_explore_all_recent") {
                        AeroCard(
                            modifier = Modifier.fillMaxWidth().testTag("home_explore_all_recent"),
                            finish = AeroFinish.Satin,
                            onClick = onNavigateToSearch,
                        ) {
                            Column(Modifier.fillMaxWidth().padding(14.dp)) {
                                Text("EXPLORA TU BIBLIOTECA", style = MichiTypography.screenEyebrow)
                                Text("Ya escuchaste las selecciones recientes",
                                    style = MichiTypography.sectionTitle)
                                Text("Buscar otras canciones o artistas de tu colección",
                                    style = MichiTypography.metadata, color = TextSecondary)
                            }
                        }
                    }
                }
                // 4. Quick Picks Showcase
                if (libraryPicks.isNotEmpty()) {
                    item {
                        QuickPicksSection(
                            tracks = libraryPicks,
                            currentTrack = currentTrack,
                            isPlaying = isPlaying,
                            onTrackClick = { track ->
                                val index = allTracks.indexOfFirst { it.id == track.id }
                                // A stale suggestion must never play the first unrelated track.
                                if (index >= 0) {
                                    sessionManager.playQueue(allTracks, index)
                                } else {
                                    scope.launch { snackbarHostState.showSnackbar("Esta canción ya no está disponible en la biblioteca") }
                                }
                            },
                        )
                    }
                }
            }
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 72.dp),
        )

        if (showCreatePlaylist) {
            val isCreatingPlaylist by viewModel.isCreatingPlaylist.collectAsStateWithLifecycle()
            val createPlaylistError by viewModel.createPlaylistError.collectAsStateWithLifecycle()
            CreatePlaylistDialog(
                isCreating = isCreatingPlaylist,
                errorMessage = createPlaylistError,
                onCreate = { name ->
                    viewModel.createPlaylist(name) { result ->
                        if (result is org.michimusic.mobile.screens.CreatePlaylistResult.Success) {
                            showCreatePlaylist = false
                            scope.launch {
                                snackbarHostState.showSnackbar("Playlist \"$name\" creada")
                            }
                        }
                    }
                },
                onDismiss = { showCreatePlaylist = false },
            )
        }
    }
}

@Composable
private fun HomeTopBar(
    onSettingsClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .padding(horizontal = 20.dp, vertical = 6.dp)
            .testTag("home_top_bar"),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "MICHI MUSIC",
                style = MichiTypography.screenEyebrow,
            )
            Text(
                text = "Tu música",
                style = MichiTypography.screenTitle,
            )
        }

        MichiIconAction(
            icon = Icons.Filled.Settings,
            contentDescription = "Ajustes",
            onClick = onSettingsClick,
            tint = PureWhite,
            iconSize = 24.dp,
            minTargetSize = MichiSpacing.minTouchTarget,
            testTag = "home_settings_button",
        )
    }
}

@Composable
private fun ContinueListeningCard(
    track: Track,
    endpoint: PlaybackEndpoint,
    isPlaying: Boolean,
    isRemote: Boolean,
    onPlayPause: () -> Unit,
    onContinueHere: () -> Unit,
) {
    // The single "continue" focus panel is machined graphite; the endpoint
    // retains its own evidence color instead of becoming a generic glass card.
    AeroSurface(
        role = AeroSurfaceRole.Raised,
        finish = AeroFinish.Anodized,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("continue_listening_card"),
        borderStroke = BorderStroke(
            width = if (LocalVisualPreferences.current.highContrast) 1.2.dp else 0.75.dp,
            color = if (LocalVisualPreferences.current.highContrast) Color.White.copy(alpha = 0.85f)
                else if (isRemote) TertiaryCyan.copy(alpha = 0.35f)
                else PrimaryPinkContainer.copy(alpha = 0.35f),
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "CONTINUAR ESCUCHANDO",
                    style = MichiTypography.microLabel,
                    color = if (isRemote) TertiaryCyan else PrimaryPinkContainer,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = "● ${endpoint.name}",
                    style = MichiTypography.microLabel,
                    color = if (endpoint.isLocal) TertiaryCyan else PrimaryPinkContainer,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                AlbumArtView(
                    coverStyle = coverStyleFor(track.coverId.ifEmpty { track.title }),
                    imageModel = track.coverId.takeIf { it.isNotBlank() },
                    fallbackMediaUri = track.filepath,
                    modifier = Modifier
                        .size(68.dp)
                        .clip(MichiShapes.sm),
                )

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = track.title,
                        style = MichiTypography.cardTitle,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = track.artist,
                        style = MichiTypography.metadata,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                if (!isRemote) {
                    MichiIconAction(
                        icon = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = if (isPlaying) "Pausar" else "Reproducir",
                        onClick = onPlayPause,
                        tint = PureWhite,
                        iconSize = 28.dp,
                        minTargetSize = MichiSpacing.minTouchTarget,
                    )
                }
            }
            if (isRemote) {
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedButton(
                    onClick = onContinueHere,
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    shape = MichiShapes.sm,
                    contentPadding = PaddingValues(horizontal = 12.dp),
                ) {
                    Text("Continuar en este teléfono", color = PureWhite, style = MichiTypography.cardTitle)
                }
            }
        }
    }
}

/** An empty collection is not a playable queue. Never show dead transport controls. */
@Composable
private fun EmptyLibraryHomeCard() {
    AeroSurface(
        role = AeroSurfaceRole.Content,
        finish = AeroFinish.Satin,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("home_empty_library"),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.LibraryMusic,
                contentDescription = null,
                tint = PrimaryPinkContainer,
                modifier = Modifier.size(28.dp),
            )
            Text(
                text = "Tu biblioteca está vacía",
                style = MichiTypography.cardTitle,
                color = PureWhite,
            )
            Text(
                text = "Cuando agregues música al teléfono, aparecerá aquí para reproducirla.",
                style = MichiTypography.metadata,
                color = OnSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ActionButtonsSection(
    onQuickPlay: () -> Unit,
    onShuffle: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // Quick Play Button
        Box(
            modifier = Modifier
                .weight(1f)
                .height(48.dp)
                .clip(MichiShapes.sm)
                .background(PrimaryPinkContainer)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = ripple(color = PureWhite),
                    role = Role.Button,
                    onClick = onQuickPlay,
                )
                .testTag("quick_play_button"),
            contentAlignment = Alignment.Center,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.PlayArrow,
                    contentDescription = null,
                    tint = PureWhite,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "Reproducir todo",
                    color = PureWhite,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }

        // Shuffle Button
        GlassCard(
            modifier = Modifier
                .weight(1f)
                .height(48.dp)
                .testTag("shuffle_button"),
            shape = MichiShapes.sm,
            onClick = onShuffle,
        ) {
            Row(
                modifier = Modifier.fillMaxSize(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.Shuffle,
                    contentDescription = null,
                    tint = PureWhite,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "Aleatorio",
                    color = PureWhite,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

@Composable
private fun RecentlyPlayedSection(
    title: String = "Recientemente Escuchadas",
    tracks: List<Track>,
    currentTrack: Track?,
    isPlaying: Boolean,
    onTrackClick: (Track) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AeroSectionHeading(
                title = title,
                eyebrow = "VOLVER A ESCUCHAR",
                modifier = Modifier.testTag("recently_played_title"),
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            contentPadding = PaddingValues(horizontal = 4.dp),
        ) {
            items(tracks) { track ->
                val isCurrent = currentTrack?.id == track.id
                RecentTrackCard(
                    track = track,
                    isCurrent = isCurrent,
                    isPlaying = isPlaying && isCurrent,
                    onClick = { onTrackClick(track) },
                )
            }
        }
    }
}

@Composable
private fun RecentTrackCard(
    track: Track,
    isCurrent: Boolean,
    isPlaying: Boolean,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .width(132.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(color = PrimaryPinkContainer),
                onClick = onClick,
            )
            .testTag("recent_track_${track.id}"),
    ) {
        org.michimusic.mobile.ui.components.AeroArtworkMat(
            modifier = Modifier.fillMaxWidth().aspectRatio(1f),
            cornerRadius = 14.dp,
            inset = 3.dp,
            emphasis = AeroArtworkEmphasis.Featured,
        ) {
            AlbumArtView(
                coverStyle = coverStyleFor(track.coverId.ifEmpty { track.title }),
                imageModel = track.coverId.takeIf { it.isNotBlank() },
                fallbackMediaUri = track.filepath,
                modifier = Modifier.fillMaxSize(),
            )

            if (isCurrent && isPlaying) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.25f)),
                    contentAlignment = Alignment.Center,
                ) {
                    EqualizerWaveBars(
                        isPlaying = true,
                        color = PrimaryPinkContainer,
                        modifier = Modifier.size(28.dp),
                    )
                }
            }
            if (isCurrent) {
                Box(Modifier.fillMaxSize().border(1.2.dp,
                    PrimaryPinkContainer.copy(alpha = 0.83f), MichiShapes.md))
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = track.title,
            color = if (isCurrent) PrimaryPinkContainer else PureWhite,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )

        Text(
            text = track.artist,
            color = TextSecondary,
            fontSize = 11.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun YourPlaylistsSection(
    totalTracks: Int,
    favoritesCount: Int = 0,
    topTracksCount: Int,
    recentTracksCount: Int,
    playlists: List<Playlist>,
    pinnedPlaylistIds: Set<String>,
    onCreatePlaylist: () -> Unit,
    onPlaylistClick: (String) -> Unit,
    onNavigateToPlaylist: (String) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AeroSectionHeading(
                title = "Colecciones",
                eyebrow = "TUS LISTAS",
            )
            MichiIconAction(
                icon = Icons.Filled.Add,
                contentDescription = "Crear Playlist",
                onClick = onCreatePlaylist,
                tint = PureWhite,
                iconSize = 22.dp,
                minTargetSize = MichiSpacing.minTouchTarget,
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(horizontal = 2.dp),
        ) {
            item { PlaylistTile(
                title = "Favoritos",
                subtitle = pluralStringResource(R.plurals.track_count, favoritesCount, favoritesCount),
                icon = Icons.Filled.Star,
                accentColor = PrimaryPinkContainer,
                modifier = Modifier.width(MichiSpacing.albumTileWidth),
                onClick = { onPlaylistClick("favorites") },
            ) }
            item { PlaylistTile(
                title = "Más Escuchadas",
                subtitle = pluralStringResource(R.plurals.themes_count, topTracksCount, topTracksCount),
                icon = Icons.Filled.PlayArrow,
                accentColor = SecondaryPurple,
                modifier = Modifier.width(MichiSpacing.albumTileWidth),
                onClick = { onPlaylistClick("top") },
            ) }
            item { PlaylistTile(
                title = "Recientes",
                subtitle = pluralStringResource(R.plurals.track_count, recentTracksCount, recentTracksCount),
                icon = Icons.Filled.LibraryMusic,
                accentColor = TertiaryCyan,
                modifier = Modifier.width(MichiSpacing.albumTileWidth),
                onClick = { onPlaylistClick("recent") },
            ) }
        }

        if (playlists.isNotEmpty()) {
            Spacer(modifier = Modifier.height(12.dp))

            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(horizontal = 4.dp),
                modifier = Modifier.testTag("your_playlists_list"),
            ) {
                items(playlists.sortedWith(compareBy<Playlist> { it.id !in pinnedPlaylistIds }
                    .thenBy { it.name.lowercase() }), key = { it.id }) { playlist ->
                    PlaylistTile(
                        title = playlist.name,
                        subtitle = if (playlist.id in pinnedPlaylistIds)
                            "Fijada · ${playlist.trackCount} canciones" else "${playlist.trackCount} canciones",
                        icon = Icons.Filled.LibraryMusic,
                        accentColor = SecondaryPurple,
                        modifier = Modifier
                            .width(MichiSpacing.albumTileWidth)
                            .testTag("playlist_tile_${playlist.id}"),
                        onClick = { onNavigateToPlaylist(playlist.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun PlaylistTile(
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    accentColor: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    // Tiles stay Plain: each icon brings the color, not a competing noisy plate.
    AeroCard(
        modifier = modifier.heightIn(min = 82.dp),
        finish = AeroFinish.Plain,
        onClick = onClick,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(MichiShapes.sm)
                    .background(accentColor.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = accentColor,
                    modifier = Modifier.size(20.dp),
                )
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MichiTypography.cardTitle,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = subtitle,
                    style = MichiTypography.metadata,
                )
            }
        }
    }
}

@Composable
private fun QuickPicksSection(
    tracks: List<Track>,
    currentTrack: Track?,
    isPlaying: Boolean,
    onTrackClick: (Track) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        AeroSectionHeading(
            title = "Explora tu biblioteca",
            eyebrow = "OTRAS PISTAS DISPONIBLES",
        )

        Spacer(modifier = Modifier.height(12.dp))

        tracks.forEach { track ->
            val isCurrent = currentTrack?.id == track.id
            QuickPickTrackRow(
                track = track,
                isCurrent = isCurrent,
                isPlaying = isPlaying && isCurrent,
                onClick = { onTrackClick(track) },
            )
            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

@Composable
private fun QuickPickTrackRow(
    track: Track,
    isCurrent: Boolean,
    isPlaying: Boolean,
    onClick: () -> Unit,
) {
    GlassCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(color = PrimaryPinkContainer),
                onClick = onClick,
            ),
        backgroundColor = if (isCurrent) GlassFillHigh else GlassFillLow,
        borderColor = if (isCurrent) PrimaryPinkContainer.copy(alpha = 0.4f) else GlassBorderLow,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            AlbumArtView(
                coverStyle = coverStyleFor(track.coverId.ifEmpty { track.title }),
                imageModel = track.coverId.takeIf { it.isNotBlank() },
                    fallbackMediaUri = track.filepath,
                modifier = Modifier
                    .size(44.dp)
                    .clip(MichiShapes.xs),
            )

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = track.title,
                    color = if (isCurrent) PrimaryPinkContainer else PureWhite,
                    style = MichiTypography.trackTitle,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "${track.artist} · ${track.album}",
                    style = MichiTypography.metadata,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            if (isCurrent && isPlaying) {
                EqualizerWaveBars(
                    isPlaying = true,
                    color = PrimaryPinkContainer,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}
