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
import org.michimusic.mobile.playback.resolvePlaybackPresentation
import org.michimusic.mobile.ui.components.premium.MichiIconAction
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import org.michimusic.mobile.R
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
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
import org.michimusic.mobile.screens.StructuredSearchResults
import org.michimusic.mobile.ui.components.AlbumArtView
import org.michimusic.mobile.ui.components.EqualizerWaveBars
import org.michimusic.mobile.ui.components.GlassCard
import org.michimusic.mobile.ui.components.AeroEmptyEditorial
import org.michimusic.mobile.ui.components.AeroPremiumBackdrop
import org.michimusic.mobile.ui.components.AeroSegmentedChoice
import org.michimusic.mobile.ui.components.AeroSegmentTreatment
import org.michimusic.mobile.ui.components.coverStyleFor
import org.michimusic.mobile.ui.components.formatTimeMillis
import org.michimusic.mobile.ui.theme.AeroGraphiteRaised
import org.michimusic.mobile.ui.theme.AeroHairline
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
    sessionManager: org.michimusic.mobile.playback.PlaybackSessionManager = koinInject(),
    viewModel: SearchViewModel = koinViewModel(),
) {
    val query by viewModel.query.collectAsStateWithLifecycle()
    val selectedFilter by viewModel.selectedFilter.collectAsStateWithLifecycle()
    val structuredResults by viewModel.structuredResults.collectAsStateWithLifecycle()
    // Use StateFlow.value inside click handlers. rememberUpdatedState still lags
    // until recomposition and can replay a stale queue between two emissions.
    // The ViewModel stamps each emission with its query, filter and generation.
    // Text matching alone cannot reject an older response when users type A->B->A.
    fun currentResults(): StructuredSearchResults = viewModel.structuredResults.value.let { snapshot ->
        if (searchSnapshotCurrent(
                snapshot.requestQuery, snapshot.requestFilter.name, snapshot.requestGeneration,
                viewModel.query.value, viewModel.selectedFilter.value.name, viewModel.currentSearchGeneration,
            )) snapshot
        else StructuredSearchResults()
    }
    fun actionAllowed(kind: String, label: String): Boolean =
        currentResults().requestGeneration >= 0L && searchActionPermitted(
            viewModel.query.value, viewModel.selectedFilter.value.name, kind, label,
        )
    val isSearching by viewModel.isSearching.collectAsStateWithLifecycle()
    val searchError by viewModel.error.collectAsStateWithLifecycle()
    // A retry must not restart the catalog scan twice: the second request cancels
    // the first one and would briefly clear the loading state it still owns.
    var retryPending by remember { mutableStateOf(false) }
    LaunchedEffect(isSearching, searchError) {
        if (isSearching || searchError == null) retryPending = false
    }
    val keyboardController = LocalSoftwareKeyboardController.current
    val audioController: AudioController = koinInject()
    val sessionState by sessionManager.sessionState.collectAsStateWithLifecycle()
    val playerState by audioController.state.collectAsStateWithLifecycle()
    val presentation = resolvePlaybackPresentation(sessionState, playerState)

    // SearchViewModel debounces input: its previous results can remain visible
    // for 250 ms after query/filter changes. Never render or activate rows that
    // no longer satisfy the LIVE query/filter, even before its next emission.
    // Use the observed snapshot for rendering; the same generation guard governs
    // the click path. Avoid caching against a non-observable generation field.
    val displayResults = structuredResults.takeIf { snapshot ->
        searchSnapshotCurrent(
            snapshot.requestQuery, snapshot.requestFilter.name, snapshot.requestGeneration,
            query, selectedFilter.name, viewModel.currentSearchGeneration,
        )
    } ?: StructuredSearchResults()
    val visibleArtists = displayResults.artists.filter {
        searchActionPermitted(query, selectedFilter.name, "ARTIST", it.artist)
    }
    val visibleAlbums = displayResults.albums.filter {
        searchActionPermitted(query, selectedFilter.name, "ALBUM", it.album)
    }
    val visibleTracks = displayResults.tracks.filter { item ->
        // File system checks belong on the ViewModel IO dispatcher; the result
        // list has already passed DOWNLOADED filtering before it is emitted.
        searchActionPermitted(query, selectedFilter.name, "TRACK", item.track.title) &&
            (selectedFilter != SearchFilter.LOSSLESS || item.track.isLossless)
    }

    fun playQueueSafe(tracks: List<Track>, index: Int) {
        val validIndex = safeSearchQueueIndex(index, tracks.size) ?: return
        sessionManager.playQueue(tracks, validIndex)
    }

    fun playCurrentTrackById(tracks: List<Track>, trackId: String) {
        val index = safeSearchQueueTrackIdIndex(tracks.map { it.id }, trackId) ?: return
        sessionManager.playQueue(tracks, index)
    }

    var selectedArtistForDetail by remember { mutableStateOf<SearchArtistResult?>(null) }
    var selectedAlbumForDetail by remember { mutableStateOf<SearchAlbumResult?>(null) }

    // A hidden stale snapshot must not reopen when a future search happens to match.
    LaunchedEffect(query, selectedFilter, structuredResults, selectedArtistForDetail?.artist,
        selectedAlbumForDetail?.artist, selectedAlbumForDetail?.album) {
        selectedArtistForDetail?.let { snapshot ->
            if (!actionAllowed("ARTIST", snapshot.artist) ||
                currentResults().artists.none { it.artist == snapshot.artist }) selectedArtistForDetail = null
        }
        selectedAlbumForDetail?.let { snapshot ->
            if (!actionAllowed("ALBUM", snapshot.album) ||
                currentResults().albums.none { it.artist == snapshot.artist && it.album == snapshot.album }) {
                selectedAlbumForDetail = null
            }
        }
    }

    LaunchedEffect(viewModel) {
        viewModel.loadLocalTracks()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(SurfaceObsidian)
            .statusBarsPadding()
            .testTag("search_screen"),
    ) {
        AeroPremiumBackdrop(accent = TertiaryCyan)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = MichiSpacing.screenHorizontal),
        ) {
            Spacer(Modifier.height(MichiSpacing.md))

            // Header
            org.michimusic.mobile.ui.components.AeroSectionHeading(
                eyebrow = "CATÁLOGO LOCAL Y SINCRONIZADO",
                title = "Encontrar música",
            )

            Spacer(Modifier.height(MichiSpacing.md))

            // Search Bar
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .clip(MichiShapes.md)
                    .background(Brush.verticalGradient(org.michimusic.mobile.ui.theme.MichiSurfaces.raised))
                    .border(
                        width = 0.65.dp,
                        color = if (query.isNotEmpty()) PrimaryPinkContainer.copy(alpha = 0.62f) else GlassBorderLow,
                        shape = MichiShapes.md,
                    )
                    .padding(horizontal = 16.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = null, // Text field provides the actual accessible label.
                        tint = if (query.isNotEmpty()) PrimaryPinkContainer else TextMuted,
                        modifier = Modifier.size(20.dp),
                    )

                    BasicTextField(
                        value = query,
                        onValueChange = viewModel::setQuery,
                        modifier = Modifier
                            .weight(1f)
                            .semantics { contentDescription = "Buscar en la biblioteca" }
                            .testTag("search_text_field"),
                        textStyle = TextStyle(
                            color = PureWhite,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium,
                        ),
                        cursorBrush = SolidColor(PrimaryPinkContainer),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { keyboardController?.hide() }),
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
                        MichiIconAction(
                            icon = Icons.Default.Clear,
                            contentDescription = "Limpiar Búsqueda",
                            onClick = { viewModel.clearSearch() },
                            tint = TextSecondary,
                            iconSize = 18.dp,
                            minTargetSize = MichiSpacing.minTouchTarget,
                            testTag = "search_clear_button",
                        )
                    }
                }
            }

            Spacer(Modifier.height(MichiSpacing.md))

            // M55: shared selection policy, contrast and responsive targets.
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(end = 24.dp),
                modifier = Modifier.testTag("m60_search_filter_rail"),
            ) {
                items(SearchFilter.entries) { filter ->
                    AeroSegmentedChoice(
                        label = filter.displayName,
                        selected = filter == selectedFilter,
                        onClick = { viewModel.setFilter(filter) },
                        treatment = AeroSegmentTreatment.Filter,
                        testTag = "search_filter_${filter.name.lowercase()}",
                    )
                }
            }

            if (androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp < 390 && SearchFilter.entries.size > 3) {
                Text(
                    text = "Desliza para explorar más filtros",
                    color = TextSecondary,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 5.dp).testTag("search_filter_scroll_hint"),
                )
            }

            Spacer(Modifier.height(MichiSpacing.md))

            // Results State
            if (searchError != null && !isSearching) {
                Box(Modifier.fillMaxSize().padding(horizontal = 4.dp, vertical = 24.dp), contentAlignment = Alignment.TopCenter) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(MichiShapes.lg)
                            .background(Brush.verticalGradient(org.michimusic.mobile.ui.theme.MichiSurfaces.base))
                            .border(0.8.dp, PrimaryPinkContainer.copy(alpha = 0.32f), MichiShapes.lg)
                            .padding(horizontal = 20.dp, vertical = 22.dp)
                            .testTag("search_error_panel"),
                        horizontalAlignment = Alignment.Start,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text("TU BIBLIOTECA", color = PrimaryPinkContainer,
                            fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.2.sp)
                        Text("No se pudo completar la búsqueda", color = PureWhite,
                            style = MichiTypography.cardTitle, fontWeight = FontWeight.SemiBold)
                        Text(searchError.orEmpty(), style = MichiTypography.metadata,
                            color = PureWhite.copy(alpha = 0.84f))
                        Button(
                            onClick = {
                                if (!isSearching && !retryPending) {
                                    retryPending = true
                                    viewModel.loadLocalTracks()
                                }
                            },
                            enabled = !isSearching && !retryPending,
                            modifier = Modifier.heightIn(min = 48.dp).testTag("search_retry_button"),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = PrimaryPinkContainer,
                                contentColor = SurfaceObsidian,
                                disabledContainerColor = PrimaryPinkContainer.copy(alpha = 0.32f),
                                disabledContentColor = SurfaceObsidian.copy(alpha = 0.6f),
                            ),
                            shape = MichiShapes.md,
                        ) {
                            Text("Volver a cargar la biblioteca", fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            } else if (isSearching || (query.length >= 2 && currentResults().requestGeneration < 0L)) {
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
            } else if (query.length < 2) {
                SearchEmptyGuidance()
            } else if (visibleArtists.isEmpty() && visibleAlbums.isEmpty() && visibleTracks.isEmpty()) {
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
                    contentPadding = PaddingValues(bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(MichiSpacing.lg),
                ) {
                    // 1. Artistas
                    if (visibleArtists.isNotEmpty()) {
                        item {
                            Text(
                                text = "Artistas",
                                style = MichiTypography.sectionTitle,
                            )
                        }
                        items(visibleArtists) { artist ->
                            ArtistSearchRow(
                                artist = artist,
                                onClick = {
                                    if (actionAllowed("ARTIST", artist.artist)) selectedArtistForDetail = artist
                                },
                            )
                        }
                    }

                    // 2. Álbumes
                    if (visibleAlbums.isNotEmpty()) {
                        item {
                            Text(
                                text = "Álbumes",
                                style = MichiTypography.sectionTitle,
                            )
                        }
                        items(visibleAlbums) { album ->
                            AlbumSearchRow(
                                album = album,
                                onClick = {
                                    if (actionAllowed("ALBUM", album.album)) selectedAlbumForDetail = album
                                },
                            )
                        }
                    }

                    // 3. Canciones
                    if (visibleTracks.isNotEmpty()) {
                        item {
                            Text(
                                text = "Canciones",
                                style = MichiTypography.sectionTitle,
                            )
                        }
                        items(visibleTracks) { item ->
                            val activeTrack = presentation.currentTrack
                            val isAudioPlaying = presentation.isPlaying
                            val isPlayingThis = activeTrack?.id == item.track.id
                            TrackSearchRow(
                                track = item.track,
                                source = item.source,
                                isPlayingThis = isPlayingThis,
                                isAudioPlaying = isAudioPlaying,
                                onClick = {
                                    // Live query and filter can change during the 250ms debounce.
                                    // Recheck against the current StateFlow before playing.
                                    val currentTracks = currentResults().tracks.map { it.track }
                                    val selected = currentTracks.singleOrNull { it.id == item.track.id }
                                    if (selected != null && actionAllowed("TRACK", selected.title) &&
                                        (viewModel.selectedFilter.value != SearchFilter.LOSSLESS || selected.isLossless)) {
                                        // No synchronous disk I/O on the Compose click thread.
                                        // DOWNLOADED membership was checked in SearchViewModel.
                                        playCurrentTrackById(currentTracks, selected.id)
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }

        // Artist Detail Dialog
        selectedArtistForDetail?.let { snapshot ->
            // Never queue captured artist tracks after a search refresh/removal.
            currentResults().artists.firstOrNull { it.artist == snapshot.artist }?.let { artistResult ->
                ArtistDetailDialog(
                    artist = artistResult,
                    onDismiss = { selectedArtistForDetail = null },
                    onPlayAll = {
                        if (actionAllowed("ARTIST", snapshot.artist)) currentResults().artists.firstOrNull { it.artist == snapshot.artist }
                            ?.tracks?.takeIf { it.isNotEmpty() }?.let { playQueueSafe(it, 0) }
                        selectedArtistForDetail = null
                    },
                    onShuffle = {
                        if (actionAllowed("ARTIST", snapshot.artist)) currentResults().artists.firstOrNull { it.artist == snapshot.artist }
                            ?.tracks?.takeIf { it.isNotEmpty() }?.shuffled()?.let { playQueueSafe(it, 0) }
                        selectedArtistForDetail = null
                    },
                    onTrackClick = { track ->
                        if (!actionAllowed("ARTIST", snapshot.artist)) return@ArtistDetailDialog
                        val currentTracks = currentResults().artists.firstOrNull { it.artist == snapshot.artist }?.tracks.orEmpty()
                        playCurrentTrackById(currentTracks, track.id)
                    },
                )
            }
        }

        // Album Detail Dialog: both artist and album identify the visible collection.
        selectedAlbumForDetail?.let { snapshot ->
            currentResults().albums.firstOrNull { it.artist == snapshot.artist && it.album == snapshot.album }?.let { albumResult ->
                AlbumDetailDialog(
                    album = albumResult,
                    onDismiss = { selectedAlbumForDetail = null },
                    onPlayAll = {
                        if (actionAllowed("ALBUM", snapshot.album)) currentResults().albums.firstOrNull { it.artist == snapshot.artist && it.album == snapshot.album }
                            ?.tracks?.takeIf { it.isNotEmpty() }?.let { playQueueSafe(it, 0) }
                        selectedAlbumForDetail = null
                    },
                    onShuffle = {
                        if (actionAllowed("ALBUM", snapshot.album)) currentResults().albums.firstOrNull { it.artist == snapshot.artist && it.album == snapshot.album }
                            ?.tracks?.takeIf { it.isNotEmpty() }?.shuffled()?.let { playQueueSafe(it, 0) }
                        selectedAlbumForDetail = null
                    },
                    onTrackClick = { track ->
                        if (!actionAllowed("ALBUM", snapshot.album)) return@AlbumDetailDialog
                        val currentTracks = currentResults().albums.firstOrNull { it.artist == snapshot.artist && it.album == snapshot.album }
                            ?.tracks.orEmpty()
                        playCurrentTrackById(currentTracks, track.id)
                    },
                )
            }
        }
    }
}

@Composable
private fun SearchEmptyGuidance() {
    AeroEmptyEditorial(
        eyebrow = "EXPLORACIÓN PERSONAL",
        title = "Toda tu música, a una búsqueda.",
        description = "Encuentra canciones, artistas y álbumes del teléfono y de tu biblioteca sincronizada. Empieza escribiendo arriba.",
        icon = Icons.Default.Search,
        modifier = Modifier.padding(top = 20.dp).testTag("search_empty_editorial"),
    )
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
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = pluralStringResource(R.plurals.songs_in_library, artist.trackCount, artist.trackCount),
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
                imageModel = album.sampleTrack?.coverId,
                fallbackMediaUri = album.sampleTrack?.filepath,
                modifier = Modifier
                    .size(44.dp)
                    .clip(MichiShapes.xs),
            )

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = album.album,
                    style = MichiTypography.cardTitle,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = album.artist + " · " + pluralStringResource(
                        R.plurals.themes_count, album.trackCount, album.trackCount,
                    ),
                    style = MichiTypography.metadata,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
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
                imageModel = track.coverId,
                fallbackMediaUri = track.filepath,
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
                            text = pluralStringResource(R.plurals.themes_in_library, artist.trackCount, artist.trackCount),
                            color = TextSecondary,
                            fontSize = 12.sp,
                        )
                    }
                    MichiIconAction(
                        icon = Icons.Filled.Close,
                        contentDescription = "Cerrar",
                        onClick = onDismiss,
                        tint = TextSecondary,
                        iconSize = 18.dp,
                        minTargetSize = MichiSpacing.minTouchTarget,
                    )
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
                            imageModel = album.sampleTrack?.coverId,
                fallbackMediaUri = album.sampleTrack?.filepath,
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
                                text = album.artist + " · " + pluralStringResource(
                        R.plurals.themes_count, album.trackCount, album.trackCount,
                    ),
                                color = TextSecondary,
                                fontSize = 12.sp,
                            )
                        }
                    }
                    MichiIconAction(
                        icon = Icons.Filled.Close,
                        contentDescription = "Cerrar",
                        onClick = onDismiss,
                        tint = TextSecondary,
                        iconSize = 18.dp,
                        minTargetSize = MichiSpacing.minTouchTarget,
                    )
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
