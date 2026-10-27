package org.michimusic.mobile.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
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
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import org.michimusic.core.models.Track
import org.michimusic.data.repository.FavoritesRepository
import org.michimusic.mobile.library.coverflow.CoverFlowAlbum
import org.michimusic.mobile.screens.AlbumsViewModel
import org.michimusic.mobile.ui.components.AddToPlaylistDialog
import org.michimusic.mobile.ui.components.AlbumArtView
import org.michimusic.mobile.ui.components.CreatePlaylistDialog
import org.michimusic.mobile.ui.components.EqualizerWaveBars
import org.michimusic.mobile.ui.components.GlassCard
import org.michimusic.mobile.ui.components.TrackActionBottomSheet
import org.michimusic.mobile.ui.components.coverStyleFor
import org.michimusic.mobile.ui.components.formatTimeMillis
import org.michimusic.mobile.ui.coverflow.MichiCoverFlowHost
import org.michimusic.mobile.ui.theme.GlassBorderHigh
import org.michimusic.mobile.ui.theme.GlassBorderLow
import org.michimusic.mobile.ui.theme.GlassFillHigh
import org.michimusic.mobile.ui.theme.GlassFillLow
import org.michimusic.mobile.ui.theme.MichiTypography
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
    onNavigateToPlaylists: () -> Unit = {},
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

    val libraryScopeStore: org.michimusic.mobile.library.LibraryScopeStore = koinInject()
    val musicLibraryRepo: org.michimusic.data.repository.MusicLibraryRepository = koinInject()
    val activeScope by libraryScopeStore.activeScope.collectAsState()
    val availableLibraries by musicLibraryRepo.observeLibraries().collectAsState(initial = emptyList())

    var selectedTab by rememberSaveable { mutableStateOf(LibraryTab.SONGS) }
    var selectedFilter by rememberSaveable { mutableStateOf(LibraryFilter.ALL) }
    var viewMode by rememberSaveable { mutableStateOf(LibraryViewMode.LIST) }
    var currentCoverflowIndex by rememberSaveable { mutableIntStateOf(0) }
    var showCreatePlaylist by remember { mutableStateOf(false) }
    var albumToAddToPlaylist by remember { mutableStateOf<org.michimusic.data.repository.LocalMediaRepository.LocalAlbum?>(null) }
    var selectedTrackForMenu by remember { mutableStateOf<Track?>(null) }
    var trackToAddToPlaylist by remember { mutableStateOf<Track?>(null) }
    val favoritesRepo: FavoritesRepository = koinInject()
    val favoriteIds by favoritesRepo.observeFavoriteTrackIds().collectAsState(initial = emptySet())
    val coroutineScope = rememberCoroutineScope()
    val playlists by viewModel.playlists.collectAsState()

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
                sourceAudioUri = album.tracks.firstOrNull()?.filepath.orEmpty(),
            )
        }
    }

    val filteredTracks = remember(allTracks, selectedFilter) {
        allTracks.filter { track ->
            when (selectedFilter) {
                LibraryFilter.ALL -> true
                LibraryFilter.LOSSLESS -> track.isLossless
                LibraryFilter.DOWNLOADED -> track.filepath.isNotEmpty()
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
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 72.dp)
                    .padding(horizontal = 20.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "BIBLIOTECA",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = PrimaryPink,
                        letterSpacing = 2.sp,
                    )
                    Text(
                        text = "Tu biblioteca",
                        style = MichiTypography.screenTitle.copy(fontSize = 22.sp, lineHeight = 27.sp),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
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
                                .size(48.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (viewMode == LibraryViewMode.COVERFLOW) PrimaryPinkContainer.copy(alpha = 0.10f) else GlassFillLow)
                                .border(0.65.dp, if (viewMode == LibraryViewMode.COVERFLOW) PrimaryPinkContainer.copy(alpha = 0.40f) else GlassBorderLow, RoundedCornerShape(12.dp))
                                .clickable {
                                    viewMode = if (viewMode == LibraryViewMode.LIST) LibraryViewMode.COVERFLOW else LibraryViewMode.LIST
                                }
                                .testTag("library_view_mode_toggle"),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = if (viewMode == LibraryViewMode.COVERFLOW) Icons.Filled.ViewList else Icons.Default.ViewCarousel,
                                contentDescription = if (viewMode == LibraryViewMode.COVERFLOW) "Cambiar a lista" else "Cambiar a CoverFlow",
                                tint = if (viewMode == LibraryViewMode.COVERFLOW) PrimaryPinkContainer else PureWhite,
                                modifier = Modifier.size(21.dp),
                            )
                        }
                    }

                    IconButton(
                        onClick = onNavigateToPlaylists,
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(GlassFillLow)
                            .border(1.dp, GlassBorderLow, CircleShape)
                            .testTag("library_playlists_button"),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.LibraryMusic,
                            contentDescription = "Playlists",
                            tint = PureWhite,
                            modifier = Modifier.size(20.dp),
                        )
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

            // Library Scope Selector (Todas / Este teléfono / etc.)
            LazyRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // Option "Todas"
                val isAllSelected = activeScope is org.michimusic.core.models.LibraryScope.All
                item {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (isAllSelected) PrimaryPinkContainer.copy(alpha = 0.11f) else GlassFillLow)
                            .border(0.65.dp, if (isAllSelected) PrimaryPinkContainer.copy(alpha = 0.4f) else GlassBorderLow, RoundedCornerShape(8.dp))
                            .selectable(selected = isAllSelected, role = Role.Tab) {
                                libraryScopeStore.setScope(org.michimusic.core.models.LibraryScope.All)
                            }
                            .heightIn(min = 48.dp)
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                            .testTag("library_scope_all"),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "Todas",
                            color = if (isAllSelected) PureWhite else TextSecondary,
                            fontSize = 12.sp,
                            fontWeight = if (isAllSelected) FontWeight.Bold else FontWeight.Medium,
                        )
                    }
                }

                // Existing libraries in repository
                items(availableLibraries) { lib ->
                    val isSelected = (activeScope as? org.michimusic.core.models.LibraryScope.One)?.libraryId == lib.id
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (isSelected) PrimaryPinkContainer.copy(alpha = 0.11f) else GlassFillLow)
                            .border(0.65.dp, if (isSelected) PrimaryPinkContainer.copy(alpha = 0.4f) else GlassBorderLow, RoundedCornerShape(8.dp))
                            .selectable(selected = isSelected, role = Role.Tab) {
                                libraryScopeStore.setScope(org.michimusic.core.models.LibraryScope.One(lib.id))
                            }
                            .heightIn(min = 48.dp)
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                            .testTag("library_scope_${lib.id}"),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = lib.name,
                            color = if (isSelected) PureWhite else TextSecondary,
                            fontSize = 12.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
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
                            .height(48.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (isSelected) PrimaryPinkContainer.copy(alpha = 0.11f) else GlassFillLow)
                            .border(0.65.dp, if (isSelected) PrimaryPinkContainer.copy(alpha = 0.40f) else GlassBorderLow, RoundedCornerShape(10.dp))
                            .clickable { selectedTab = tab }
                            .semantics { selected = isSelected }
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
                                .background(if (isSelected) PrimaryPinkContainer.copy(alpha = 0.10f) else GlassFillLow)
                                .border(0.65.dp, if (isSelected) PrimaryPinkContainer.copy(alpha = 0.38f) else GlassBorderLow, RoundedCornerShape(8.dp))
                                .clickable { selectedFilter = filter }
                                .semantics { selected = isSelected }
                                .heightIn(min = 48.dp)
                                .padding(horizontal = 12.dp, vertical = 6.dp)
                                .testTag("library_filter_${filter.name.lowercase()}"),
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
                            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp),
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
                                            fallbackMediaUri = track.filepath,
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

                                        IconButton(
                                            onClick = { selectedTrackForMenu = track },
                                            modifier = Modifier.size(44.dp),
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.MoreVert,
                                                contentDescription = "Opciones",
                                                tint = TextSecondary,
                                                modifier = Modifier.size(20.dp),
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
                                    .padding(bottom = 24.dp),
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
                                        initialPage = currentCoverflowIndex,
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

                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                            ) {
                                                IconButton(
                                                    onClick = { albumToAddToPlaylist = currentAlbum },
                                                    modifier = Modifier.size(44.dp),
                                                ) {
                                                    Icon(
                                                        Icons.Default.Add,
                                                        contentDescription = "Añadir a playlist",
                                                        tint = TextSecondary,
                                                        modifier = Modifier.size(20.dp),
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
                            }
                        } else {
                            LazyColumn(
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp),
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
                                                fallbackMediaUri = localAlbum.tracks.firstOrNull()?.filepath,
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

                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                            ) {
                                                IconButton(
                                                    onClick = { albumToAddToPlaylist = localAlbum },
                                                    modifier = Modifier.size(44.dp),
                                                ) {
                                                    Icon(
                                                        Icons.Default.Add,
                                                        contentDescription = "Añadir a playlist",
                                                        tint = TextSecondary,
                                                        modifier = Modifier.size(20.dp),
                                                    )
                                                }
                                                IconButton(
                                                    onClick = {
                                                        if (localAlbum.tracks.isNotEmpty()) {
                                                            audioController.playQueue(localAlbum.tracks, 0)
                                                        }
                                                    },
                                                    modifier = Modifier.size(44.dp),
                                                ) {
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
                        }
                    }

                    LibraryTab.ARTISTS -> {
                        val artistsList = remember(albums) {
                            albums.groupBy { it.album.artist }.toList()
                        }

                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp),
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
                onCreate = { name ->
                    viewModel.createPlaylist(name) {}
                    showCreatePlaylist = false
                },
                onDismiss = { showCreatePlaylist = false },
            )
        }

        albumToAddToPlaylist?.let { targetAlbum ->
            var isAddingToPlaylist by remember { mutableStateOf(false) }
            var addError by remember { mutableStateOf<String?>(null) }

            androidx.compose.ui.window.Dialog(onDismissRequest = {
                if (!isAddingToPlaylist) {
                    albumToAddToPlaylist = null
                }
            }) {
                androidx.compose.material3.Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = SurfaceObsidian,
                    border = androidx.compose.foundation.BorderStroke(1.dp, GlassBorderHigh),
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(
                            text = "Añadir a playlist",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = PureWhite,
                        )
                        Text(
                            text = "Selecciona una playlist para «${targetAlbum.album.title}» (${targetAlbum.tracks.size} pistas)",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary,
                        )

                        if (addError != null) {
                            Text(
                                text = addError.orEmpty(),
                                style = MaterialTheme.typography.bodySmall,
                                color = androidx.compose.material3.MaterialTheme.colorScheme.error,
                            )
                        }

                        if (isAddingToPlaylist) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 16.dp),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                androidx.compose.material3.CircularProgressIndicator(
                                    modifier = Modifier.size(24.dp),
                                    color = PrimaryPink,
                                    strokeWidth = 2.dp,
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Text(
                                    text = "Añadiendo canciones...",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = TextSecondary,
                                )
                            }
                        } else if (playlists.isEmpty()) {
                            Text(
                                text = "No tienes playlists creadas. Pulsa el botón '+' arriba para crear una.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = TextMuted,
                                modifier = Modifier.padding(vertical = 12.dp),
                            )
                        } else {
                            LazyColumn(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height((playlists.size * 56).coerceAtMost(240).dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                items(playlists, key = { it.id }) { pl ->
                                    GlassCard(
                                        modifier = Modifier.fillMaxWidth(),
                                        backgroundColor = GlassFillLow,
                                        borderColor = GlassBorderLow,
                                        onClick = {
                                            if (!isAddingToPlaylist) {
                                                isAddingToPlaylist = true
                                                addError = null
                                                viewModel.addAlbumToPlaylist(pl.id, targetAlbum.tracks) { success ->
                                                    isAddingToPlaylist = false
                                                    if (success) {
                                                        albumToAddToPlaylist = null
                                                    } else {
                                                        addError = "No se pudieron añadir las canciones a la playlist"
                                                    }
                                                }
                                            }
                                        },
                                    ) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(12.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                        ) {
                                            Text(
                                                text = pl.name,
                                                style = MaterialTheme.typography.bodyMedium,
                                                fontWeight = FontWeight.SemiBold,
                                                color = PureWhite,
                                            )
                                            Text(
                                                text = "${pl.trackCount} temas",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = TextSecondary,
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End,
                        ) {
                            androidx.compose.material3.TextButton(
                                onClick = { albumToAddToPlaylist = null },
                                enabled = !isAddingToPlaylist,
                            ) {
                                Text("Cancelar", color = TextSecondary)
                            }
                        }
                    }
                }
            }
        }

        selectedTrackForMenu?.let { track ->
            TrackActionBottomSheet(
                track = track,
                isFavorite = favoriteIds.contains(track.id),
                onDismiss = { selectedTrackForMenu = null },
                onPlayNow = {
                    val idx = filteredTracks.indexOfFirst { it.id == track.id }.coerceAtLeast(0)
                    audioController.playQueue(filteredTracks, idx)
                },
                onPlayNext = {
                    audioController.playNext(track)
                },
                onAddToQueue = {
                    audioController.addToQueue(track)
                },
                onAddToPlaylist = {
                    trackToAddToPlaylist = track
                },
                onToggleFavorite = {
                    coroutineScope.launch {
                        favoritesRepo.toggleFavorite(track.id)
                    }
                },
            )
        }

        trackToAddToPlaylist?.let { track ->
            AddToPlaylistDialog(
                tracks = listOf(track),
                onDismiss = { trackToAddToPlaylist = null },
            )
        }
    }
}
