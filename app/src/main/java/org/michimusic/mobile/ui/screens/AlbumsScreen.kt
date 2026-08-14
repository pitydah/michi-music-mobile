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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.Headset
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
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
import org.michimusic.mobile.ui.components.EqualizerWaveBars
import org.michimusic.mobile.ui.components.GlassCard
import org.michimusic.mobile.ui.components.coverStyleFor
import org.michimusic.mobile.ui.components.formatTimeMillis
import org.michimusic.mobile.ui.theme.GlassBorderHigh
import org.michimusic.mobile.ui.theme.GlassBorderLow
import org.michimusic.mobile.ui.theme.GlassFillHigh
import org.michimusic.mobile.ui.theme.GlassFillLow
import org.michimusic.mobile.ui.theme.OnSurfaceVariant
import org.michimusic.mobile.ui.theme.PrimaryPink
import org.michimusic.mobile.ui.theme.PureWhite
import org.michimusic.mobile.ui.theme.SurfaceObsidian
import org.michimusic.mobile.ui.theme.TertiaryCyan
import org.michimusic.mobile.ui.theme.TertiaryCyanContainer
import org.michimusic.player.AudioController

@Composable
fun AlbumsScreen(
    modifier: Modifier = Modifier,
) {
    val viewModel: AlbumsViewModel = koinViewModel()
    val allTracks by viewModel.allTracks.collectAsState()
    val albums by viewModel.albums.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()

    val audioController: AudioController = koinInject()
    val playerState by audioController.state.collectAsState()
    val currentTrack = playerState.currentTrack
    val isPlaying = playerState.isPlaying

    var searchQuery by remember { mutableStateOf("") }
    var selectedFilter by remember { mutableStateOf("Todo") }
    var showCreatePlaylist by remember { mutableStateOf(false) }

    val filters = listOf("Todo", "Álbumes", "Artistas", "Lossless", "Recientes")

    LaunchedEffect(Unit) {
        viewModel.loadMedia()
    }

    val filteredTracks = remember(allTracks, searchQuery, selectedFilter) {
        allTracks.filter { track ->
            val matchesSearch = searchQuery.isBlank() ||
                track.title.contains(searchQuery, ignoreCase = true) ||
                track.artist.contains(searchQuery, ignoreCase = true) ||
                track.album.contains(searchQuery, ignoreCase = true)

            val matchesFilter = when (selectedFilter) {
                "Álbumes" -> track.album.isNotBlank()
                "Artistas" -> track.artist.isNotBlank()
                "Lossless" -> track.format.contains("flac", ignoreCase = true) || track.bitrate > 320000
                else -> true
            }

            matchesSearch && matchesFilter
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
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp)
                    .padding(horizontal = 20.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Biblioteca",
                    color = PrimaryPink,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.testTag("library_title"),
                )

                IconButton(
                    onClick = { showCreatePlaylist = true },
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(GlassFillLow)
                        .testTag("library_add_playlist_button"),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Add,
                        contentDescription = "Nueva Playlist",
                        tint = PureWhite,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }

            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("library_scroll_list"),
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 120.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                // Search Field
                item {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        placeholder = { Text("Filtrar por título, artista o álbum...", color = OnSurfaceVariant) },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Filled.Search,
                                contentDescription = null,
                                tint = OnSurfaceVariant,
                            )
                        },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = PureWhite,
                            unfocusedTextColor = PureWhite,
                            focusedBorderColor = TertiaryCyan,
                            unfocusedBorderColor = GlassBorderLow,
                            focusedContainerColor = GlassFillHigh,
                            unfocusedContainerColor = GlassFillLow,
                        ),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                // Filter Chips Row
                item {
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(filters) { filter ->
                            val isSelected = selectedFilter == filter
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(20.dp))
                                    .background(if (isSelected) TertiaryCyanContainer else GlassFillLow)
                                    .border(
                                        1.dp,
                                        if (isSelected) TertiaryCyan else GlassBorderLow,
                                        RoundedCornerShape(20.dp),
                                    )
                                    .clickable { selectedFilter = filter }
                                    .padding(horizontal = 16.dp, vertical = 8.dp),
                            ) {
                                Text(
                                    text = filter,
                                    color = if (isSelected) SurfaceObsidian else PureWhite,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }
                        }
                    }
                }

                // Albums Overview
                if (albums.isNotEmpty() && (selectedFilter == "Todo" || selectedFilter == "Álbumes")) {
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(
                                text = "ÁLBUMES (${albums.size})",
                                color = OnSurfaceVariant,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.5.sp,
                            )

                            LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                items(albums) { localAlbum ->
                                    GlassCard(
                                        modifier = Modifier
                                            .width(160.dp)
                                            .height(84.dp),
                                        backgroundColor = GlassFillHigh,
                                        borderColor = GlassBorderHigh,
                                        onClick = {
                                            if (localAlbum.tracks.isNotEmpty()) {
                                                audioController.playQueue(localAlbum.tracks, 0)
                                            }
                                        },
                                    ) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxSize()
                                                .padding(12.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                                        ) {
                                            AlbumArtView(
                                                coverStyle = coverStyleFor(localAlbum.album.title),
                                                modifier = Modifier.size(44.dp),
                                                cornerRadius = 8.dp,
                                            )
                                            Column {
                                                Text(
                                                    text = localAlbum.album.title,
                                                    color = PureWhite,
                                                    fontSize = 13.sp,
                                                    fontWeight = FontWeight.SemiBold,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                )
                                                Text(
                                                    text = "${localAlbum.tracks.size} pistas",
                                                    color = OnSurfaceVariant,
                                                    fontSize = 11.sp,
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // Tracks Header
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "CANCIÓNES (${filteredTracks.size})",
                            color = OnSurfaceVariant,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.5.sp,
                        )

                        Text(
                            text = "Audio de Alta Fidelidad",
                            color = TertiaryCyan,
                            fontSize = 11.sp,
                        )
                    }
                }

                // Tracks List
                items(filteredTracks) { track ->
                    val isCurrent = track.id == currentTrack?.id

                    GlassCard(
                        modifier = Modifier.fillMaxWidth(),
                        backgroundColor = if (isCurrent) GlassFillHigh else GlassFillLow,
                        borderColor = if (isCurrent) TertiaryCyan.copy(alpha = 0.5f) else GlassBorderLow,
                        onClick = {
                            val index = allTracks.indexOfFirst { it.id == track.id }.coerceAtLeast(0)
                            audioController.playQueue(allTracks, index)
                        },
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            AlbumArtView(
                                coverStyle = coverStyleFor(track.coverId.ifEmpty { track.title }),
                                imageModel = track.filepath.ifEmpty { track.coverId },
                                modifier = Modifier.size(42.dp),
                                cornerRadius = 8.dp,
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
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    Text(
                                        text = track.artist,
                                        color = OnSurfaceVariant,
                                        fontSize = 11.sp,
                                    )
                                    if (track.album.isNotBlank()) {
                                        Text(
                                            text = "• ${track.album}",
                                            color = OnSurfaceVariant.copy(alpha = 0.6f),
                                            fontSize = 10.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                            }

                            if (isCurrent) {
                                EqualizerWaveBars(isPlaying = isPlaying, barCount = 3, color = TertiaryCyan)
                            } else {
                                Text(
                                    text = formatTimeMillis(track.duration),
                                    color = OnSurfaceVariant,
                                    fontSize = 11.sp,
                                )
                            }
                        }
                    }
                }
            }
        }

        if (showCreatePlaylist) {
            CreatePlaylistDialog(
                onCreate = { showCreatePlaylist = false },
                onDismiss = { showCreatePlaylist = false },
            )
        }
    }
}
