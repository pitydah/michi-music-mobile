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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import org.michimusic.core.models.Track
import org.michimusic.mobile.screens.SearchAlbumResult
import org.michimusic.mobile.screens.SearchArtistResult
import org.michimusic.mobile.screens.SearchFilter
import org.michimusic.mobile.screens.SearchTrackResult
import org.michimusic.mobile.screens.SearchViewModel
import org.michimusic.mobile.ui.components.AlbumArtView
import org.michimusic.mobile.ui.components.EqualizerWaveBars
import org.michimusic.mobile.ui.components.GlassCard
import org.michimusic.mobile.ui.components.coverStyleFor
import org.michimusic.mobile.ui.components.formatTimeMillis
import org.michimusic.mobile.ui.theme.GlassBorderHigh
import org.michimusic.mobile.ui.theme.GlassBorderLow
import org.michimusic.mobile.ui.theme.GlassFillHigh
import org.michimusic.mobile.ui.theme.GlassFillLow
import org.michimusic.mobile.ui.theme.MichiShapes
import org.michimusic.mobile.ui.theme.MichiSpacing
import org.michimusic.mobile.ui.theme.MichiTypography
import org.michimusic.mobile.ui.theme.PrimaryPinkContainer
import org.michimusic.mobile.ui.theme.PureWhite
import org.michimusic.mobile.ui.theme.SurfaceContainer
import org.michimusic.mobile.ui.theme.SurfaceObsidian
import org.michimusic.mobile.ui.theme.TertiaryCyan
import org.michimusic.mobile.ui.theme.TextMuted
import org.michimusic.mobile.ui.theme.TextSecondary
import org.michimusic.player.AudioController

@Composable
fun SearchScreen(
    viewModel: SearchViewModel = koinViewModel(),
) {
    val query by viewModel.query.collectAsStateWithLifecycle()
    val selectedFilter by viewModel.selectedFilter.collectAsStateWithLifecycle()
    val structuredResults by viewModel.structuredResults.collectAsStateWithLifecycle()
    val isSearching by viewModel.isSearching.collectAsStateWithLifecycle()
    val audioController: AudioController = koinInject()
    val playerState by audioController.state.collectAsStateWithLifecycle()

    var selectedArtistForDetail by remember { mutableStateOf<SearchArtistResult?>(null) }
    var selectedAlbumForDetail by remember { mutableStateOf<SearchAlbumResult?>(null) }

    LaunchedEffect(Unit) {
        viewModel.loadLocalTracks()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(SurfaceObsidian)
            .statusBarsPadding()
            .testTag("search_screen"),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = MichiSpacing.screenHorizontal),
        ) {
            Spacer(Modifier.height(MichiSpacing.md))

            // Header
            Text(
                text = "EXPLORADOR",
                style = MichiTypography.screenEyebrow,
            )
            Text(
                text = "Buscar en tu Música",
                style = MichiTypography.screenTitle,
            )

            Spacer(Modifier.height(MichiSpacing.md))

            // Search Bar
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
                    .clip(MichiShapes.md)
                    .background(GlassFillHigh)
                    .border(
                        width = 1.dp,
                        color = if (query.isNotEmpty()) PrimaryPinkContainer.copy(alpha = 0.5f) else GlassBorderHigh,
                        shape = MichiShapes.md,
                    )
                    .padding(horizontal = 16.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = "Buscar",
                        tint = if (query.isNotEmpty()) PrimaryPinkContainer else TextMuted,
                        modifier = Modifier.size(20.dp),
                    )

                    BasicTextField(
                        value = query,
                        onValueChange = viewModel::setQuery,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("search_text_field"),
                        textStyle = TextStyle(
                            color = PureWhite,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium,
                        ),
                        cursorBrush = SolidColor(PrimaryPinkContainer),
                        singleLine = true,
                        decorationBox = { innerTextField ->
                            if (query.isEmpty()) {
                                Text(
                                    text = "Canciones, artistas, álbumes...",
                                    color = TextMuted,
                                    fontSize = 14.sp,
                                )
                            }
                            innerTextField()
                        },
                    )

                    if (query.isNotEmpty()) {
                        IconButton(
                            onClick = { viewModel.clearSearch() },
                            modifier = Modifier
                                .size(MichiSpacing.minTouchTarget)
                                .testTag("search_clear_button"),
                        ) {
                            Icon(
                                imageVector = Icons.Default.Clear,
                                contentDescription = "Limpiar Búsqueda",
                                tint = TextSecondary,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(MichiSpacing.md))

            // Filter Chips
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(SearchFilter.values()) { filter ->
                    val isSelected = filter == selectedFilter
                    Box(
                        modifier = Modifier
                            .clip(MichiShapes.sm)
                            .background(if (isSelected) PrimaryPinkContainer.copy(alpha = 0.2f) else GlassFillLow)
                            .border(1.dp, if (isSelected) PrimaryPinkContainer else GlassBorderLow, MichiShapes.sm)
                            .clickable { viewModel.setFilter(filter) }
                            .padding(horizontal = 14.dp, vertical = 8.dp)
                            .testTag("search_filter_${filter.name.lowercase()}"),
                    ) {
                        Text(
                            text = filter.displayName,
                            fontSize = 12.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                            color = if (isSelected) PureWhite else TextSecondary,
                        )
                    }
                }
            }

            Spacer(Modifier.height(MichiSpacing.md))

            // Results State
            if (isSearching) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(32.dp),
                        color = PrimaryPinkContainer,
                        strokeWidth = 2.dp,
                    )
                }
            } else if (query.isEmpty()) {
                SearchEmptyGuidance()
            } else if (structuredResults.artists.isEmpty() && structuredResults.albums.isEmpty() && structuredResults.tracks.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Default.MusicNote,
                            contentDescription = null,
                            tint = TextMuted,
                            modifier = Modifier.size(44.dp),
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(
                            text = "No se encontraron coincidencias",
                            style = MichiTypography.cardTitle,
                            color = PureWhite,
                        )
                        Text(
                            text = "Prueba buscando por título, álbum o artista",
                            style = MichiTypography.metadata,
                            color = TextSecondary,
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 120.dp),
                    verticalArrangement = Arrangement.spacedBy(MichiSpacing.lg),
                ) {
                    // 1. Artistas
                    if (structuredResults.artists.isNotEmpty()) {
                        item {
                            Text(
                                text = "Artistas",
                                style = MichiTypography.sectionTitle,
                            )
                        }
                        items(structuredResults.artists) { artist ->
                            ArtistSearchRow(
                                artist = artist,
                                onClick = {
                                    selectedArtistForDetail = artist
                                },
                            )
                        }
                    }

                    // 2. Álbumes
                    if (structuredResults.albums.isNotEmpty()) {
                        item {
                            Text(
                                text = "Álbumes",
                                style = MichiTypography.sectionTitle,
                            )
                        }
                        items(structuredResults.albums) { album ->
                            AlbumSearchRow(
                                album = album,
                                onClick = {
                                    selectedAlbumForDetail = album
                                },
                            )
                        }
                    }

                    // 3. Canciones
                    if (structuredResults.tracks.isNotEmpty()) {
                        item {
                            Text(
                                text = "Canciones",
                                style = MichiTypography.sectionTitle,
                            )
                        }
                        items(structuredResults.tracks) { item ->
                            val isPlayingThis = playerState.currentTrack?.id == item.track.id
                            TrackSearchRow(
                                track = item.track,
                                source = item.source,
                                isPlayingThis = isPlayingThis,
                                isAudioPlaying = playerState.isPlaying,
                                onClick = {
                                    val allTracks = structuredResults.tracks.map { it.track }
                                    val idx = allTracks.indexOfFirst { it.id == item.track.id }.coerceAtLeast(0)
                                    audioController.playQueue(allTracks, idx)
                                },
                            )
                        }
                    }
                }
            }
        }

        // Artist Detail Dialog
        selectedArtistForDetail?.let { artistResult ->
            ArtistDetailDialog(
                artist = artistResult,
                onDismiss = { selectedArtistForDetail = null },
                onPlayAll = {
                    if (artistResult.tracks.isNotEmpty()) {
                        audioController.playQueue(artistResult.tracks, 0)
                        selectedArtistForDetail = null
                    }
                },
                onShuffle = {
                    if (artistResult.tracks.isNotEmpty()) {
                        audioController.playQueue(artistResult.tracks.shuffled(), 0)
                        selectedArtistForDetail = null
                    }
                },
                onTrackClick = { track ->
                    val idx = artistResult.tracks.indexOfFirst { it.id == track.id }.coerceAtLeast(0)
                    audioController.playQueue(artistResult.tracks, idx)
                },
            )
        }

        // Album Detail Dialog
        selectedAlbumForDetail?.let { albumResult ->
            AlbumDetailDialog(
                album = albumResult,
                onDismiss = { selectedAlbumForDetail = null },
                onPlayAll = {
                    if (albumResult.tracks.isNotEmpty()) {
                        audioController.playQueue(albumResult.tracks, 0)
                        selectedAlbumForDetail = null
                    }
                },
                onShuffle = {
                    if (albumResult.tracks.isNotEmpty()) {
                        audioController.playQueue(albumResult.tracks.shuffled(), 0)
                        selectedAlbumForDetail = null
                    }
                },
                onTrackClick = { track ->
                    val idx = albumResult.tracks.indexOfFirst { it.id == track.id }.coerceAtLeast(0)
                    audioController.playQueue(albumResult.tracks, idx)
                },
            )
        }
    }
}

@Composable
private fun SearchEmptyGuidance() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(MichiSpacing.md),
    ) {
        Icon(
            imageVector = Icons.Default.Search,
            contentDescription = null,
            tint = TextMuted,
            modifier = Modifier.size(48.dp),
        )
        Text(
            text = "Busca en tu biblioteca",
            style = MichiTypography.cardTitle,
            color = PureWhite,
        )
        Text(
            text = "Escribe para filtrar al instante canciones locales, álbumes sincronizados o artistas.",
            style = MichiTypography.metadata,
            color = TextSecondary,
            modifier = Modifier.padding(horizontal = 24.dp),
        )
    }
}

@Composable
private fun ArtistSearchRow(
    artist: SearchArtistResult,
    onClick: () -> Unit,
) {
    GlassCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        backgroundColor = GlassFillLow,
        borderColor = GlassBorderLow,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(TertiaryCyan.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Default.Person,
                    contentDescription = null,
                    tint = TertiaryCyan,
                    modifier = Modifier.size(22.dp),
                )
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = artist.artist,
                    style = MichiTypography.cardTitle,
                )
                Text(
                    text = "${artist.trackCount} canciones en tu biblioteca",
                    style = MichiTypography.metadata,
                )
            }
        }
    }
}

@Composable
private fun AlbumSearchRow(
    album: SearchAlbumResult,
    onClick: () -> Unit,
) {
    GlassCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        backgroundColor = GlassFillLow,
        borderColor = GlassBorderLow,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            AlbumArtView(
                coverStyle = coverStyleFor(album.sampleTrack?.coverId?.ifEmpty { album.album } ?: album.album),
                imageModel = album.sampleTrack?.filepath?.ifEmpty { album.sampleTrack.coverId } ?: album.sampleTrack?.coverId,
                modifier = Modifier
                    .size(44.dp)
                    .clip(MichiShapes.xs),
            )

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = album.album,
                    style = MichiTypography.cardTitle,
                )
                Text(
                    text = "${album.artist} · ${album.trackCount} temas",
                    style = MichiTypography.metadata,
                )
            }
        }
    }
}

@Composable
private fun TrackSearchRow(
    track: Track,
    source: String,
    isPlayingThis: Boolean,
    isAudioPlaying: Boolean,
    onClick: () -> Unit,
) {
    GlassCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .testTag("search_track_${track.id}"),
        backgroundColor = if (isPlayingThis) GlassFillHigh else GlassFillLow,
        borderColor = if (isPlayingThis) PrimaryPinkContainer.copy(alpha = 0.4f) else GlassBorderLow,
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
                imageModel = track.filepath.ifEmpty { track.coverId },
                modifier = Modifier
                    .size(44.dp)
                    .clip(MichiShapes.xs),
            )

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = track.title,
                    color = if (isPlayingThis) PrimaryPinkContainer else PureWhite,
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

            Text(
                text = formatTimeMillis(track.duration),
                style = MichiTypography.metadata,
            )

            if (isPlayingThis && isAudioPlaying) {
                EqualizerWaveBars(
                    isPlaying = true,
                    color = PrimaryPinkContainer,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

@Composable
private fun ArtistDetailDialog(
    artist: SearchArtistResult,
    onDismiss: () -> Unit,
    onPlayAll: () -> Unit,
    onShuffle: () -> Unit,
    onTrackClick: (Track) -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = SurfaceContainer,
            border = androidx.compose.foundation.BorderStroke(1.dp, GlassBorderHigh),
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = artist.artist,
                            color = PureWhite,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = "${artist.trackCount} temas en biblioteca",
                            color = TextSecondary,
                            fontSize = 12.sp,
                        )
                    }
                    IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = "Cerrar",
                            tint = TextSecondary,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }

                Spacer(Modifier.height(14.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Button(
                        onClick = onPlayAll,
                        modifier = Modifier
                            .weight(1f)
                            .height(42.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = PrimaryPinkContainer),
                        shape = MichiShapes.sm,
                    ) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Reproducir", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }

                    OutlinedButton(
                        onClick = onShuffle,
                        modifier = Modifier
                            .weight(1f)
                            .height(42.dp),
                        shape = MichiShapes.sm,
                    ) {
                        Icon(Icons.Filled.Shuffle, contentDescription = null, modifier = Modifier.size(16.dp), tint = PureWhite)
                        Spacer(Modifier.width(4.dp))
                        Text("Aleatorio", fontSize = 12.sp, color = PureWhite)
                    }
                }

                Spacer(Modifier.height(14.dp))

                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(240.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(artist.tracks) { track ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(MichiShapes.xs)
                                .clickable { onTrackClick(track) }
                                .padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = track.title,
                                    color = PureWhite,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = track.album,
                                    color = TextMuted,
                                    fontSize = 11.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            Text(
                                text = formatTimeMillis(track.duration),
                                color = TextMuted,
                                fontSize = 11.sp,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AlbumDetailDialog(
    album: SearchAlbumResult,
    onDismiss: () -> Unit,
    onPlayAll: () -> Unit,
    onShuffle: () -> Unit,
    onTrackClick: (Track) -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = SurfaceContainer,
            border = androidx.compose.foundation.BorderStroke(1.dp, GlassBorderHigh),
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        AlbumArtView(
                            coverStyle = coverStyleFor(album.sampleTrack?.coverId?.ifEmpty { album.album } ?: album.album),
                            imageModel = album.sampleTrack?.filepath?.ifEmpty { album.sampleTrack.coverId } ?: album.sampleTrack?.coverId,
                            modifier = Modifier
                                .size(48.dp)
                                .clip(MichiShapes.xs),
                        )
                        Column {
                            Text(
                                text = album.album,
                                color = PureWhite,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = "${album.artist} · ${album.trackCount} temas",
                                color = TextSecondary,
                                fontSize = 12.sp,
                            )
                        }
                    }
                    IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = "Cerrar",
                            tint = TextSecondary,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }

                Spacer(Modifier.height(14.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Button(
                        onClick = onPlayAll,
                        modifier = Modifier
                            .weight(1f)
                            .height(42.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = PrimaryPinkContainer),
                        shape = MichiShapes.sm,
                    ) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Reproducir", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }

                    OutlinedButton(
                        onClick = onShuffle,
                        modifier = Modifier
                            .weight(1f)
                            .height(42.dp),
                        shape = MichiShapes.sm,
                    ) {
                        Icon(Icons.Filled.Shuffle, contentDescription = null, modifier = Modifier.size(16.dp), tint = PureWhite)
                        Spacer(Modifier.width(4.dp))
                        Text("Aleatorio", fontSize = 12.sp, color = PureWhite)
                    }
                }

                Spacer(Modifier.height(14.dp))

                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(240.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(album.tracks) { track ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(MichiShapes.xs)
                                .clickable { onTrackClick(track) }
                                .padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                text = track.title,
                                color = PureWhite,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                text = formatTimeMillis(track.duration),
                                color = TextMuted,
                                fontSize = 11.sp,
                            )
                        }
                    }
                }
            }
        }
    }
}
