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
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.ViewCarousel
import androidx.compose.material.icons.filled.ViewList
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import org.michimusic.core.models.Track
import org.michimusic.mobile.library.coverflow.CoverFlowAlbum
import org.michimusic.mobile.screens.AlbumsViewModel
import org.michimusic.mobile.ui.components.AlbumArtView
import org.michimusic.mobile.ui.components.CreatePlaylistDialog
import org.michimusic.mobile.ui.components.EqualizerWaveBars
import org.michimusic.mobile.ui.components.GlassCard
import org.michimusic.mobile.ui.components.coverStyleFor
import org.michimusic.mobile.ui.components.formatTimeMillis
import org.michimusic.mobile.ui.coverflow.MichiCoverFlowHost
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
import org.michimusic.mobile.ui.theme.TextMuted
import org.michimusic.mobile.ui.theme.TextSecondary
import org.michimusic.player.AudioController

enum class LibraryTab(val displayName: String) {
    SONGS("Canciones"),
    ALBUMS("Álbumes"),
    ARTISTS("Artistas"),
}

enum class LibraryFilter(val displayName: String) {
    ALL("Todo"),
    LOSSLESS("Lossless"),
    DOWNLOADED("Descargadas"),
}

enum class LibraryViewMode {
    LIST,
    COVERFLOW,
}

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

    var selectedTab by remember { mutableStateOf(LibraryTab.SONGS) }
    var selectedFilter by remember { mutableStateOf(LibraryFilter.ALL) }
    var viewMode by remember { mutableStateOf(LibraryViewMode.LIST) }
    var currentCoverflowIndex by remember { mutableIntStateOf(0) }
    var showCreatePlaylist by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.loadMedia()
    }

    val coverFlowAlbums = remember(albums) {
        albums.map { album ->
            CoverFlowAlbum(
                id = album.album.id,
                title = album.album.title,
                artist = album.album.artist,
                year = album.album.year,
                trackCount = album.tracks.size,
                hasArt = true,
                coverUri = album.album.coverId,
            )
        }
    }

    val filteredTracks = remember(allTracks, selectedFilter) {
        allTracks.filter { track ->
            when (selectedFilter) {
                LibraryFilter.ALL -> true
                LibraryFilter.LOSSLESS -> track.format.contains("flac", ignoreCase = true) || (track.sampleRate ?: 0) >= 48000
                LibraryFilter.DOWNLOADED -> track.filepath.isNotEmpty()
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(SurfaceObsidian)
            .drawBehind {
                drawCircle(
                    color = PrimaryPinkContainer.copy(alpha = 0.08f),
                    radius = size.width * 0.7f,
                    center = androidx.compose.ui.geometry.Offset(size.width * 0.9f, size.height * 0.1f),
                )
                drawCircle(
                    color = TertiaryCyan.copy(alpha = 0.05f),
                    radius = size.width * 0.6f,
                    center = androidx.compose.ui.geometry.Offset(size.width * 0.1f, size.height * 0.85f),
                )
            },
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
                Column {
                    Text(
                        text = "BIBLIOTECA",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = PrimaryPink,
                        letterSpacing = 2.sp,
                    )
                    Text(
                        text = "Colección Local",
                        color = PureWhite,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.testTag("library_title"),
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (selectedTab == LibraryTab.ALBUMS && coverFlowAlbums.isNotEmpty()) {
                        // CoverFlow / List Switcher
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .background(GlassFillHigh)
                                .border(1.dp, if (viewMode == LibraryViewMode.COVERFLOW) TertiaryCyan else GlassBorderLow, RoundedCornerShape(12.dp))
                                .clickable {
                                    viewMode = if (viewMode == LibraryViewMode.LIST) LibraryViewMode.COVERFLOW else LibraryViewMode.LIST
                                }
                                .padding(horizontal = 10.dp, vertical = 6.dp)
                                .testTag("library_view_mode_toggle"),
                            contentAlignment = Alignment.Center,
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Icon(
                                    imageVector = if (viewMode == LibraryViewMode.COVERFLOW) Icons.Filled.ViewList else Icons.Default.ViewCarousel,
                                    contentDescription = "Cambiar Vista",
                                    tint = if (viewMode == LibraryViewMode.COVERFLOW) TertiaryCyan else PureWhite,
                                    modifier = Modifier.size(18.dp),
                                )
                                Text(
                                    text = if (viewMode == LibraryViewMode.COVERFLOW) "Lista" else "CoverFlow",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (viewMode == LibraryViewMode.COVERFLOW) TertiaryCyan else PureWhite,
                                )
                            }
                        }
                    }

                    IconButton(
                        onClick = { showCreatePlaylist = true },
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(GlassFillLow)
                            .border(1.dp, GlassBorderLow, CircleShape)
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
            }

            // View Type Tabs (Canciones / Álbumes / Artistas)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                LibraryTab.values().forEach { tab ->
                    val isSelected = tab == selectedTab
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(38.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (isSelected) PrimaryPinkContainer.copy(alpha = 0.25f) else GlassFillLow)
                            .border(1.dp, if (isSelected) PrimaryPink else GlassBorderLow, RoundedCornerShape(10.dp))
                            .clickable { selectedTab = tab }
                            .testTag("library_tab_${tab.name.lowercase()}"),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = tab.displayName,
                            color = if (isSelected) PureWhite else TextSecondary,
                            fontSize = 13.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                        )
                    }
                }
            }

            // Filter Chips (Todo / Lossless / Descargadas)
            if (selectedTab == LibraryTab.SONGS) {
                LazyRow(
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(LibraryFilter.values()) { filter ->
                        val isSelected = filter == selectedFilter
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (isSelected) TertiaryCyan.copy(alpha = 0.2f) else GlassFillLow)
                                .border(1.dp, if (isSelected) TertiaryCyan else GlassBorderLow, RoundedCornerShape(8.dp))
                                .clickable { selectedFilter = filter }
                                .padding(horizontal = 12.dp, vertical = 6.dp)
                                .testTag("library_filter_${filter.name.lowercase()}"),
                        ) {
                            Text(
                                text = filter.displayName,
                                fontSize = 11.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                color = if (isSelected) TertiaryCyan else TextSecondary,
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            // Main Content Area
            if (isLoading && allTracks.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(36.dp),
                        color = PrimaryPink,
                        strokeWidth = 3.dp,
                    )
                }
            } else if (allTracks.isEmpty()) {
                // Empty Library State
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.LibraryMusic,
                            contentDescription = null,
                            tint = TextMuted,
                            modifier = Modifier.size(56.dp),
                        )
                        Text(
                            text = "No encontramos música en este dispositivo",
                            color = PureWhite,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = "Asegúrate de otorgar permisos de lectura multimedia y tener archivos de audio en tu almacenamiento.",
                            color = TextMuted,
                            fontSize = 13.sp,
                        )
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .background(PrimaryPinkContainer)
                                .clickable { viewModel.loadMedia() }
                                .padding(horizontal = 20.dp, vertical = 12.dp)
                                .testTag("library_rescan_button"),
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Icon(Icons.Filled.Refresh, contentDescription = null, tint = PureWhite, modifier = Modifier.size(18.dp))
                                Text("Volver a escanear", color = PureWhite, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            } else {
                when (selectedTab) {
                    LibraryTab.SONGS -> {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 120.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            items(filteredTracks, key = { it.id }) { track ->
                                val isCurrent = currentTrack?.id == track.id
                                GlassCard(
                                    modifier = Modifier.fillMaxWidth(),
                                    backgroundColor = if (isCurrent) TertiaryCyan.copy(alpha = 0.08f) else GlassFillLow,
                                    borderColor = if (isCurrent) TertiaryCyan.copy(alpha = 0.4f) else GlassBorderLow,
                                    accent = if (isCurrent) TertiaryCyan else null,
                                    onClick = {
                                        val idx = filteredTracks.indexOfFirst { it.id == track.id }.coerceAtLeast(0)
                                        audioController.playQueue(filteredTracks, idx)
                                    },
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(12.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    ) {
                                        AlbumArtView(
                                            coverStyle = coverStyleFor(track.coverId.ifEmpty { track.title }),
                                            imageModel = track.coverId,
                                            modifier = Modifier
                                                .size(44.dp)
                                                .clip(RoundedCornerShape(8.dp)),
                                        )

                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = track.title,
                                                fontSize = 14.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                color = if (isCurrent) TertiaryCyan else PureWhite,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                            Text(
                                                text = "${track.artist} • ${track.album}",
                                                fontSize = 12.sp,
                                                color = TextSecondary,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        }

                                        if (isCurrent && isPlaying) {
                                            EqualizerWaveBars(isPlaying = true, barCount = 3, color = TertiaryCyan)
                                        } else {
                                            Text(
                                                text = formatTimeMillis(track.duration),
                                                fontSize = 11.sp,
                                                color = TextMuted,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    LibraryTab.ALBUMS -> {
                        if (viewMode == LibraryViewMode.COVERFLOW && coverFlowAlbums.isNotEmpty()) {
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(bottom = 100.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(260.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    MichiCoverFlowHost(
                                        albums = coverFlowAlbums,
                                        onCurrentChanged = { currentCoverflowIndex = it },
                                        onAlbumClick = { idx ->
                                            val selectedAlbum = albums.getOrNull(idx)
                                            if (selectedAlbum != null && selectedAlbum.tracks.isNotEmpty()) {
                                                audioController.playQueue(selectedAlbum.tracks, 0)
                                            }
                                        },
                                        modifier = Modifier.fillMaxSize(),
                                    )
                                }

                                Spacer(Modifier.height(12.dp))

                                val currentAlbum = albums.getOrNull(currentCoverflowIndex)
                                if (currentAlbum != null) {
                                    GlassCard(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 20.dp),
                                        backgroundColor = GlassFillLow,
                                        borderColor = GlassBorderLow,
                                        accent = TertiaryCyan,
                                    ) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(14.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                        ) {
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    text = currentAlbum.album.title,
                                                    fontSize = 16.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = PureWhite,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                )
                                                Text(
                                                    text = "${currentAlbum.album.artist} • ${currentAlbum.tracks.size} canciones",
                                                    fontSize = 12.sp,
                                                    color = TextSecondary,
                                                )
                                            }

                                            Box(
                                                modifier = Modifier
                                                    .size(48.dp)
                                                    .clip(CircleShape)
                                                    .background(PrimaryPinkContainer)
                                                    .clickable {
                                                        if (currentAlbum.tracks.isNotEmpty()) {
                                                            audioController.playQueue(currentAlbum.tracks, 0)
                                                        }
                                                    },
                                                contentAlignment = Alignment.Center,
                                            ) {
                                                Icon(
                                                    Icons.Default.PlayArrow,
                                                    contentDescription = "Reproducir Álbum",
                                                    tint = PureWhite,
                                                    modifier = Modifier.size(24.dp),
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        } else {
                            LazyColumn(
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 120.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                items(albums, key = { it.album.id }) { localAlbum ->
                                    GlassCard(
                                        modifier = Modifier.fillMaxWidth(),
                                        backgroundColor = GlassFillLow,
                                        borderColor = GlassBorderLow,
                                        onClick = {
                                            if (localAlbum.tracks.isNotEmpty()) {
                                                audioController.playQueue(localAlbum.tracks, 0)
                                            }
                                        },
                                    ) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(12.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                                        ) {
                                            AlbumArtView(
                                                coverStyle = coverStyleFor(localAlbum.album.coverId ?: localAlbum.album.title),
                                                imageModel = localAlbum.album.coverId,
                                                modifier = Modifier
                                                    .size(52.dp)
                                                    .clip(RoundedCornerShape(10.dp)),
                                            )

                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    text = localAlbum.album.title,
                                                    fontSize = 15.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = PureWhite,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                )
                                                Text(
                                                    text = "${localAlbum.album.artist} • ${localAlbum.tracks.size} pistas",
                                                    fontSize = 12.sp,
                                                    color = TextSecondary,
                                                )
                                            }

                                            Icon(
                                                Icons.Default.PlayArrow,
                                                contentDescription = "Reproducir",
                                                tint = PrimaryPink,
                                                modifier = Modifier.size(24.dp),
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    LibraryTab.ARTISTS -> {
                        val artistsList = remember(albums) {
                            albums.groupBy { it.album.artist }.toList()
                        }

                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 120.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            items(artistsList, key = { it.first }) { (artistName, artistAlbums) ->
                                val artistTracks = artistAlbums.flatMap { it.tracks }
                                GlassCard(
                                    modifier = Modifier.fillMaxWidth(),
                                    backgroundColor = GlassFillLow,
                                    borderColor = GlassBorderLow,
                                    onClick = {
                                        if (artistTracks.isNotEmpty()) {
                                            audioController.playQueue(artistTracks, 0)
                                        }
                                    },
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(14.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(48.dp)
                                                .clip(CircleShape)
                                                .background(SecondaryPurple.copy(alpha = 0.3f))
                                                .border(1.dp, PrimaryPink.copy(alpha = 0.5f), CircleShape),
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            Icon(
                                                Icons.Default.Person,
                                                contentDescription = null,
                                                tint = PureWhite,
                                                modifier = Modifier.size(24.dp),
                                            )
                                        }

                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = artistName.ifBlank { "Artista Desconocido" },
                                                fontSize = 15.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = PureWhite,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                            Text(
                                                text = "${artistAlbums.size} álbumes • ${artistTracks.size} canciones",
                                                fontSize = 12.sp,
                                                color = TextSecondary,
                                            )
                                        }

                                        Icon(
                                            Icons.Default.PlayArrow,
                                            contentDescription = "Reproducir Artista",
                                            tint = TertiaryCyan,
                                            modifier = Modifier.size(24.dp),
                                        )
                                    }
                                }
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
