package org.michimusic.mobile.ui.screens

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
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import org.michimusic.mobile.ui.components.coverStyleFor
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
) {
    val allTracks by viewModel.allTracks.collectAsState()
    val recentTracks by viewModel.recentTracks.collectAsState()
    val topTracks by viewModel.topTracks.collectAsState()
    val favoriteTracks by viewModel.favoriteTracks.collectAsState()
    val playlists by viewModel.playlists.collectAsState()

    val sessionState by sessionManager.sessionState.collectAsState()
    val activeEndpoint = sessionState.activeEndpoint

    val playerState by audioController.state.collectAsState()

    val currentTrack = sessionState.currentTrack ?: playerState.currentTrack
    val isPlaying = sessionState.isPlaying
    // The hero already represents the active song. Avoid repeating it below.
    val visibleRecent = remember(recentTracks, currentTrack?.id) {
        recentTracks.filterNot { it.id == currentTrack?.id }.distinctBy { it.id }.take(8)
    }
    // No recommendation engine is wired to this screen yet. Present honest library picks,
    // never replay the active or recently-played tracks as fake suggestions.
    val libraryPicks = remember(allTracks, recentTracks, currentTrack?.id) {
        val excluded = recentTracks.mapTo(mutableSetOf()) { it.id }
        currentTrack?.id?.let(excluded::add)
        allTracks.filterNot { it.id in excluded }.distinctBy { it.id }.take(6)
    }

    val isLoading by viewModel.isLoading.collectAsState()
    val errorMessage by viewModel.error.collectAsState()

    var showCreatePlaylist by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        viewModel.loadMedia()
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(SurfaceObsidian),
    ) {
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
                        .background(org.michimusic.mobile.ui.theme.ErrorColor.copy(alpha = 0.12f))
                        .testTag("error_message")
                        .padding(8.dp)
                ) {
                    Text(text = errMsg, color = org.michimusic.mobile.ui.theme.ErrorColor,
                        style = MichiTypography.metadata)
                }
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

            // Scrollable Content
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("home_scroll_list"),
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                // 1. Continue Listening (Hero section based on real active session)
                item {
                    if (currentTrack != null) {
                        ContinueListeningCard(
                            track = currentTrack,
                            endpoint = activeEndpoint,
                            isPlaying = isPlaying,
                            isRemote = sessionState.isRemoteActive,
                            onPlayPause = {
                                sessionManager.playPause()
                            },
                            onContinueHere = {
                                sessionManager.handoffTo(PlaybackEndpoint.LocalPhone) { success, msg ->
                                    scope.launch { snackbarHostState.showSnackbar(msg) }
                                }
                            },
                        )
                    } else if (allTracks.isNotEmpty()) {
                        ActionButtonsSection(
                            onQuickPlay = { sessionManager.playQueue(allTracks, 0) },
                            onShuffle = { sessionManager.playQueue(allTracks.shuffled(), 0) },
                        )
                    } else {
                        EmptyLibraryHomeCard()
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

                // 4. Quick Picks Showcase
                if (recentTracks.isNotEmpty() && libraryPicks.isNotEmpty()) {
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
            val isCreatingPlaylist by viewModel.isCreatingPlaylist.collectAsState()
            val createPlaylistError by viewModel.createPlaylistError.collectAsState()
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
            .height(56.dp)
            .padding(horizontal = 20.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column {
            Text(
                text = "MICHI MUSIC",
                style = MichiTypography.screenEyebrow,
            )
            Text(
                text = "Inicio",
                style = MichiTypography.screenTitle,
            )
        }

        IconButton(
            onClick = onSettingsClick,
            modifier = Modifier
                .size(MichiSpacing.minTouchTarget)
                .testTag("home_settings_button"),
        ) {
            Icon(
                imageVector = Icons.Filled.Settings,
                contentDescription = "Ajustes",
                tint = PureWhite,
                modifier = Modifier.size(24.dp),
            )
        }
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
    GlassCard(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("continue_listening_card"),
        backgroundColor = GlassFillHigh,
        borderColor = if (isRemote) TertiaryCyan.copy(alpha = 0.35f) else PrimaryPinkContainer.copy(alpha = 0.35f),
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
                    IconButton(
                        onClick = onPlayPause,
                        modifier = Modifier.size(MichiSpacing.minTouchTarget),
                    ) {
                        Icon(
                            imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            contentDescription = if (isPlaying) "Pausar" else "Reproducir",
                            tint = PureWhite,
                            modifier = Modifier.size(28.dp),
                        )
                    }
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
    GlassCard(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("home_empty_library"),
        backgroundColor = GlassFillHigh,
        borderColor = GlassBorderLow,
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
            Text(
                text = title,
                style = MichiTypography.sectionTitle,
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
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(MichiShapes.md)
                .border(
                    width = if (isCurrent) 1.dp else 0.65.dp,
                    color = if (isCurrent) PrimaryPinkContainer.copy(alpha = 0.75f) else GlassBorderLow,
                    shape = MichiShapes.md,
                ),
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
            Text(
                text = "Tu Colección",
                style = MichiTypography.sectionTitle,
            )
            IconButton(
                onClick = onCreatePlaylist,
                modifier = Modifier.size(MichiSpacing.minTouchTarget),
            ) {
                Icon(
                    imageVector = Icons.Filled.Add,
                    contentDescription = "Crear Playlist",
                    tint = PureWhite,
                    modifier = Modifier.size(22.dp),
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(horizontal = 2.dp),
        ) {
            item { PlaylistTile(
                title = "Favoritos",
                subtitle = "$favoritesCount pistas",
                icon = Icons.Filled.Star,
                accentColor = PrimaryPinkContainer,
                modifier = Modifier.width(MichiSpacing.albumTileWidth),
                onClick = { onPlaylistClick("favorites") },
            ) }
            item { PlaylistTile(
                title = "Más Escuchadas",
                subtitle = "$topTracksCount temas",
                icon = Icons.Filled.PlayArrow,
                accentColor = SecondaryPurple,
                modifier = Modifier.width(MichiSpacing.albumTileWidth),
                onClick = { onPlaylistClick("top") },
            ) }
            item { PlaylistTile(
                title = "Recientes",
                subtitle = "$recentTracksCount pistas",
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
                items(playlists, key = { it.id }) { playlist ->
                    PlaylistTile(
                        title = playlist.name,
                        subtitle = "${playlist.trackCount} canciones",
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
    GlassCard(
        modifier = modifier.heightIn(min = 82.dp),
        backgroundColor = GlassFillLow,
        borderColor = GlassBorderLow,
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
        Text(
            text = "Explora tu biblioteca",
            style = MichiTypography.sectionTitle,
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
