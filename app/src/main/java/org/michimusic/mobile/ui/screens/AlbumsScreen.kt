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
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import org.michimusic.mobile.playback.resolvePlaybackPresentation
import org.michimusic.mobile.ui.components.premium.MichiIconAction
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
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
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import org.michimusic.core.models.Track
import org.michimusic.data.repository.FavoritesRepository
import org.michimusic.mobile.screens.AlbumsViewModel
import org.michimusic.mobile.screens.GenreLibraryViewModel
import org.michimusic.mobile.ui.components.AddToPlaylistDialog
import org.michimusic.mobile.ui.components.AlbumArtView
import org.michimusic.mobile.ui.components.AlbumMosaicGrid
import org.michimusic.mobile.ui.components.EqualizerWaveBars
import org.michimusic.mobile.ui.components.AeroCard
import org.michimusic.mobile.ui.components.TrackActionBottomSheet
import org.michimusic.mobile.ui.components.AeroPremiumBackdrop
import org.michimusic.mobile.ui.components.AeroActionButton
import org.michimusic.mobile.ui.components.AeroActionKind
import org.michimusic.mobile.ui.components.AeroEmptyEditorial
import org.michimusic.mobile.ui.components.AeroFilterChip
import org.michimusic.mobile.ui.components.AeroSegmentedChoice
import org.michimusic.mobile.ui.components.AeroSegmentRail
import org.michimusic.mobile.ui.components.AeroSegmentStack
import org.michimusic.mobile.ui.components.aeroSegmentStacking
import org.michimusic.mobile.ui.components.ArtistMosaicEntry
import org.michimusic.mobile.artist.artwork.ArtistArtworkPersistence
import org.michimusic.mobile.ui.components.ArtistMosaicGrid
import org.michimusic.mobile.ui.components.MichiCardDeck
import org.michimusic.mobile.library.rememberArtistScopedCollection
import org.michimusic.mobile.library.rememberArtistIdentityMemberships
import org.michimusic.mobile.ui.components.coverStyleFor
import org.michimusic.mobile.ui.components.formatTimeMillis
import org.michimusic.mobile.ui.coverflow.MichiCoverFlowHost
import org.michimusic.mobile.ui.coverflow.coverFlowPositionLabel
import org.michimusic.mobile.ui.coverflow.coverFlowStageHeightDp
import org.michimusic.mobile.ui.theme.GlassBorderHigh
import org.michimusic.mobile.ui.theme.GlassBorderLow
import org.michimusic.mobile.ui.theme.GlassFillHigh
import org.michimusic.mobile.ui.theme.GlassFillLow
import org.michimusic.mobile.ui.theme.LocalVisualPreferences
import org.michimusic.mobile.ui.theme.MichiShapes
import org.michimusic.mobile.ui.theme.MichiSpacing
import org.michimusic.mobile.ui.theme.MichiTypography
import org.michimusic.mobile.ui.theme.OnSurfaceVariant
import org.michimusic.mobile.ui.theme.PrimaryPink
import org.michimusic.mobile.ui.theme.PrimaryPinkContainer
import org.michimusic.mobile.ui.theme.PureWhite
import org.michimusic.mobile.ui.theme.SurfaceObsidian
import org.michimusic.mobile.ui.theme.TertiaryCyan
import org.michimusic.mobile.ui.theme.TextMuted
import org.michimusic.mobile.ui.theme.TextSecondary
import org.michimusic.player.AudioController

enum class LibraryTab(val displayName: String) {
    SONGS("Canciones"),
    ALBUMS("Álbumes"),
    ARTISTS("Artistas"),
    GENRES("Géneros"),
}

enum class LibraryFilter(val displayName: String) {
    ALL("Todo"),
    LOSSLESS("Lossless"),
    DOWNLOADED("Descargadas"),
}

@Composable
fun AlbumsScreen(
    onNavigateToPlaylists: () -> Unit = {},
    onNavigateToArtist: (String) -> Unit = {},
    sessionManager: org.michimusic.mobile.playback.PlaybackSessionManager = koinInject(),
    modifier: Modifier = Modifier,
    onNavigateToAlbum: (String) -> Unit = {},
) {
    val viewModel: AlbumsViewModel = koinViewModel()
    val genreLibraryViewModel: GenreLibraryViewModel = koinViewModel()
    val context = androidx.compose.ui.platform.LocalContext.current
    val allTracks by viewModel.allTracks.collectAsStateWithLifecycle()
    val albums by viewModel.albums.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
    val mediaState by viewModel.mediaState.collectAsStateWithLifecycle()
    org.michimusic.mobile.ui.components.MediaPermissionWatcher(mediaState) {
        viewModel.loadMedia(forceRefresh = true)
    }

    val audioController: AudioController = koinInject()
    val sessionState by sessionManager.sessionState.collectAsStateWithLifecycle()
    val playerState by audioController.state.collectAsStateWithLifecycle()
    val presentation = resolvePlaybackPresentation(sessionState, playerState)
    val currentTrack = presentation.currentTrack
    val isPlaying = presentation.isPlaying

    fun playQueueSafe(tracks: List<Track>, index: Int) {
        sessionManager.playQueue(tracks, index)
    }

    val libraryScopeStore: org.michimusic.mobile.library.LibraryScopeStore = koinInject()
    val musicLibraryRepo: org.michimusic.data.repository.MusicLibraryRepository = koinInject()
    val activeScope by libraryScopeStore.activeScope.collectAsStateWithLifecycle()
    val availableLibraries by musicLibraryRepo.observeLibraries().collectAsStateWithLifecycle(initialValue = emptyList())

    var selectedTab by rememberSaveable { mutableStateOf(LibraryTab.SONGS) }
    var selectedFilter by rememberSaveable { mutableStateOf(LibraryFilter.ALL) }
    var viewMode by rememberSaveable { mutableStateOf(LibraryViewMode.MOSAIC) }
    var artistCardsMode by rememberSaveable { mutableStateOf(false) }
    var selectedArtistCard by rememberSaveable(activeScope) { mutableStateOf<String?>(null) }
    // Keep genre exploration state while switching Biblioteca tabs, but reset it
    // when the owning library scope changes (never bleed another owner's state).
    val genreViewState = key(activeScope) { androidx.compose.runtime.saveable.rememberSaveableStateHolder() }
    // A library change must never inherit another scope's scroll index.
    val albumGridState = key(activeScope) { rememberLazyGridState() }
    val albumListState = key(activeScope) { rememberLazyListState() }
    var selectedAlbumId by rememberSaveable(activeScope) { mutableStateOf<String?>(null) }
    var selectedAlbumContinuityKey by rememberSaveable(activeScope) { mutableStateOf<String?>(null) }

    var albumToAddToPlaylist by remember { mutableStateOf<org.michimusic.data.repository.LocalMediaRepository.LocalAlbum?>(null) }
    var selectedTrackForMenu by remember { mutableStateOf<Track?>(null) }
    var trackToAddToPlaylist by remember { mutableStateOf<Track?>(null) }
    val rootAuthority: org.michimusic.mobile.library.MusicRootAuthority = koinInject()
    val favoritesRepo: FavoritesRepository = koinInject()
    val favoriteIds by favoritesRepo.observeFavoriteTrackIds().collectAsStateWithLifecycle(initialValue = emptySet())
    val coroutineScope = rememberCoroutineScope()
    val playlists by viewModel.playlists.collectAsStateWithLifecycle()
    val downloadedTrackIds by viewModel.downloadedTrackIds.collectAsStateWithLifecycle()

    // Only observe the full canonical library graph when Artistas is visible.
    // This also supports synced/registered artists when MediaStore is empty.
    var artistScopeRetry by remember { mutableIntStateOf(0) }
    val scopedArtistCollection = if (
        selectedTab == LibraryTab.ARTISTS ||
        selectedTab == LibraryTab.ALBUMS ||
        selectedTab == LibraryTab.GENRES
    ) {
        rememberArtistScopedCollection(
            localAlbums = albums,
            scope = activeScope,
            libraries = availableLibraries,
            repository = musicLibraryRepo,
            retryKey = artistScopeRetry,
        )
    } else null

    LaunchedEffect(Unit) {
        viewModel.loadMedia()
    }

    val scopedAlbums = scopedArtistCollection?.takeIf { it.ready }?.albums.orEmpty()
    val identityCounts = remember(scopedAlbums, activeScope) { albumNavigationIdentityCounts(scopedAlbums, activeScope) }
    // Ambiguous duplicate identities fail closed: a carousel selection must always
    // map back to exactly one album and must never persist an index as identity.
    val coverFlowPresentation = remember(scopedAlbums, activeScope) {
        albumCoverFlowPresentation(scopedAlbums, activeScope)
    }
    val albumByToken = coverFlowPresentation.albumById
    val coverFlowAlbums = coverFlowPresentation.albums
    LaunchedEffect(
        activeScope,
        selectedTab,
        coverFlowPresentation,
        isLoading,
        scopedArtistCollection?.ready,
        scopedArtistCollection?.errorMessage,
    ) {
        if (selectedTab != LibraryTab.ALBUMS) return@LaunchedEffect
        val selection = selectedAlbumId?.let { CoverFlowSelection(it, selectedAlbumContinuityKey) }
        val reconciled = reconcileCoverFlowSelection(
            selection = selection,
            presentation = coverFlowPresentation,
            refreshInProgress = isLoading ||
                (scopedArtistCollection?.ready != true && scopedArtistCollection?.errorMessage == null),
        )
        selectedAlbumId = reconciled?.id
        selectedAlbumContinuityKey = reconciled?.continuityKey
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
            LibraryHeaderLayout(
                titleContent = { titleModifier ->
                    Column(modifier = titleModifier) {
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
                        if (scopedArtistCollection?.ready == true || (!isLoading && allTracks.isNotEmpty())) {
                            val displayTracks = if (scopedArtistCollection?.ready == true)
                                scopedArtistCollection.albums.sumOf { it.tracks.size } else allTracks.size
                            val displayAlbums = if (scopedArtistCollection?.ready == true)
                                scopedArtistCollection.albums.size else albums.size
                            Text(
                                text = pluralStringResource(R.plurals.track_count, displayTracks, displayTracks) +
                                    "  ·  " +
                                    pluralStringResource(R.plurals.album_count, displayAlbums, displayAlbums),
                                style = MichiTypography.microLabel,
                                color = TextSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(top = 5.dp).testTag("library_real_count"),
                            )
                        }
                    }
                },
                actionsContent = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (selectedTab == LibraryTab.ALBUMS && scopedAlbums.isNotEmpty()) {
                            AlbumViewModeToggle(
                                viewMode = viewMode,
                                onViewModeChange = { viewMode = it },
                            )
                        } else if (selectedTab == LibraryTab.ARTISTS && scopedAlbums.isNotEmpty()) {
                            ArtistViewModeToggle(
                                cardsMode = artistCardsMode,
                                onCardsModeChange = { artistCardsMode = it },
                            )
                        }

                        // Playlist creation belongs to the Playlists destination.
                        // The library header keeps only browsing controls.
                    }
                },
            )

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
            val libraryConfiguration = androidx.compose.ui.platform.LocalConfiguration.current
            if (aeroSegmentStacking(
                    libraryConfiguration.screenWidthDp.toFloat(),
                    libraryConfiguration.fontScale,
                )
            ) {
                AeroSegmentStack(
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 3.dp),
                    compact = true,
                ) {
                    // Four content categories form two rows on narrow screens and
                    // large font scales. A four-item vertical stack hides the library.
                    LibraryTab.values().toList().chunked(2).forEach { pair ->
                        Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                            pair.forEach { tab ->
                                AeroSegmentedChoice(
                                    label = tab.displayName,
                                    selected = selectedTab == tab,
                                    onClick = { selectedTab = tab },
                                    modifier = Modifier.weight(1f),
                                    compact = true,
                                    testTag = "library_tab_${tab.name.lowercase()}",
                                )
                            }
                        }
                    }
                }
            } else {
                AeroSegmentRail(
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 3.dp),
                    compact = true,
                ) {
                    LibraryTab.values().forEach { tab ->
                        AeroSegmentedChoice(
                            label = tab.displayName,
                            selected = selectedTab == tab,
                            onClick = { selectedTab = tab },
                            modifier = Modifier.weight(1f),
                            compact = true,
                            testTag = "library_tab_${tab.name.lowercase()}",
                        )
                    }
                }
            }

            if (selectedTab == LibraryTab.SONGS) {
                LazyRow(
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 3.dp)
                        .testTag("library_filter_row"),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(LibraryFilter.values()) { filter ->
                        AeroFilterChip(
                            label = filter.displayName,
                            selected = selectedFilter == filter,
                            onClick = { selectedFilter = filter },
                            testTag = "library_filter_${filter.name.lowercase()}",
                        )
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            // Main Content Area
            if (selectedTab == LibraryTab.SONGS && isLoading && allTracks.isEmpty()) {
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
            } else if (selectedTab == LibraryTab.SONGS &&
                (mediaState == org.michimusic.mobile.screens.MediaLibraryState.PERMISSION_DENIED ||
                    mediaState == org.michimusic.mobile.screens.MediaLibraryState.QUERY_ERROR)
            ) {
                // Media truth in the library tab: the permission/query panel replaces the
                // editorial empty state, which is only valid after a successful query.
                Box(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 18.dp),
                    contentAlignment = Alignment.TopCenter,
                ) {
                    org.michimusic.mobile.ui.components.MediaTruthPanel(
                        state = mediaState,
                        onPermissionAvailable = { viewModel.loadMedia(forceRefresh = true) },
                        onRetry = { viewModel.loadMedia(forceRefresh = true) },
                    )
                }
            } else if (selectedTab == LibraryTab.SONGS && allTracks.isEmpty()) {
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
                        if (scopedArtistCollection?.errorMessage != null) {
                            Column(Modifier.fillMaxSize().padding(20.dp)) {
                                Text(scopedArtistCollection?.errorMessage.orEmpty(), color = TextSecondary)
                                AeroActionButton("Reintentar biblioteca", onClick = { artistScopeRetry++ })
                            }
                        } else if (scopedArtistCollection?.ready != true) {
                            Box(Modifier.fillMaxSize().testTag("album_scope_loading"),
                                contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(color = PrimaryPink)
                            }
                        } else if (scopedAlbums.isEmpty() &&
                            (mediaState == org.michimusic.mobile.screens.MediaLibraryState.PERMISSION_DENIED ||
                                mediaState == org.michimusic.mobile.screens.MediaLibraryState.QUERY_ERROR)
                        ) {
                            Box(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 18.dp),
                                contentAlignment = Alignment.TopCenter,
                            ) {
                                org.michimusic.mobile.ui.components.MediaTruthPanel(
                                    state = mediaState,
                                    onPermissionAvailable = { artistScopeRetry++; viewModel.loadMedia(forceRefresh = true) },
                                    onRetry = { artistScopeRetry++; viewModel.loadMedia(forceRefresh = true) },
                                )
                            }
                        } else if (scopedAlbums.isEmpty()) {
                            Box(
                                modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 16.dp),
                                contentAlignment = Alignment.TopCenter,
                            ) {
                                AeroEmptyEditorial(
                                    eyebrow = "TU COLECCIÓN",
                                    title = "No hay álbumes disponibles",
                                    description = "No hay álbumes en la biblioteca seleccionada. Comprueba el origen o cambia de biblioteca.",
                                    icon = Icons.Filled.LibraryMusic,
                                ) {
                                    AeroActionButton(
                                        label = "Reintentar biblioteca",
                                        onClick = {
                                            artistScopeRetry++
                                            viewModel.loadMedia(forceRefresh = true)
                                        },
                                        kind = AeroActionKind.Primary,
                                        modifier = Modifier.fillMaxWidth(),
                                        testTag = "album_scope_empty_retry",
                                    )
                                }
                            }
                        } else {
                            AlbumPresentationContent(
                                viewMode = viewMode,
                                mosaicContent = {
                                    AlbumMosaicGrid(
                                        albums = scopedAlbums,
                                        scope = activeScope,
                                        onOpenAlbum = onNavigateToAlbum,
                                        modifier = Modifier.fillMaxSize(),
                                        state = albumGridState,
                                    )
                                },
                                cardsContent = {
                                    if (coverFlowAlbums.isEmpty()) {
                                        Box(
                                            modifier = Modifier.fillMaxSize().padding(20.dp)
                                                .testTag("album_cards_identity_empty"),
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            Text(
                                                "No hay álbumes con identidad única para esta vista.",
                                                color = TextSecondary,
                                                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                            )
                                        }
                                    } else Column(
                                        modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                    ) {
                                        key(activeScope) {
                                            org.michimusic.mobile.ui.components.MichiVinylCrate(
                                                items = coverFlowAlbums,
                                                itemKey = { it.id },
                                                itemLabel = { it.title },
                                                selectedKey = selectedAlbumId,
                                                onSelect = { id ->
                                                    val selection = coverFlowSelection(id, coverFlowPresentation)
                                                    selectedAlbumId = selection?.id
                                                    selectedAlbumContinuityKey = selection?.continuityKey
                                                },
                                                onOpenSelected = { card -> onNavigateToAlbum(card.id) },
                                                modifier = Modifier.fillMaxWidth().weight(1f)
                                                    .testTag("album_cards_deck"),
                                            ) { card ->
                                                AlbumArtView(
                                                    coverStyle = coverStyleFor(card.coverUri.ifBlank { card.title }),
                                                    imageModel = card.coverUri,
                                                    fallbackMediaUri = card.sourceAudioUri,
                                                    modifier = Modifier.fillMaxSize(),
                                                    cornerRadius = 12.dp,
                                                )
                                            }
                                        }
                                        val currentAlbum = selectedAlbumId?.let(albumByToken::get)
                                        if (currentAlbum == null) {
                                            Text(
                                                "Selecciona una tarjeta válida para ver las acciones del álbum.",
                                                color = TextSecondary,
                                                modifier = Modifier.padding(horizontal = 20.dp),
                                            )
                                        } else {
                                            val playableAlbumTracks = remember(
                                                currentAlbum.tracks,
                                                scopedArtistCollection.playableTrackIds,
                                            ) {
                                                canonicalPlayableAlbumTracks(
                                                    currentAlbum.tracks,
                                                    scopedArtistCollection.playableTrackIds,
                                                )
                                            }
                                            Column(
                                                modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)
                                                    .testTag("album_vinyl_editorial"),
                                                horizontalAlignment = Alignment.CenterHorizontally,
                                                verticalArrangement = Arrangement.spacedBy(3.dp),
                                            ) {
                                                Text(
                                                    currentAlbum.album.title,
                                                    style = MichiTypography.sectionTitle,
                                                    color = PureWhite,
                                                    maxLines = if (androidx.compose.ui.platform.LocalDensity.current.fontScale >= 1.5f) 3 else 2,
                                                    overflow = TextOverflow.Ellipsis,
                                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                                )
                                                Text(
                                                    listOfNotNull(
                                                        currentAlbum.album.artist,
                                                        currentAlbum.album.year.takeIf { it > 0 }?.toString(),
                                                        "${currentAlbum.tracks.size} pistas",
                                                    ).joinToString(" · "),
                                                    style = MichiTypography.metadata,
                                                    color = TextSecondary,
                                                    maxLines = 2,
                                                    overflow = TextOverflow.Ellipsis,
                                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                                )
                                            }
                                            CoverFlowAlbumActions(
                                                albumTitle = currentAlbum.album.title,
                                                hasPlayableTracks = playableAlbumTracks.isNotEmpty(),
                                                onPlay = {
                                                    if (playableAlbumTracks.isNotEmpty()) {
                                                        playQueueSafe(playableAlbumTracks, 0)
                                                    }
                                                },
                                                onShuffle = {
                                                    if (playableAlbumTracks.isNotEmpty()) {
                                                        playQueueSafe(playableAlbumTracks.shuffled(), 0)
                                                    }
                                                },
                                                onAddToPlaylist = { albumToAddToPlaylist = currentAlbum },
                                            )
                                        }
                                        Spacer(Modifier.height(12.dp))
                                    }
                                },
                                coverFlowContent = {
                            if (coverFlowAlbums.isEmpty()) {
                                Box(
                                    modifier = Modifier.fillMaxSize().padding(20.dp)
                                        .testTag("coverflow_identity_empty"),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        "CoverFlow no puede mostrar álbumes con identidad ambigua.",
                                        color = TextSecondary,
                                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                    )
                                }
                            } else {
                                val currentAlbum = selectedAlbumId?.let(albumByToken::get)
                                val currentPresentation = remember(currentAlbum?.tracks) {
                                    currentAlbum?.let { albumTrackPresentation(it.tracks) }
                                }
                                val playableAlbumTracks = remember(
                                    currentAlbum?.tracks, scopedArtistCollection.playableTrackIds,
                                ) {
                                    currentAlbum?.let {
                                        canonicalPlayableAlbumTracks(
                                            it.tracks, scopedArtistCollection.playableTrackIds,
                                        )
                                    } ?: emptyList()
                                }
                                val coverFlowScrollState = rememberLazyListState()
                                LaunchedEffect(selectedAlbumId, activeScope) {
                                    coverFlowScrollState.scrollToItem(0)
                                }
                                LazyColumn(
                                    state = coverFlowScrollState,
                                    modifier = Modifier.fillMaxSize().testTag("coverflow_page_scroll"),
                                    contentPadding = PaddingValues(bottom = 24.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    item(key = "coverflow_stage") {
                                Box(
                                    modifier = Modifier.fillMaxWidth().height(
                                        coverFlowStageHeightDp(
                                            androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp.toFloat(),
                                        ).dp,
                                    ),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    MichiCoverFlowHost(
                                        albums = coverFlowAlbums,
                                        selectedAlbumId = selectedAlbumId,
                                        onAlbumSelected = { id ->
                                            val selection = coverFlowSelection(id, coverFlowPresentation)
                                            selectedAlbumId = selection?.id
                                            selectedAlbumContinuityKey = selection?.continuityKey
                                        },
                                        onAlbumClick = { index ->
                                            val selected = coverFlowAlbums.getOrNull(index)
                                            if (selected != null && identityCounts[selected.id] == 1) {
                                                onNavigateToAlbum(selected.id)
                                            }
                                        },
                                        modifier = Modifier.fillMaxSize(),
                                    )
                                }
                                    }
                                    item(key = "coverflow_album_details") {
                                Spacer(Modifier.height(8.dp))
                                if (currentAlbum == null) {
                                    Text(
                                        "Selecciona una portada válida para ver las acciones del álbum.",
                                        color = TextSecondary,
                                        modifier = Modifier.padding(horizontal = 20.dp),
                                    )
                                } else {
                                    val currentIdentity = albumNavigationIdentity(currentAlbum, activeScope)
                                    val positionLabel = coverFlowPositionLabel(
                                        coverFlowAlbums.indexOfFirst { it.id == currentIdentity },
                                        coverFlowAlbums.size,
                                    )
                                    Text(
                                        text = if (positionLabel != null) "$positionLabel · Toca la portada para abrir"
                                        else "Toca la portada para abrir",
                                        style = MichiTypography.microLabel,
                                        color = TextMuted,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 2.dp)
                                            .testTag("coverflow_position_hint"),
                                    )
                                    Column(
                                        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.spacedBy(4.dp),
                                    ) {
                                        Text(
                                            currentAlbum.album.title,
                                            style = MichiTypography.sectionTitle.copy(fontSize = 18.sp),
                                            fontWeight = FontWeight.Bold,
                                            color = PureWhite,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis,
                                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                        )
                                        Text(
                                            listOfNotNull(
                                                currentAlbum.album.artist,
                                                currentAlbum.album.year.takeIf { it > 0 }?.toString(),
                                                pluralStringResource(
                                                    R.plurals.track_count,
                                                    currentAlbum.tracks.size,
                                                    currentAlbum.tracks.size,
                                                ),
                                            ).joinToString(" · "),
                                            style = MichiTypography.metadata,
                                            color = TextSecondary,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                        )
                                        CoverFlowAlbumActions(
                                            albumTitle = currentAlbum.album.title,
                                            hasPlayableTracks = playableAlbumTracks.isNotEmpty(),
                                            onPlay = {
                                                if (playableAlbumTracks.isNotEmpty()) playQueueSafe(playableAlbumTracks, 0)
                                            },
                                            onShuffle = {
                                                if (playableAlbumTracks.isNotEmpty()) {
                                                    playQueueSafe(playableAlbumTracks.shuffled(), 0)
                                                }
                                            },
                                            onAddToPlaylist = { albumToAddToPlaylist = currentAlbum },
                                        )
                                    }

                                    Spacer(Modifier.height(10.dp))
                                    Text(
                                        "PISTAS · ${currentAlbum.tracks.size}",
                                        style = MichiTypography.microLabel,
                                        color = TextSecondary,
                                        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 4.dp),
                                    )
                                }
                                    }
                                    if (currentPresentation != null) {
                                        itemsIndexed(
                                            currentPresentation.tracks,
                                            key = { _, presented -> presented.track.id },
                                        ) { index, presented ->
                                            val track = presented.track
                                            val available = track.id in scopedArtistCollection.playableTrackIds
                                            val isCurrentPlaying = presentation.currentTrack?.id == track.id
                                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                                val disc = presented.position?.discNumber
                                                val previousDisc = currentPresentation.tracks
                                                    .getOrNull(index - 1)?.position?.discNumber
                                                if (currentPresentation.showDiscHeaders && disc != null && disc != previousDisc) {
                                                    Text(
                                                        "Disco $disc",
                                                        color = TextSecondary,
                                                        style = MichiTypography.microLabel,
                                                        modifier = Modifier.padding(start = 4.dp, top = 4.dp),
                                                    )
                                                }
                                                Row(
                                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp)
                                                        .clip(MichiShapes.sm)
                                                        .heightIn(min = 52.dp).background(GlassFillLow, MichiShapes.sm)
                                                        .clickable(enabled = available) {
                                                            val exact = playableAlbumTracks.indexOfFirst { it.id == track.id }
                                                            if (exact >= 0) playQueueSafe(playableAlbumTracks, exact)
                                                        }.padding(horizontal = 12.dp, vertical = 8.dp),
                                                    verticalAlignment = Alignment.CenterVertically,
                                                ) {
                                                    if (isCurrentPlaying) {
                                                        Box(Modifier.width(36.dp), contentAlignment = Alignment.CenterStart) {
                                                            EqualizerWaveBars(
                                                                isPlaying = isPlaying,
                                                                modifier = Modifier.size(16.dp),
                                                                color = PrimaryPinkContainer,
                                                            )
                                                        }
                                                    } else {
                                                        Text(
                                                            presented.position?.trackNumber?.toString() ?: "—",
                                                            style = MichiTypography.metadata,
                                                            color = TextMuted,
                                                            modifier = Modifier.width(36.dp),
                                                        )
                                                    }
                                                    Column(Modifier.weight(1f)) {
                                                        Text(
                                                            track.title,
                                                            style = MichiTypography.trackTitle,
                                                            color = if (isCurrentPlaying) PrimaryPinkContainer else PureWhite,
                                                            maxLines = 1,
                                                            overflow = TextOverflow.Ellipsis,
                                                        )
                                                        Text(
                                                            listOf(track.artist, formatTimeMillis(track.duration))
                                                                .filter { it.isNotBlank() }.joinToString(" · "),
                                                            style = MichiTypography.microLabel,
                                                            color = TextSecondary,
                                                            maxLines = 1,
                                                            overflow = TextOverflow.Ellipsis,
                                                        )
                                                    }
                                                    if (!available) {
                                                        Text("No disponible", style = MichiTypography.microLabel, color = TextMuted)
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                                },
                                listContent = {
                            LazyColumn(
                                state = albumListState,
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                itemsIndexed(scopedAlbums, key = { index, group -> "${albumNavigationIdentity(group, activeScope)}#$index" }) { _, localAlbum ->
                                    val identity = albumNavigationIdentity(localAlbum, activeScope)
                                    val uniqueAlbum = identityCounts[identity] == 1
                                    val playableTracks = canonicalPlayableAlbumTracks(
                                        localAlbum.tracks,
                                        scopedArtistCollection.playableTrackIds,
                                    )
                                    // Dense scrolling album rows use the canonical Plain finish.
                                    AeroCard(
                                        modifier = Modifier.fillMaxWidth(),
                                        // Tocar la fila abre la ficha; reproducir es acción separada.
                                        onClick = if (uniqueAlbum) ({ onNavigateToAlbum(identity) }) else null,
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
                                                    text = listOfNotNull(
                                                        localAlbum.album.artist,
                                                        localAlbum.album.year.takeIf { it > 0 }?.toString(),
                                                        pluralStringResource(R.plurals.track_count, localAlbum.tracks.size, localAlbum.tracks.size),
                                                    ).joinToString(" · "),
                                                    fontSize = 12.sp,
                                                    color = TextSecondary,
                                                )
                                                if (!uniqueAlbum) {
                                                    Text("Identidad de álbum duplicada", color = TextMuted, fontSize = 11.sp)
                                                }
                                            }

                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                            ) {
                                                MichiIconAction(
                                                    icon = Icons.Default.Add,
                                                     contentDescription = "Añadir ${localAlbum.album.title} a playlist",
                                                     onClick = { albumToAddToPlaylist = localAlbum },
                                                     enabled = playableTracks.isNotEmpty(),
                                                     tint = TextSecondary,
                                                    iconSize = 20.dp,
                                                    minTargetSize = MichiSpacing.minTouchTarget,
                                                )
                                                MichiIconAction(
                                                    icon = Icons.Default.PlayArrow,
                                                    contentDescription = "Reproducir ${localAlbum.album.title}",
                                                    onClick = {
                                                        if (playableTracks.isNotEmpty()) playQueueSafe(playableTracks, 0)
                                                    },
                                                    enabled = playableTracks.isNotEmpty(),
                                                    tint = PureWhite,
                                                    iconSize = 24.dp,
                                                    minTargetSize = MichiSpacing.minTouchTarget,
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                                },
                            )
                        }
                    }

                    LibraryTab.ARTISTS -> {
                        // Membership, albums and profile identity share a SINGLE scope source.
                        // The new scope starts empty instead of flashing old library artists.
                        val scoped = scopedArtistCollection
                        if (scoped?.errorMessage != null) {
                            Column(Modifier.fillMaxSize().padding(20.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text(scoped.errorMessage, color = TextSecondary)
                                AeroActionButton("Reintentar biblioteca", onClick = { artistScopeRetry++ },
                                    testTag = "artist_scope_retry")
                            }
                        } else if (scoped == null || !scoped.ready) {
                            Box(Modifier.fillMaxSize().testTag("artist_scope_loading"),
                                contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(color = TertiaryCyan)
                            }
                        } else {
                            val multiMemberships = rememberArtistIdentityMemberships(
                                aggregate = scoped,
                                localAlbums = albums,
                                libraries = availableLibraries,
                                repository = musicLibraryRepo,
                            )
                            val artistsList = remember(scoped.albums) {
                                scoped.albums.groupBy { it.album.artist }.toList()
                            }
                            val artistEntries = remember(artistsList, multiMemberships) {
                                artistsList.map { (credit, groups) ->
                                    ArtistMosaicEntry(
                                        credit = credit,
                                        albumIds = groups.map { it.album.id },
                                        albumCount = groups.size,
                                        trackCount = groups.sumOf { it.tracks.size },
                                        profileScopes = if (scoped.identityLibraryId == null &&
                                            multiMemberships.ready && !multiMemberships.error)
                                            multiMemberships.byCredit[credit].orEmpty() else emptyList(),
                                    )
                                }
                            }
                            if (artistEntries.isEmpty()) {
                                Box(Modifier.fillMaxSize().testTag("artist_scope_empty"),
                                    contentAlignment = Alignment.Center) {
                                    Text("Esta biblioteca no contiene artistas disponibles.",
                                        color = TextSecondary, modifier = Modifier.padding(20.dp))
                                }
                            } else {
                                val artworkStore = remember(context) {
                                    ArtistArtworkPersistence.from(context)
                                }
                                val artworkRevision by remember(artworkStore, scoped.identityLibraryId) {
                                    scoped.identityLibraryId?.let(artworkStore::observeLibraryRevision)
                                        ?: flowOf(0L)
                                }.collectAsStateWithLifecycle(initialValue = 0L)
                                ArtistMosaicGrid(
                                    artists = artistEntries,
                                    libraryId = scoped.identityLibraryId,
                                    refreshToken = 0,
                                    artworkRevision = artworkRevision,
                                    onOpenArtist = onNavigateToArtist,
                                    modifier = Modifier.fillMaxSize(),
                                    artworkStore = artworkStore,
                                    cardsMode = artistCardsMode,
                                    selectedArtistCredit = selectedArtistCard,
                                    onSelectArtistCard = { selectedArtistCard = it },
                                    libraryScopeKey = activeScope,
                                )
                            }
                        }
                    }

                    LibraryTab.GENRES -> {
                        val scoped = scopedArtistCollection
                        if (scoped?.errorMessage != null) {
                            Column(Modifier.fillMaxSize().padding(20.dp)) {
                                Text(scoped.errorMessage, color = TextSecondary)
                                AeroActionButton("Reintentar biblioteca", onClick = { artistScopeRetry++ })
                            }
                        } else if (scoped == null || !scoped.ready) {
                            Box(Modifier.fillMaxSize().testTag("genre_scope_loading"),
                                contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(color = PrimaryPink)
                            }
                        } else if (scoped.albums.isEmpty() &&
                            // Local MediaStore permissions cannot explain an empty remote ONE scope.
                            // Only ALL or the canonical device library may display this panel.
                            shouldShowGenreMediaStoreFailure(
                                allScope = activeScope is org.michimusic.core.models.LibraryScope.All,
                                selectedLibraryId = (activeScope as? org.michimusic.core.models.LibraryScope.One)?.libraryId,
                                deviceLibraryId = org.michimusic.data.cache.MichiDatabase.DEFAULT_DEVICE_LIBRARY_ID,
                            ) &&
                            (mediaState == org.michimusic.mobile.screens.MediaLibraryState.PERMISSION_DENIED ||
                                mediaState == org.michimusic.mobile.screens.MediaLibraryState.QUERY_ERROR)
                        ) {
                            Box(Modifier.fillMaxWidth().padding(20.dp)) {
                                org.michimusic.mobile.ui.components.MediaTruthPanel(
                                    state = mediaState,
                                    onPermissionAvailable = { artistScopeRetry++; viewModel.loadMedia(forceRefresh = true) },
                                    onRetry = { artistScopeRetry++; viewModel.loadMedia(forceRefresh = true) },
                                )
                            }
                        } else {
                            // Scope ownership is decisive: a different library cannot
                            // inherit an old genre index while the new index is building.
                            key(activeScope) {
                                // A single cold Room flow prevents transient authorization
                                // with an empty collision list while genre rows are loading.
                                val activeLibrary = activeScope as? org.michimusic.core.models.LibraryScope.One
                                val activeLibraryId = activeLibrary?.libraryId
                                val scopeOwner = activeLibrary
                                    ?.let { selected -> availableLibraries.firstOrNull { it.id == selected.libraryId }?.ownerId }
                                val isAllScope = activeScope is org.michimusic.core.models.LibraryScope.All
                                val expectedScopeKey = genreScopeKey(activeLibraryId, scopeOwner, isAllScope)
                                DisposableEffect(Unit) {
                                    onDispose { genreLibraryViewModel.clear() }
                                }
                                val genreLoad by genreLibraryViewModel.state.collectAsStateWithLifecycle()
                                LaunchedEffect(scoped.albums, activeScope, scopeOwner, artistScopeRetry) {
                                    genreLibraryViewModel.load(
                                        tracks = scoped.albums.flatMap { it.tracks },
                                        scopeOwnerId = scopeOwner,
                                        isAllScope = isAllScope,
                                        libraryId = activeLibraryId,
                                    )
                                }
                                when (val current = genreLoad) {
                                    GenreIndexLoad.Loading -> {
                                        Box(Modifier.fillMaxSize().testTag("genres_indexing"),
                                            contentAlignment = Alignment.Center) {
                                            CircularProgressIndicator(color = PrimaryPink)
                                        }
                                    }
                                    GenreIndexLoad.Failed -> {
                                        Column(Modifier.fillMaxSize().padding(20.dp)) {
                                            Text("No se pudo leer el índice de géneros.", color = TextSecondary)
                                            AeroActionButton("Reintentar géneros", onClick = { artistScopeRetry++ })
                                        }
                                    }
                                    is GenreIndexLoad.Ready -> {
                                        if (current.scopeKey == expectedScopeKey) {
                                            genreViewState.SaveableStateProvider("genre_panel") {
                                                GenreLibraryPanel(
                                                    genres = current.index.genres,
                                                    playableTrackIds = scoped.playableTrackIds,
                                                    globallyBlockedTrackIds = current.index.blockedTrackIds,
                                                    currentTrack = currentTrack,
                                                    isPlaying = isPlaying,
                                                    onPlay = ::playQueueSafe,
                                                )
                                            }
                                        } else {
                                            Box(Modifier.fillMaxSize().testTag("genres_indexing"),
                                                contentAlignment = Alignment.Center) {
                                                CircularProgressIndicator(color = PrimaryPink)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        albumToAddToPlaylist?.let { targetAlbum ->
            val tracksToAdd = remember(targetAlbum.tracks, scopedArtistCollection?.playableTrackIds) {
                canonicalPlayableAlbumTracks(
                    targetAlbum.tracks,
                    scopedArtistCollection?.playableTrackIds.orEmpty(),
                )
            }
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
                                tracksToAdd.size,
                                targetAlbum.album.title,
                                tracksToAdd.size,
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
                                text = "Todavía no tienes playlists. Puedes crear una desde la sección Playlists.",
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
                                    AeroCard(
                                        modifier = Modifier.fillMaxWidth(),
                                        onClick = {
                                            if (!isAddingToPlaylist) {
                                                isAddingToPlaylist = true
                                                addError = null
                                                viewModel.addAlbumToPlaylist(pl.id, tracksToAdd) { success ->
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
                                                text = pluralStringResource(R.plurals.themes_count, pl.trackCount, pl.trackCount),
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
                onHideFromLibrary = if (track.id.startsWith("local_") || track.id.startsWith("safroot_")) ({
                    rootAuthority.hideLocalTrack(track.id, track.filepath,
                        org.michimusic.data.repository.libraryVisibilityFingerprint(track))
                }) else null,
                onHideSuccess = { viewModel.loadMedia(forceRefresh = true) },
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
/** Scope chooser is useful only with multiple libraries or for orphan-scope recovery. */
internal fun shouldShowLibraryScopeSelector(availableCount: Int, allSelected: Boolean): Boolean =
    availableCount > 1 || !allSelected
