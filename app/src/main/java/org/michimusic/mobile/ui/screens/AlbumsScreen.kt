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
import androidx.compose.foundation.lazy.itemsIndexed
import org.michimusic.mobile.playback.resolvePlaybackPresentation
import org.michimusic.mobile.ui.components.premium.MichiIconAction
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.ViewCarousel
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
import androidx.compose.ui.res.pluralStringResource
import org.michimusic.mobile.R
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
import org.michimusic.mobile.ui.components.AeroPremiumBackdrop
import org.michimusic.mobile.ui.components.AeroActionButton
import org.michimusic.mobile.ui.components.AeroActionKind
import org.michimusic.mobile.ui.components.AeroEmptyEditorial
import org.michimusic.mobile.ui.components.AeroSegmentedChoice
import org.michimusic.mobile.ui.components.AeroSegmentRail
import org.michimusic.mobile.ui.components.AeroSegmentTreatment
import org.michimusic.mobile.ui.components.premium.AeroFloatingSurface
import org.michimusic.mobile.artist.ArtistEnrichmentPanel
import org.michimusic.mobile.ui.components.coverStyleFor
import org.michimusic.mobile.ui.components.formatTimeMillis
import org.michimusic.mobile.ui.coverflow.MichiCoverFlowHost
import org.michimusic.mobile.ui.theme.GlassBorderHigh
import org.michimusic.mobile.ui.theme.GlassBorderLow
import org.michimusic.mobile.ui.theme.GlassFillHigh
import org.michimusic.mobile.ui.theme.GlassFillLow
import org.michimusic.mobile.ui.theme.MichiShapes
import org.michimusic.mobile.ui.theme.MichiSpacing
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
    sessionManager: org.michimusic.mobile.playback.PlaybackSessionManager = koinInject(),
    modifier: Modifier = Modifier,
) {
    val viewModel: AlbumsViewModel = koinViewModel()
    val allTracks by viewModel.allTracks.collectAsState()
    val albums by viewModel.albums.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()

    val audioController: AudioController = koinInject()
    val sessionState by sessionManager.sessionState.collectAsState()
    val playerState by audioController.state.collectAsState()
    val presentation = resolvePlaybackPresentation(sessionState, playerState)
    val currentTrack = presentation.currentTrack
    val isPlaying = presentation.isPlaying

    fun playQueueSafe(tracks: List<Track>, index: Int) {
        sessionManager.playQueue(tracks, index)
    }

    val libraryScopeStore: org.michimusic.mobile.library.LibraryScopeStore = koinInject()
    val musicLibraryRepo: org.michimusic.data.repository.MusicLibraryRepository = koinInject()
    val activeScope by libraryScopeStore.activeScope.collectAsState()
    val availableLibraries by musicLibraryRepo.observeLibraries().collectAsState(initial = emptyList())

    var selectedTab by rememberSaveable { mutableStateOf(LibraryTab.SONGS) }
    var selectedFilter by rememberSaveable { mutableStateOf(LibraryFilter.ALL) }
    var viewMode by rememberSaveable { mutableStateOf(LibraryViewMode.LIST) }
    var selectedAlbumId by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedArtistName by rememberSaveable { mutableStateOf<String?>(null) }
    var showCreatePlaylist by remember { mutableStateOf(false) }
    var albumToAddToPlaylist by remember { mutableStateOf<org.michimusic.data.repository.LocalMediaRepository.LocalAlbum?>(null) }
    var selectedTrackForMenu by remember { mutableStateOf<Track?>(null) }
    var trackToAddToPlaylist by remember { mutableStateOf<Track?>(null) }
    val favoritesRepo: FavoritesRepository = koinInject()
    val favoriteIds by favoritesRepo.observeFavoriteTrackIds().collectAsState(initial = emptySet())
    val coroutineScope = rememberCoroutineScope()
    val playlists by viewModel.playlists.collectAsState()
    val downloadedTrackIds by viewModel.downloadedTrackIds.collectAsState()

    LaunchedEffect(Unit) {
        viewModel.loadMedia()
    }

    LaunchedEffect(albums) {
        // A rescan can drop the album that was selected; without this reconciliation the
        // CoverFlow and the detail panel would resolve the stale id differently.
        selectedAlbumId = reconcileSelectedAlbumId(
            selectedId = selectedAlbumId,
            albumIds = albums.map { it.album.id },
        )
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

    val filteredTracks = remember(allTracks, selectedFilter, downloadedTrackIds) {
        allTracks.filter { track ->
            when (selectedFilter) {
                LibraryFilter.ALL -> true
                LibraryFilter.LOSSLESS -> track.isLossless
                LibraryFilter.DOWNLOADED -> track.id in downloadedTrackIds
            }
        }
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
                    if (!isLoading && allTracks.isNotEmpty()) {
                        Text(
                            text = pluralStringResource(R.plurals.track_count, allTracks.size, allTracks.size) +
                                "  ·  " +
                                pluralStringResource(R.plurals.album_count, albums.size, albums.size),
                            style = MichiTypography.microLabel,
                            color = TextSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 5.dp).testTag("library_real_count"),
                        )
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (selectedTab == LibraryTab.ALBUMS && coverFlowAlbums.isNotEmpty()) {
                        // CoverFlow / List Switcher - naked icon-only per Aero standard
                        MichiIconAction(
                            icon = if (viewMode == LibraryViewMode.COVERFLOW) Icons.AutoMirrored.Filled.ViewList else Icons.Default.ViewCarousel,
                            contentDescription = if (viewMode == LibraryViewMode.COVERFLOW) "Cambiar a lista" else "Cambiar a CoverFlow",
                            onClick = {
                                viewMode = if (viewMode == LibraryViewMode.LIST) LibraryViewMode.COVERFLOW else LibraryViewMode.LIST
                            },
                            tint = if (viewMode == LibraryViewMode.COVERFLOW) PrimaryPinkContainer else PureWhite,
                            iconSize = 22.dp,
                            minTargetSize = MichiSpacing.minTouchTarget,
                            testTag = "library_view_mode_toggle",
                        )
                    }

                    // The playlists entry point carries its own label: an icon-only
                    // action in the header left the whole feature discoverable only by
                    // guessing what a music-note glyph opened.
                    Row(
                        modifier = Modifier
                            .heightIn(min = MichiSpacing.minTouchTarget)
                            .clip(MichiShapes.sm)
                            .background(GlassFillLow)
                            .border(0.65.dp, GlassBorderLow, MichiShapes.sm)
                            .clickable(role = Role.Button, onClick = onNavigateToPlaylists)
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                            .testTag("library_playlists_button"),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.LibraryMusic,
                            contentDescription = null,
                            tint = PureWhite,
                            modifier = Modifier.size(18.dp),
                        )
                        Text(
                            text = "Listas",
                            color = PureWhite,
                            style = MichiTypography.metadata,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                        )
                    }

                    MichiIconAction(
                        icon = Icons.Filled.Add,
                        contentDescription = "Nueva Playlist",
                        onClick = { showCreatePlaylist = true },
                        tint = PureWhite,
                        iconSize = 24.dp,
                        minTargetSize = MichiSpacing.minTouchTarget,
                        testTag = "library_add_playlist_button",
                    )
                }
            }

            // Library Scope Selector (Todas / Este teléfono / etc.)
            if (shouldShowLibraryScopeSelector(availableLibraries.size, activeScope is org.michimusic.core.models.LibraryScope.All)) LazyRow(
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

            // M60: content type is the dominant library navigation level.
            // Collection scope above is omitted if there is no scope choice.
            AeroSegmentRail(
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
            ) {
                LibraryTab.values().forEach { tab ->
                    AeroSegmentedChoice(
                        label = tab.displayName,
                        selected = selectedTab == tab,
                        onClick = { selectedTab = tab },
                        modifier = Modifier.weight(1f),
                        testTag = "library_tab_${tab.name.lowercase()}",
                    )
                }
            }

            if (selectedTab == LibraryTab.SONGS) {
                LazyRow(
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 5.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(LibraryFilter.values()) { filter ->
                        AeroSegmentedChoice(
                            label = filter.displayName,
                            selected = selectedFilter == filter,
                            onClick = { selectedFilter = filter },
                            treatment = AeroSegmentTreatment.Filter,
                            testTag = "library_filter_${filter.name.lowercase()}",
                        )
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
                // M55: one editorial empty state, with a real rescan action and no fake data.
                Box(
                    modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 18.dp),
                    contentAlignment = Alignment.TopCenter,
                ) {
                    AeroEmptyEditorial(
                        eyebrow = "TU COLECCIÓN",
                        title = "Sin música disponible",
                        description = "Comprueba los permisos multimedia y las fuentes de tu biblioteca."
                            + " También puedes volver a buscar archivos de audio.",
                        icon = Icons.Filled.LibraryMusic,
                    ) {
                        AeroActionButton(
                            label = "Volver a escanear",
                            onClick = { viewModel.loadMedia() },
                            icon = Icons.Filled.Refresh,
                            kind = AeroActionKind.Primary,
                            modifier = Modifier.fillMaxWidth(),
                            testTag = "library_rescan_button",
                        )
                    }
                }
            } else {
                when (selectedTab) {
                    LibraryTab.SONGS -> {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            if (filteredTracks.isEmpty()) {
                                item(key = "library_filter_empty") {
                                    AeroEmptyEditorial(
                                        eyebrow = "FILTRO ACTIVO",
                                        title = "No hay coincidencias",
                                        description = "No encontramos pistas con este filtro en tu biblioteca.",
                                        icon = Icons.Filled.LibraryMusic,
                                    ) {
                                        AeroActionButton(
                                            label = "Mostrar todas",
                                            onClick = { selectedFilter = LibraryFilter.ALL },
                                            kind = AeroActionKind.Secondary,
                                            modifier = Modifier.fillMaxWidth(),
                                            testTag = "library_clear_filter",
                                        )
                                    }
                                }
                            }
                            items(filteredTracks, key = { it.id }) { track ->
                                val isCurrent = currentTrack?.id == track.id
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(MichiShapes.sm)
                                        .background(if (isCurrent) TertiaryCyan.copy(alpha = 0.075f) else androidx.compose.ui.graphics.Color.Transparent)
                                        .clickable(role = Role.Button) {
                                            // The list can change under an open row; a -1 coerced to 0
                                            // would play the first track instead of the tapped one.
                                            indexOfTrackOrNull(filteredTracks, track.id)?.let { idx ->
                                                playQueueSafe(filteredTracks, idx)
                                            }
                                        },
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 10.dp, vertical = 8.dp),
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

                                        MichiIconAction(
                                            icon = Icons.Default.MoreVert,
                                            contentDescription = "Opciones para ${track.title}",
                                            onClick = { selectedTrackForMenu = track },
                                            tint = TextSecondary,
                                            iconSize = 20.dp,
                                            minTargetSize = MichiSpacing.minTouchTarget,
                                        )
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
                                        selectedAlbumId = selectedAlbumId,
                                        onAlbumSelected = { selectedAlbumId = it },
                                        onAlbumClick = { idx ->
                                            // Resolve strictly by id: the carousel index can be
                                            // stale mid-animation, and a positional fallback would
                                            // start an album the user never tapped.
                                            val selectedCoverAlbum = coverFlowAlbums.getOrNull(idx)
                                            val selectedAlbum = selectedCoverAlbum?.let { cover ->
                                                albums.find { it.album.id == cover.id }
                                            }
                                            if (selectedAlbum != null && selectedAlbum.tracks.isNotEmpty()) {
                                                playQueueSafe(selectedAlbum.tracks, 0)
                                            }
                                        },
                                        modifier = Modifier.fillMaxSize(),
                                    )
                                }

                                Spacer(Modifier.height(8.dp))

                                val currentAlbum = albums.find { it.album.id == selectedAlbumId } ?: albums.firstOrNull()
                                if (currentAlbum != null) {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 20.dp, vertical = 6.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.spacedBy(4.dp),
                                    ) {
                                        Text(
                                            text = currentAlbum.album.title,
                                            style = MichiTypography.sectionTitle.copy(fontSize = 18.sp),
                                            fontWeight = FontWeight.Bold,
                                            color = PureWhite,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                        )
                                        val albumMeta = buildString {
                                            append(currentAlbum.album.artist)
                                            if (currentAlbum.album.year > 0) {
                                                append(" · ")
                                                append(currentAlbum.album.year)
                                            }
                                            append(" · ")
                                            append(currentAlbum.tracks.size)
                                            append(if (currentAlbum.tracks.size == 1) " canción" else " canciones")
                                        }
                                        Text(
                                            text = albumMeta,
                                            style = MichiTypography.metadata,
                                            color = TextSecondary,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                        )

                                        Row(
                                            modifier = Modifier.padding(top = 6.dp, bottom = 4.dp),
                                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            MichiIconAction(
                                                icon = Icons.Default.PlayArrow,
                                                contentDescription = "Reproducir ${currentAlbum.album.title}",
                                                onClick = {
                                                    if (currentAlbum.tracks.isNotEmpty()) {
                                                        playQueueSafe(currentAlbum.tracks, 0)
                                                    }
                                                },
                                                tint = PureWhite,
                                                iconSize = 28.dp,
                                                minTargetSize = MichiSpacing.minTouchTarget,
                                                testTag = "coverflow_play_album",
                                            )
                                            MichiIconAction(
                                                icon = Icons.Default.Shuffle,
                                                contentDescription = "Reproducir aleatorio ${currentAlbum.album.title}",
                                                onClick = {
                                                    if (currentAlbum.tracks.isNotEmpty()) {
                                                        playQueueSafe(currentAlbum.tracks.shuffled(), 0)
                                                    }
                                                },
                                                tint = TertiaryCyan,
                                                iconSize = 22.dp,
                                                minTargetSize = MichiSpacing.minTouchTarget,
                                                testTag = "coverflow_shuffle_album",
                                            )
                                            MichiIconAction(
                                                icon = Icons.Default.Add,
                                                contentDescription = "Añadir álbum a playlist",
                                                onClick = { albumToAddToPlaylist = currentAlbum },
                                                tint = TextSecondary,
                                                iconSize = 22.dp,
                                                minTargetSize = MichiSpacing.minTouchTarget,
                                                testTag = "coverflow_add_to_playlist",
                                            )
                                        }
                                    }

                                    Spacer(Modifier.height(4.dp))

                                    // Interactive in-screen album track list
                                    val activeTrack = presentation.currentTrack
                                    LazyColumn(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .weight(1f)
                                            .padding(horizontal = 20.dp),
                                        verticalArrangement = Arrangement.spacedBy(4.dp),
                                    ) {
                                        itemsIndexed(currentAlbum.tracks, key = { _, track -> track.id }) { index, track ->
                                            val isCurrentPlaying = activeTrack?.id == track.id
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .clip(MichiShapes.sm)
                                                    .clickable {
                                                        playQueueSafe(currentAlbum.tracks, index)
                                                    }
                                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                            ) {
                                                if (isCurrentPlaying) {
                                                    Box(modifier = Modifier.width(28.dp), contentAlignment = Alignment.CenterStart) {
                                                        EqualizerWaveBars(
                                                            isPlaying = isPlaying,
                                                            modifier = Modifier.size(16.dp),
                                                            color = PrimaryPinkContainer,
                                                        )
                                                    }
                                                } else {
                                                    Text(
                                                        text = "${index + 1}",
                                                        style = MichiTypography.metadata,
                                                        color = TextMuted,
                                                        modifier = Modifier.width(28.dp),
                                                    )
                                                }
                                                Column(modifier = Modifier.weight(1f)) {
                                                    Text(
                                                        text = track.title,
                                                        style = MichiTypography.trackTitle,
                                                        color = if (isCurrentPlaying) PrimaryPinkContainer else PureWhite,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis,
                                                    )
                                                    Text(
                                                        text = track.artist,
                                                        style = MichiTypography.microLabel,
                                                        color = TextSecondary,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis,
                                                    )
                                                }
                                                Text(
                                                    text = formatTimeMillis(track.duration),
                                                    style = MichiTypography.microLabel,
                                                    color = TextMuted,
                                                )
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
                                                playQueueSafe(localAlbum.tracks, 0)
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
                                            org.michimusic.mobile.ui.components.AeroArtworkMat(
                                                modifier = Modifier.size(58.dp),
                                                cornerRadius = 10.dp,
                                                inset = 3.dp,
                                            ) {
                                                AlbumArtView(
                                                    coverStyle = coverStyleFor(localAlbum.album.coverId ?: localAlbum.album.title),
                                                    imageModel = localAlbum.album.coverId,
                                                    fallbackMediaUri = localAlbum.tracks.firstOrNull()?.filepath,
                                                    modifier = Modifier.fillMaxSize(),
                                                    cornerRadius = 10.dp,
                                                    borderWidth = 0.dp,
                                                )
                                            }

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
                                                    text = localAlbum.album.artist + " • " +
                                                        pluralStringResource(R.plurals.track_count, localAlbum.tracks.size, localAlbum.tracks.size),
                                                    fontSize = 12.sp,
                                                    color = TextSecondary,
                                                )
                                            }

                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                            ) {
                                                MichiIconAction(
                                                    icon = Icons.Default.Add,
                                                    contentDescription = "Añadir ${localAlbum.album.title} a playlist",
                                                    onClick = { albumToAddToPlaylist = localAlbum },
                                                    tint = TextSecondary,
                                                    iconSize = 20.dp,
                                                    minTargetSize = MichiSpacing.minTouchTarget,
                                                )
                                                MichiIconAction(
                                                    icon = Icons.Default.PlayArrow,
                                                    contentDescription = "Reproducir ${localAlbum.album.title}",
                                                    onClick = {
                                                        if (localAlbum.tracks.isNotEmpty()) {
                                                            playQueueSafe(localAlbum.tracks, 0)
                                                        }
                                                    },
                                                    tint = PureWhite,
                                                    iconSize = 24.dp,
                                                    minTargetSize = MichiSpacing.minTouchTarget,
                                                )
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
                        // Local-only explorer: we do not invent photos, MBIDs or biographies.
                        artistsList.firstOrNull { it.first == selectedArtistName }?.let { (name, groups) ->
                            ArtistLibraryDetailDialog(
                                artistName = name,
                                artistAlbums = groups,
                                libraryId = when (val scope = activeScope) {
                                    is org.michimusic.core.models.LibraryScope.One -> scope.libraryId
                                    org.michimusic.core.models.LibraryScope.All ->
                                        availableLibraries.singleOrNull()?.id
                                },
                                onDismiss = { selectedArtistName = null },
                                onPlayTracks = { tracks ->
                                    if (tracks.isNotEmpty()) {
                                        playQueueSafe(tracks, 0)
                                        selectedArtistName = null
                                    }
                                },
                            )
                        }

                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            items(artistsList, key = { it.first }) { (artistName, artistAlbums) ->
                                val artistTracks = artistAlbums.flatMap { it.tracks }
                                GlassCard(
                                    modifier = Modifier.fillMaxWidth().testTag("artist_open_detail_${artistName}"),
                                    backgroundColor = GlassFillLow,
                                    borderColor = GlassBorderLow,
                                    onClick = { selectedArtistName = artistName },
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
                                                text = pluralStringResource(R.plurals.album_count, artistAlbums.size, artistAlbums.size) +
                                                    " • " +
                                                    pluralStringResource(R.plurals.song_count, artistTracks.size, artistTracks.size),
                                                fontSize = 12.sp,
                                                color = TextSecondary,
                                            )
                                        }

                                        MichiIconAction(
                                            icon = Icons.Default.PlayArrow,
                                            contentDescription = "Reproducir ${artistName.ifBlank { "Artista Desconocido" }}",
                                            onClick = { if (artistTracks.isNotEmpty()) playQueueSafe(artistTracks, 0) },
                                            enabled = artistTracks.isNotEmpty(),
                                            tint = TertiaryCyan,
                                            iconSize = 22.dp,
                                            minTargetSize = MichiSpacing.minTouchTarget,
                                            testTag = "artist_play_button",
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
            val isCreatingPlaylist by viewModel.isCreatingPlaylist.collectAsState()
            val createPlaylistError by viewModel.createPlaylistError.collectAsState()
            CreatePlaylistDialog(
                isCreating = isCreatingPlaylist,
                errorMessage = createPlaylistError,
                onCreate = { name ->
                    viewModel.createPlaylist(name) { result ->
                        if (result is org.michimusic.mobile.screens.CreatePlaylistResult.Success) {
                            showCreatePlaylist = false
                        }
                    }
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
                            text = pluralStringResource(
                                R.plurals.select_playlist_for_album,
                                targetAlbum.tracks.size,
                                targetAlbum.album.title,
                                targetAlbum.tracks.size,
                            ),
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
                    // "Play now" must not fall back to the first track when the filter changed
                    // while the sheet was open and the song is no longer in that list.
                    indexOfTrackOrNull(filteredTracks, track.id)?.let { idx ->
                        playQueueSafe(filteredTracks, idx)
                    }
                },
                onPlayNext = {
                    sessionManager.playNext(track)
                },
                onAddToQueue = {
                    sessionManager.addToQueue(track)
                },
                onAddToPlaylist = {
                    trackToAddToPlaylist = track
                },
                onToggleFavorite = {
                    // The write can fail; an unhandled throw here would take the screen
                    // scope down with it and the sheet would stay open with a stale heart.
                    coroutineScope.launch {
                        try {
                            favoritesRepo.toggleFavorite(track.id)
                        } catch (cancelled: kotlinx.coroutines.CancellationException) {
                            throw cancelled
                        } catch (error: Exception) {
                            android.util.Log.w("AlbumsScreen", "Favorite update failed", error)
                        }
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
/** Artist Enrichment phase 1: a factual local library view, not a remote metadata claim. */
@Composable
private fun ArtistLibraryDetailDialog(
    artistName: String,
    artistAlbums: List<org.michimusic.data.repository.LocalMediaRepository.LocalAlbum>,
    libraryId: String?,
    onDismiss: () -> Unit,
    onPlayTracks: (List<Track>) -> Unit,
) {
    val tracks = remember(artistAlbums) { artistAlbums.flatMap { it.tracks } }
    var showExternal by remember(artistName) { mutableStateOf(false) }
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        AeroFloatingSurface(
            shape = RoundedCornerShape(24.dp),
            modifier = Modifier.fillMaxWidth().testTag("artist_detail_dialog"),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth()
                    .heightIn(max = (androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp.dp - 64.dp)
                        .coerceAtLeast(280.dp))
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(artistName.ifBlank { "Artista Desconocido" }, color = PureWhite,
                            fontSize = 20.sp, fontWeight = FontWeight.SemiBold,
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text("${artistAlbums.size} álbumes · ${tracks.size} canciones en tu colección",
                            color = TextSecondary, fontSize = 12.sp)
                    }
                    MichiIconAction(
                        icon = Icons.Default.Close,
                        contentDescription = "Cerrar ficha del artista",
                        onClick = onDismiss,
                        tint = TextSecondary,
                        iconSize = 20.dp,
                        minTargetSize = MichiSpacing.minTouchTarget,
                    )
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AeroSegmentedChoice(
                        label = "Mi colección", selected = !showExternal,
                        onClick = { showExternal = false }, modifier = Modifier.weight(1f),
                        testTag = "artist_local_tab",
                    )
                    AeroSegmentedChoice(
                        label = "Enriquecimiento", selected = showExternal,
                        onClick = { showExternal = true }, modifier = Modifier.weight(1f),
                        testTag = "artist_external_tab",
                    )
                }
                if (showExternal) {
                    ArtistEnrichmentPanel(
                        artistCredit = artistName,
                        libraryId = libraryId,
                        albumIds = artistAlbums.map { it.album.id },
                        localAudioUris = tracks.filter { it.source == org.michimusic.core.models.TrackSource.LOCAL && it.filepath.isNotBlank() }
                            .map { it.filepath }.filter(String::isNotBlank),
                        localTrackIds = tracks.filter { it.source == org.michimusic.core.models.TrackSource.LOCAL && it.filepath.isNotBlank() }
                            .map { it.id },
                        modifier = Modifier.weight(1f, fill = false),
                    )
                } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().weight(1f, fill = false),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(artistAlbums, key = { it.album.id }) { group ->
                        GlassCard(
                            modifier = Modifier.fillMaxWidth(),
                            backgroundColor = GlassFillLow,
                            borderColor = GlassBorderLow,
                            onClick = { if (group.tracks.isNotEmpty()) onPlayTracks(group.tracks) },
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(group.album.title, color = PureWhite, fontSize = 14.sp,
                                        fontWeight = FontWeight.SemiBold, maxLines = 2,
                                        overflow = TextOverflow.Ellipsis)
                                    Text("${group.tracks.size} canciones", color = TextSecondary, fontSize = 12.sp)
                                }
                                Icon(Icons.Default.PlayArrow, contentDescription = null,
                                    tint = TertiaryCyan, modifier = Modifier.size(20.dp))
                            }
                        }
                    }
                }
                } // Sólo pistas locales en la vista de colección.
                AeroActionButton(
                    label = "Reproducir todo",
                    onClick = { if (tracks.isNotEmpty()) onPlayTracks(tracks) },
                    modifier = Modifier.fillMaxWidth(),
                    icon = Icons.Default.PlayArrow,
                    kind = AeroActionKind.Primary,
                    enabled = tracks.isNotEmpty(),
                    testTag = "artist_detail_play_all",
                )
            }
        }
    }
}


/** Scope chooser is useful only with multiple libraries or for orphan-scope recovery. */
internal fun shouldShowLibraryScopeSelector(availableCount: Int, allSelected: Boolean): Boolean =
    availableCount > 1 || !allSelected
