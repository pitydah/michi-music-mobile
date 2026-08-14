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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
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
import org.michimusic.mobile.ui.theme.PureWhite
import org.michimusic.mobile.ui.theme.SecondaryPurple
import org.michimusic.mobile.ui.theme.SurfaceObsidian
import org.michimusic.mobile.ui.theme.TertiaryCyan
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

    val audioController: AudioController = koinInject()
    val playerState by audioController.state.collectAsState()
    val currentTrack = playerState.currentTrack
    val isPlaying = playerState.isPlaying

    var showEqualizer by remember { mutableStateOf(false) }
    var showCreatePlaylist by remember { mutableStateOf(false) }

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
            // Top App Bar
            HomeTopBar(
                onSearchClick = onNavigateToSearch,
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

                // Suggested Tracks Showcase (Curated quick picks instead of thousands of rows)
                if (allTracks.isNotEmpty()) {
                    item {
                        QuickPicksSection(
                            tracks = allTracks.take(6),
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
        }

        // Dialogs
        if (showEqualizer) {
            EqualizerDialog(onDismiss = { showEqualizer = false })
        }

        if (showCreatePlaylist) {
            CreatePlaylistDialog(
                onCreate = { _ ->
                    showCreatePlaylist = false
                },
                onDismiss = { showCreatePlaylist = false },
            )
        }
    }
}

@Composable
private fun HomeTopBar(
    onSearchClick: () -> Unit,
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
        IconButton(
            onClick = onEqualizerClick,
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(GlassFillLow)
                .testTag("home_eq_button"),
        ) {
            Icon(
                imageVector = Icons.Filled.GraphicEq,
                contentDescription = "Ecualizador",
                tint = OnSurfaceVariant,
                modifier = Modifier.size(22.dp),
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
            onClick = onSearchClick,
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(GlassFillLow)
                .testTag("home_search_button"),
        ) {
            Icon(
                imageVector = Icons.Filled.Search,
                contentDescription = "Buscar",
                tint = OnSurfaceVariant,
                modifier = Modifier.size(22.dp),
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
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Escuchado Recientemente",
                color = PureWhite,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = (-0.5).sp,
                modifier = Modifier.testTag("recent_section_title"),
            )
        }

        Spacer(modifier = Modifier.height(14.dp))

        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            contentPadding = PaddingValues(horizontal = 4.dp),
        ) {
            items(tracks, key = { it.id }) { track ->
                val isCurrent = currentTrack?.id == track.id

                Column(
                    modifier = Modifier
                        .width(130.dp)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = ripple(color = TertiaryCyan),
                            onClick = { onTrackClick(track) },
                        )
                        .testTag("recent_track_${track.id}"),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(16.dp))
                            .border(
                                1.dp,
                                if (isCurrent) TertiaryCyan else GlassBorderLow,
                                RoundedCornerShape(16.dp),
                            ),
                    ) {
                        AlbumArtView(
                            coverStyle = coverStyleFor(track.coverId.ifEmpty { track.title }),
                            imageModel = track.coverId,
                            modifier = Modifier.fillMaxSize(),
                            cornerRadius = 16.dp,
                            borderColor = if (isCurrent) TertiaryCyan else GlassBorderLow,
                        )

                        if (isCurrent) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(SurfaceObsidian.copy(alpha = 0.5f)),
                                contentAlignment = Alignment.Center,
                            ) {
                                EqualizerWaveBars(isPlaying = isPlaying, barCount = 3, color = TertiaryCyan)
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = track.title,
                        color = if (isCurrent) TertiaryCyan else PureWhite,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = track.artist,
                        color = OnSurfaceVariant,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
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
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Tus Listas",
                color = PureWhite,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = (-0.5).sp,
                modifier = Modifier.testTag("playlists_section_title"),
            )

            IconButton(
                onClick = onCreatePlaylist,
                modifier = Modifier.size(48.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.PlaylistAdd,
                    contentDescription = "Nueva Lista",
                    tint = PrimaryPink,
                    modifier = Modifier.size(22.dp),
                )
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Favorites
            GlassCard(
                modifier = Modifier
                    .weight(1f)
                    .height(90.dp),
                shape = RoundedCornerShape(14.dp),
                onClick = { onPlaylistClick("top") },
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(12.dp),
                    verticalArrangement = Arrangement.SpaceBetween,
                ) {
                    Icon(
                        imageVector = Icons.Filled.Star,
                        contentDescription = null,
                        tint = PrimaryPink,
                        modifier = Modifier.size(22.dp),
                    )
                    Column {
                        Text(
                            text = "Más Escuchadas",
                            color = PureWhite,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                        )
                        Text(
                            text = "$topTracksCount canciones",
                            color = OnSurfaceVariant,
                            fontSize = 10.sp,
                        )
                    }
                }
            }

            // Recent
            GlassCard(
                modifier = Modifier
                    .weight(1f)
                    .height(90.dp),
                shape = RoundedCornerShape(14.dp),
                onClick = { onPlaylistClick("recent") },
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(12.dp),
                    verticalArrangement = Arrangement.SpaceBetween,
                ) {
                    Icon(
                        imageVector = Icons.Filled.MusicNote,
                        contentDescription = null,
                        tint = TertiaryCyan,
                        modifier = Modifier.size(22.dp),
                    )
                    Column {
                        Text(
                            text = "Recientes",
                            color = PureWhite,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                        )
                        Text(
                            text = "$recentTracksCount canciones",
                            color = OnSurfaceVariant,
                            fontSize = 10.sp,
                        )
                    }
                }
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
            text = "Música Sugerida",
            color = PureWhite,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = (-0.5).sp,
        )

        Spacer(modifier = Modifier.height(14.dp))

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            tracks.forEach { track ->
                val isCurrent = currentTrack?.id == track.id
                val shape = RoundedCornerShape(12.dp)

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
                        .clickable { onTrackClick(track) }
                        .padding(10.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        AlbumArtView(
                            coverStyle = coverStyleFor(track.coverId.ifEmpty { track.title }),
                            imageModel = track.coverId,
                            modifier = Modifier.size(44.dp),
                            cornerRadius = 8.dp,
                        )

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
                                text = "${track.artist} • ${track.album}",
                                color = OnSurfaceVariant,
                                fontSize = 12.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }

                        if (isCurrent) {
                            EqualizerWaveBars(isPlaying = isPlaying, barCount = 3, color = TertiaryCyan)
                        }
                    }
                }
            }
        }
    }
}
