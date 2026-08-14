package org.michimusic.mobile.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Headset
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RocketLaunch
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import org.michimusic.core.models.Track
import org.michimusic.mobile.screens.AlbumsViewModel
import org.michimusic.mobile.ui.components.AlbumArtView
import org.michimusic.mobile.ui.components.CreatePlaylistDialog
import org.michimusic.mobile.ui.components.EqualizerDialog
import org.michimusic.mobile.ui.components.EqualizerWaveBars
import org.michimusic.mobile.ui.components.GlassCard
import org.michimusic.mobile.ui.components.coverStyleFor
import org.michimusic.mobile.ui.theme.GlassBorderHigh
import org.michimusic.mobile.ui.theme.GlassBorderLow
import org.michimusic.mobile.ui.theme.GlassFillHigh
import org.michimusic.mobile.ui.theme.GlassFillLow
import org.michimusic.mobile.ui.theme.OnSurfaceVariant
import org.michimusic.mobile.ui.theme.PrimaryPink
import org.michimusic.mobile.ui.theme.PrimaryPinkContainer
import org.michimusic.mobile.ui.theme.PrimaryPinkDim
import org.michimusic.mobile.ui.theme.PureWhite
import org.michimusic.mobile.ui.theme.SecondaryPurple
import org.michimusic.mobile.ui.theme.SurfaceContainerHigh
import org.michimusic.mobile.ui.theme.SurfaceObsidian
import org.michimusic.mobile.ui.theme.TertiaryCyan
import org.michimusic.mobile.ui.theme.TertiaryCyanContainer
import org.michimusic.player.AudioController

@Composable
fun HomeScreen(
    onNavigateToSearch: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val viewModel: AlbumsViewModel = koinViewModel()
    val allTracks by viewModel.allTracks.collectAsState()
    val recentTracks by viewModel.recentTracks.collectAsState()
    val topTracks by viewModel.topTracks.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()

    val audioController: AudioController = koinInject()
    val playerState by audioController.state.collectAsState()
    val currentTrack = playerState.currentTrack
    val isPlaying = playerState.isPlaying

    var searchQuery by remember { mutableStateOf("") }
    var isSearchActive by remember { mutableStateOf(false) }
    var showEqualizer by remember { mutableStateOf(false) }
    var showCreatePlaylist by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.loadMedia()
    }

    val filteredTracks = remember(allTracks, searchQuery) {
        if (searchQuery.isBlank()) {
            allTracks
        } else {
            allTracks.filter {
                it.title.contains(searchQuery, ignoreCase = true) ||
                    it.artist.contains(searchQuery, ignoreCase = true) ||
                    it.album.contains(searchQuery, ignoreCase = true)
            }
        }
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
            // Top App Bar
            HomeTopBar(
                isSearchActive = isSearchActive,
                searchQuery = searchQuery,
                onToggleSearch = {
                    isSearchActive = !isSearchActive
                    if (!isSearchActive) searchQuery = ""
                },
                onSearchChange = { searchQuery = it },
                onEqualizerClick = { showEqualizer = true },
            )

            // Scrollable Content
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("home_scroll_list"),
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 120.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                // Hero Action Buttons
                item {
                    ActionButtonsSection(
                        onQuickPlay = {
                            if (allTracks.isNotEmpty()) {
                                audioController.playQueue(allTracks, 0)
                            }
                        },
                        onShuffle = {
                            if (allTracks.isNotEmpty()) {
                                audioController.playQueue(allTracks.shuffled(), 0)
                            }
                        },
                    )
                }

                // Recently Played Carousel
                if (recentTracks.isNotEmpty() || allTracks.isNotEmpty()) {
                    item {
                        RecentlyPlayedSection(
                            tracks = recentTracks.ifEmpty { allTracks.take(8) },
                            currentTrack = currentTrack,
                            isPlaying = isPlaying,
                            onTrackClick = { track ->
                                val index = allTracks.indexOfFirst { it.id == track.id }.coerceAtLeast(0)
                                audioController.playQueue(allTracks, index)
                            },
                        )
                    }
                }

                // Your Playlists Grid
                item {
                    YourPlaylistsSection(
                        totalTracks = allTracks.size,
                        topTracksCount = topTracks.size,
                        recentTracksCount = recentTracks.size,
                        onCreatePlaylist = { showCreatePlaylist = true },
                        onPlaylistClick = { type ->
                            when (type) {
                                "top" -> if (topTracks.isNotEmpty()) audioController.playQueue(topTracks, 0)
                                "recent" -> if (recentTracks.isNotEmpty()) audioController.playQueue(recentTracks, 0)
                                else -> if (allTracks.isNotEmpty()) audioController.playQueue(allTracks, 0)
                            }
                        },
                    )
                }

                // All Tracks List
                item {
                    AllTracksSection(
                        tracks = filteredTracks,
                        currentTrack = currentTrack,
                        isPlaying = isPlaying,
                        onTrackClick = { track ->
                            val index = allTracks.indexOfFirst { it.id == track.id }.coerceAtLeast(0)
                            audioController.playQueue(allTracks, index)
                        },
                    )
                }
            }
        }

        // Dialogs
        if (showEqualizer) {
            EqualizerDialog(onDismiss = { showEqualizer = false })
        }

        if (showCreatePlaylist) {
            CreatePlaylistDialog(
                onCreate = { name ->
                    // Custom playlist creation logic
                    showCreatePlaylist = false
                },
                onDismiss = { showCreatePlaylist = false },
            )
        }
    }
}

@Composable
private fun HomeTopBar(
    isSearchActive: Boolean,
    searchQuery: String,
    onToggleSearch: () -> Unit,
    onSearchChange: (String) -> Unit,
    onEqualizerClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(64.dp)
            .padding(horizontal = 20.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (!isSearchActive) {
            IconButton(
                onClick = onEqualizerClick,
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(GlassFillLow)
                    .testTag("home_eq_button"),
            ) {
                Icon(
                    imageVector = Icons.Filled.GraphicEq,
                    contentDescription = "Ecualizador",
                    tint = OnSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }

            Text(
                text = "Michi Music",
                color = PrimaryPink,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = (-0.5).sp,
                modifier = Modifier.testTag("home_title"),
            )

            IconButton(
                onClick = onToggleSearch,
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(GlassFillLow)
                    .testTag("home_search_button"),
            ) {
                Icon(
                    imageVector = Icons.Filled.Search,
                    contentDescription = "Buscar",
                    tint = OnSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
        } else {
            // Search Input Field
            OutlinedTextField(
                value = searchQuery,
                onValueChange = onSearchChange,
                placeholder = { Text("Buscar canciones, artistas, álbumes...", color = OnSurfaceVariant) },
                singleLine = true,
                trailingIcon = {
                    IconButton(onClick = onToggleSearch) {
                        Icon(Icons.Filled.Close, contentDescription = "Cerrar Búsqueda", tint = OnSurfaceVariant)
                    }
                },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = PureWhite,
                    unfocusedTextColor = PureWhite,
                    focusedBorderColor = TertiaryCyan,
                    unfocusedBorderColor = GlassBorderLow,
                    focusedContainerColor = GlassFillHigh,
                    unfocusedContainerColor = GlassFillLow,
                ),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("home_search_input"),
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
        // Quick Play (Pink-to-Purple Gradient Button)
        Box(
            modifier = Modifier
                .weight(1f)
                .height(48.dp)
                .drawBehind {
                    drawCircle(
                        color = PrimaryPinkContainer.copy(alpha = 0.35f),
                        radius = size.maxDimension * 0.75f,
                        center = center,
                    )
                }
                .clip(RoundedCornerShape(12.dp))
                .background(
                    Brush.linearGradient(
                        colors = listOf(PrimaryPink, SecondaryPurple),
                    ),
                )
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = ripple(color = PureWhite),
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
                    tint = Color(0xFF370C11),
                    modifier = Modifier.size(20.dp),
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "Reproducir",
                    color = Color(0xFF370C11),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }

        // Shuffle (Glass Card Button)
        GlassCard(
            modifier = Modifier
                .weight(1f)
                .height(48.dp)
                .testTag("shuffle_button"),
            shape = RoundedCornerShape(12.dp),
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
    tracks: List<Track>,
    currentTrack: Track?,
    isPlaying: Boolean,
    onTrackClick: (Track) -> Unit,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = "Escuchadas Recientemente",
            color = PureWhite,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
        )

        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(end = 12.dp),
        ) {
            items(tracks) { track ->
                val isCurrent = track.id == currentTrack?.id

                Column(
                    modifier = Modifier
                        .width(140.dp)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = { onTrackClick(track) },
                        )
                        .testTag("recent_track_${track.id}"),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(140.dp)
                            .then(
                                if (isCurrent) {
                                    Modifier.drawBehind {
                                        drawCircle(
                                            color = TertiaryCyan.copy(alpha = 0.35f),
                                            radius = size.maxDimension * 0.7f,
                                            center = center,
                                        )
                                    }
                                } else {
                                    Modifier
                                },
                            )
                            .clip(RoundedCornerShape(14.dp))
                            .border(
                                width = if (isCurrent) 1.5.dp else 1.dp,
                                color = if (isCurrent) TertiaryCyan else GlassBorderLow,
                                shape = RoundedCornerShape(14.dp),
                            ),
                    ) {
                        AlbumArtView(
                            coverStyle = coverStyleFor(track.coverId.ifEmpty { track.title }),
                            imageModel = track.filepath.ifEmpty { track.coverId },
                            modifier = Modifier.fillMaxSize(),
                            cornerRadius = 14.dp,
                        )

                        if (isCurrent) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(Color(0x55000000)),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.GraphicEq,
                                    contentDescription = null,
                                    tint = TertiaryCyan,
                                    modifier = Modifier.size(36.dp),
                                )
                            }
                        }
                    }

                    Column {
                        Text(
                            text = track.title,
                            color = PureWhite,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = if (isCurrent) "Reproduciendo ahora" else track.artist,
                            color = if (isCurrent) TertiaryCyan else OnSurfaceVariant,
                            fontSize = 11.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun YourPlaylistsSection(
    totalTracks: Int,
    topTracksCount: Int,
    recentTracksCount: Int,
    onCreatePlaylist: () -> Unit,
    onPlaylistClick: (String) -> Unit,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom,
        ) {
            Text(
                text = "Tus Listas y Favoritos",
                color = PureWhite,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
            )
        }

        // 2x2 Grid
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                PlaylistCard(
                    title = "Más Escuchadas",
                    subtitle = "$topTracksCount canciones",
                    icon = Icons.Filled.Headset,
                    iconContainerColor = Color(0xFF583876),
                    iconTint = Color(0xFFF0DBFF),
                    modifier = Modifier.weight(1f),
                    onClick = { onPlaylistClick("top") },
                )
                PlaylistCard(
                    title = "Recientes",
                    subtitle = "$recentTracksCount canciones",
                    icon = Icons.Filled.RocketLaunch,
                    iconContainerColor = Color(0xFF005C62),
                    iconTint = TertiaryCyan,
                    modifier = Modifier.weight(1f),
                    onClick = { onPlaylistClick("recent") },
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                PlaylistCard(
                    title = "Biblioteca Completa",
                    subtitle = "$totalTracks canciones",
                    icon = Icons.Filled.Favorite,
                    iconContainerColor = SurfaceContainerHigh,
                    iconTint = PrimaryPinkDim,
                    modifier = Modifier.weight(1f),
                    onClick = { onPlaylistClick("all") },
                )
                PlaylistCard(
                    title = "Nueva Lista",
                    subtitle = "Crear",
                    icon = Icons.Filled.Add,
                    iconContainerColor = SurfaceContainerHigh,
                    iconTint = PureWhite,
                    modifier = Modifier.weight(1f),
                    onClick = onCreatePlaylist,
                )
            }
        }
    }
}

@Composable
private fun PlaylistCard(
    title: String,
    subtitle: String,
    icon: ImageVector,
    iconContainerColor: Color,
    iconTint: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    GlassCard(
        modifier = modifier.height(68.dp),
        onClick = onClick,
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(iconContainerColor),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = iconTint,
                    modifier = Modifier.size(22.dp),
                )
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = title,
                    color = PureWhite,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = subtitle,
                    color = OnSurfaceVariant,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun AllTracksSection(
    tracks: List<Track>,
    currentTrack: Track?,
    isPlaying: Boolean,
    onTrackClick: (Track) -> Unit,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = "Canciones (${tracks.size})",
            color = PureWhite,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
        )

        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            tracks.forEach { track ->
                val isCurrent = track.id == currentTrack?.id
                var showMenu by remember { mutableStateOf(false) }

                TrackListItem(
                    track = track,
                    isCurrent = isCurrent,
                    isPlaying = isPlaying,
                    onTrackClick = { onTrackClick(track) },
                    onMoreClick = { showMenu = true },
                )

                DropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false },
                    modifier = Modifier.background(SurfaceContainerHigh),
                ) {
                    DropdownMenuItem(
                        text = { Text("Reproducir Siguiente", color = PureWhite) },
                        onClick = { showMenu = false },
                    )
                    DropdownMenuItem(
                        text = { Text("Información del Archivo", color = PureWhite) },
                        onClick = { showMenu = false },
                    )
                }
            }
        }
    }
}

@Composable
private fun TrackListItem(
    track: Track,
    isCurrent: Boolean,
    isPlaying: Boolean,
    onTrackClick: () -> Unit,
    onMoreClick: () -> Unit,
) {
    val shape = RoundedCornerShape(10.dp)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (isCurrent) GlassFillHigh else GlassFillLow)
            .border(
                1.dp,
                if (isCurrent) TertiaryCyan.copy(alpha = 0.5f) else GlassBorderLow,
                shape,
            )
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(color = TertiaryCyan),
                onClick = onTrackClick,
            )
            .testTag("track_row_${track.id}"),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (isCurrent) {
                Box(
                    modifier = Modifier
                        .width(4.dp)
                        .height(28.dp)
                        .clip(RoundedCornerShape(topEnd = 4.dp, bottomEnd = 4.dp))
                        .background(TertiaryCyan),
                )
                Spacer(modifier = Modifier.width(6.dp))
            }

            AlbumArtView(
                coverStyle = coverStyleFor(track.coverId.ifEmpty { track.title }),
                imageModel = track.filepath.ifEmpty { track.coverId },
                modifier = Modifier.size(40.dp),
                cornerRadius = 6.dp,
                borderColor = if (isCurrent) TertiaryCyan else GlassBorderLow,
            )

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = track.title,
                    color = if (isCurrent) TertiaryCyan else PureWhite,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = if (isCurrent) "Reproduciendo ahora" else "${track.artist} • ${track.album}",
                    color = if (isCurrent) TertiaryCyan.copy(alpha = 0.8f) else OnSurfaceVariant,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            if (isCurrent) {
                EqualizerWaveBars(isPlaying = isPlaying, barCount = 3, color = TertiaryCyan)
                Spacer(modifier = Modifier.width(8.dp))
            }

            IconButton(
                onClick = onMoreClick,
                modifier = Modifier.size(32.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.MoreVert,
                    contentDescription = "Opciones",
                    tint = if (isCurrent) TertiaryCyan else OnSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}
