package org.michimusic.mobile.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SortByAlpha
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import org.michimusic.core.models.Track
import org.michimusic.core.models.TrackSource
import org.michimusic.data.repository.FavoritesRepository
import org.michimusic.mobile.artist.AlbumEnrichmentPanel
import org.michimusic.mobile.library.LibraryScopeStore
import org.michimusic.mobile.library.rememberArtistScopedCollection
import org.michimusic.data.repository.MusicLibraryRepository
import org.michimusic.mobile.playback.PlaybackSessionManager
import org.michimusic.mobile.playback.resolvePlaybackPresentation
import org.michimusic.mobile.screens.AlbumsViewModel
import org.michimusic.mobile.screens.MediaLibraryState
import org.michimusic.mobile.ui.components.AeroActionButton
import org.michimusic.mobile.ui.components.AeroActionKind
import org.michimusic.mobile.ui.components.AeroCard
import org.michimusic.mobile.ui.components.AeroPremiumBackdrop
import org.michimusic.mobile.ui.components.AlbumArtView
import org.michimusic.mobile.ui.components.TrackActionBottomSheet
import org.michimusic.mobile.ui.components.AddToPlaylistDialog
import org.michimusic.mobile.ui.components.coverStyleFor
import org.michimusic.mobile.ui.components.formatTimeMillis
import org.michimusic.mobile.ui.components.sampleRateLabel
import org.michimusic.mobile.ui.components.premium.MichiBottomSheet
import org.michimusic.mobile.ui.components.premium.MichiIconAction
import org.michimusic.mobile.ui.components.MediaPermissionWatcher
import org.michimusic.mobile.ui.theme.PrimaryPinkContainer
import org.michimusic.mobile.ui.theme.PureWhite
import org.michimusic.mobile.ui.theme.SurfaceObsidian
import org.michimusic.mobile.ui.theme.TextMuted
import org.michimusic.mobile.ui.theme.TextSecondary
import org.michimusic.player.AudioController

/** Album detail is a navigation destination; the NavHost owns dock and mini-player. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlbumDetailScreen(
    albumId: String,
    onBack: () -> Unit,
    backLabel: String = "Volver a álbumes",
    sessionManager: PlaybackSessionManager = koinInject(),
    modifier: Modifier = Modifier,
) {
    val viewModel: AlbumsViewModel = koinViewModel()
    val albums by viewModel.albums.collectAsStateWithLifecycle()
    val playlists by viewModel.playlists.collectAsStateWithLifecycle()
    val loading by viewModel.isLoading.collectAsStateWithLifecycle()
    val mediaState by viewModel.mediaState.collectAsStateWithLifecycle()
    MediaPermissionWatcher(mediaState) { viewModel.loadMedia(forceRefresh = true) }
    val scopeStore: LibraryScopeStore = koinInject()
    val libraryRepository: MusicLibraryRepository = koinInject()
    val activeScope by scopeStore.activeScope.collectAsStateWithLifecycle()
    val libraries by libraryRepository.observeLibraries().collectAsStateWithLifecycle(initialValue = emptyList())
    var scopeRetry by remember { androidx.compose.runtime.mutableIntStateOf(0) }
    val scoped = rememberArtistScopedCollection(
        localAlbums = albums, scope = activeScope, libraries = libraries,
        repository = libraryRepository, retryKey = scopeRetry,
    )
    val album = if (scoped.ready) resolveNavigationAlbum(scoped.albums, albumId, activeScope) else null
    val tracks = remember(album) { album?.tracks.orEmpty().distinctBy { it.id } }
    val trackPresentation = remember(tracks) { albumTrackPresentation(tracks) }
    val orderedTracks = remember(trackPresentation) {
        trackPresentation.tracks.map(PresentedAlbumTrack::track)
    }
    // Availability belongs to the current library snapshot. Never substitute another track.
    val eligibleTracks = remember(tracks, scoped.playableTrackIds) {
        tracks.filter { it.id in scoped.playableTrackIds }
    }
    val audioController: AudioController = koinInject()
    val session by sessionManager.sessionState.collectAsStateWithLifecycle()
    val engine by audioController.state.collectAsStateWithLifecycle()
    val playing = resolvePlaybackPresentation(session, engine)
    val favorites: FavoritesRepository = koinInject()
    val favoriteIds by favorites.observeFavoriteTrackIds().collectAsStateWithLifecycle(initialValue = emptySet())
    val coroutineScope = rememberCoroutineScope()
    val allFavorite = tracks.isNotEmpty() && tracks.all { it.id in favoriteIds }
    var feedback by remember(albumId, activeScope) { mutableStateOf<String?>(null) }
    var qualityOpen by remember(albumId, activeScope) { mutableStateOf(false) }
    var historyOpen by remember(albumId, activeScope) { mutableStateOf(false) }
    var playlistOpen by remember(albumId, activeScope) { mutableStateOf(false) }
    var menuOpen by remember(albumId, activeScope) { mutableStateOf(false) }
    var sortByTitle by remember(albumId, activeScope) { mutableStateOf(false) }
    val actionLayout = albumActionLayout(
        LocalConfiguration.current.screenWidthDp, LocalConfiguration.current.fontScale,
    )
    val shownTracks = remember(trackPresentation, sortByTitle) {
        if (sortByTitle) trackPresentation.tracks.sortedWith(
            compareBy({ it.track.title.lowercase() }, { it.track.id }),
        ) else trackPresentation.tracks
    }
    // Menu identity, not a captured Track/URI: a rescan may update preferred location.
    var selectedTrackMenuId by remember(albumId, activeScope) { mutableStateOf<String?>(null) }
    var trackToAddToPlaylistId by remember(albumId, activeScope) { mutableStateOf<String?>(null) }
    var savingPlaylist by remember(albumId, activeScope) { mutableStateOf(false) }
    LaunchedEffect(albumId, activeScope, scoped.ready, album, tracks) {
        if (album == null || tracks.none { it.id == selectedTrackMenuId }) selectedTrackMenuId = null
        if (album == null || tracks.none { it.id == trackToAddToPlaylistId }) trackToAddToPlaylistId = null
        if (album == null) {
            qualityOpen = false
            historyOpen = false
            playlistOpen = false
            menuOpen = false
        }
    }

    LaunchedEffect(albumId) { viewModel.loadMedia() }

    fun playSelection(requestedTrackId: String? = null, shuffle: Boolean = false) {
        // Playback follows the same stable disc/track order presented on screen.
        val selection = albumQueueSelection(orderedTracks, scoped.playableTrackIds, requestedTrackId, shuffle)
        if (selection == null) {
            feedback = "Esta selección no tiene pistas disponibles en la biblioteca actual."
            return
        }
        feedback = null
        try {
            sessionManager.playQueue(selection.tracks, selection.startIndex)
        } catch (_: Exception) {
            feedback = "No se pudo iniciar la reproducción. Comprueba el origen de audio."
        }
    }

    fun favoriteAlbum() {
        coroutineScope.launch {
            try {
                // Track favorites are the existing canonical model. No fake album-favorite store.
                tracks.forEach { if (allFavorite) favorites.removeFavorite(it.id) else favorites.addFavorite(it.id) }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                feedback = "No se pudo actualizar Favoritos."
            }
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        AeroPremiumBackdrop(accent = PrimaryPinkContainer)
        Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                MichiIconAction(
                    Icons.AutoMirrored.Filled.ArrowBack, backLabel, onBack,
                    testTag = "album_detail_back",
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    MichiIconAction(
                        if (allFavorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                        if (allFavorite) "Quitar álbum de favoritos" else "Añadir álbum a favoritos",
                        onClick = ::favoriteAlbum, enabled = tracks.isNotEmpty(),
                        selected = allFavorite, testTag = "album_favorite_button",
                    )
                    MichiIconAction(
                        Icons.Filled.MoreVert, "Más opciones del álbum",
                        onClick = { menuOpen = true }, enabled = album != null,
                        testTag = "album_more_button",
                    )
                }
            }
            if (album == null) {
                Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                    if (mediaState == MediaLibraryState.PERMISSION_DENIED || mediaState == MediaLibraryState.QUERY_ERROR) {
                        org.michimusic.mobile.ui.components.MediaTruthPanel(
                            state = mediaState,
                            onPermissionAvailable = { scopeRetry++; viewModel.loadMedia(forceRefresh = true) },
                            onRetry = { scopeRetry++; viewModel.loadMedia(forceRefresh = true) },
                        )
                    } else if (loading || !scoped.ready && scoped.errorMessage == null) {
                        CircularProgressIndicator(color = PrimaryPinkContainer)
                    } else Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Álbum no disponible", color = PureWhite, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            if (scoped.errorMessage != null) scoped.errorMessage.orEmpty()
                            else if (mediaState == MediaLibraryState.PERMISSION_DENIED)
                                "Se necesita acceso a los archivos locales de audio."
                            else "El álbum ya no corresponde a la selección original o hay registros idénticos. Vuelve a Biblioteca y selecciónalo de nuevo.",
                            color = TextSecondary,
                        )
                        TextButton(onClick = {
                            scopeRetry++
                            viewModel.loadMedia(forceRefresh = true)
                        }) { Text("Reintentar") }
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().testTag("album_detail_screen"),
                    contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 28.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    item(key = "hero") {
                        BoxWithConstraints(Modifier.fillMaxWidth()) {
                            val vertical = maxWidth < 300.dp
                            val coverSize = (maxWidth * 0.36f).coerceIn(112.dp, 144.dp)
                            @Composable fun cover() {
                                AlbumArtView(
                                    coverStyle = coverStyleFor(album.album.coverId.ifBlank { album.album.title }),
                                    imageModel = album.album.coverId,
                                    fallbackMediaUri = tracks.firstOrNull()?.filepath,
                                    modifier = Modifier.size(coverSize),
                                    cornerRadius = 16.dp,
                                )
                            }
                            @Composable fun description() {
                                Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                    Text(
                                        album.album.title, fontSize = 22.sp, lineHeight = 26.sp,
                                        fontWeight = FontWeight.Bold, color = PureWhite,
                                        maxLines = 3, overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(album.album.artist, fontSize = 15.sp, color = PureWhite,
                                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    Text(
                                        album.album.year.takeIf { it in 1800..2100 }?.let { "Año local · $it" } ?: "Año local desconocido",
                                        color = TextSecondary, fontSize = 13.sp,
                                    )
                                    Text(
                                        listOfNotNull(albumTrackCountLabel(tracks.size, tracks.all { it.source == TrackSource.LOCAL }), albumCodecSummary(tracks)).joinToString(" · "),
                                        color = TextSecondary, fontSize = 13.sp,
                                        modifier = Modifier.testTag("album_detail_track_count"),
                                    )
                                }
                            }
                            if (vertical) {
                                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                    cover()
                                    description()
                                }
                            } else {
                                Row(horizontalArrangement = Arrangement.spacedBy(16.dp),
                                    verticalAlignment = Alignment.CenterVertically) {
                                    cover()
                                    Box(Modifier.weight(1f)) { description() }
                                }
                            }
                        }
                    }
                    item(key = "actions") {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (actionLayout == AlbumActionLayout.TWO_ROWS) {
                            AeroActionButton(
                                "Reproducir", onClick = { playSelection() },
                                modifier = Modifier.fillMaxWidth(), icon = Icons.Filled.PlayArrow,
                                kind = AeroActionKind.Primary, enabled = eligibleTracks.isNotEmpty(),
                                testTag = "album_detail_play",
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
                            ) {
                                MichiIconAction(
                                    Icons.Filled.Shuffle, "Reproducir álbum aleatoriamente",
                                    onClick = { playSelection(shuffle = true) },
                                    enabled = eligibleTracks.isNotEmpty(), testTag = "album_detail_shuffle",
                                )
                                MichiIconAction(
                                    Icons.AutoMirrored.Filled.PlaylistAdd, "Añadir álbum a una lista",
                                    onClick = { playlistOpen = true }, enabled = tracks.isNotEmpty(),
                                    testTag = "album_detail_add_to_playlist",
                                )
                            }
                        } else {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                AeroActionButton(
                                    "Reproducir", onClick = { playSelection() },
                                    modifier = Modifier.weight(1f), icon = Icons.Filled.PlayArrow,
                                    kind = AeroActionKind.Primary, enabled = eligibleTracks.isNotEmpty(),
                                    testTag = "album_detail_play",
                                )
                                MichiIconAction(
                                    Icons.Filled.Shuffle, "Reproducir álbum aleatoriamente",
                                    onClick = { playSelection(shuffle = true) },
                                    enabled = eligibleTracks.isNotEmpty(), testTag = "album_detail_shuffle",
                                )
                                MichiIconAction(
                                    Icons.AutoMirrored.Filled.PlaylistAdd, "Añadir álbum a una lista",
                                    onClick = { playlistOpen = true }, enabled = tracks.isNotEmpty(),
                                    testTag = "album_detail_add_to_playlist",
                                )
                            }
                        }
                        feedback?.let { Text(it, color = PrimaryPinkContainer, fontSize = 12.sp) }
                        }
                    }
                    item(key = "list_heading") {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text("Pistas (${tracks.size})", color = PureWhite,
                                fontSize = 19.sp, fontWeight = FontWeight.Bold)
                            MichiIconAction(
                                Icons.Filled.SortByAlpha,
                                if (sortByTitle) "Ordenar por número de pista" else "Ordenar alfabéticamente",
                                onClick = { sortByTitle = !sortByTitle }, selected = sortByTitle,
                                testTag = "album_track_sort",
                            )
                        }
                    }
                    itemsIndexed(shownTracks, key = { _, presented -> presented.track.id }) { index, presented ->
                        val track = presented.track
                        val available = track.id in scoped.playableTrackIds
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            val disc = presented.position?.discNumber
                            val previousDisc = shownTracks.getOrNull(index - 1)?.position?.discNumber
                            if (!sortByTitle && trackPresentation.showDiscHeaders && disc != null && disc != previousDisc) {
                                Text(
                                    "Disco $disc",
                                    color = TextSecondary,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.padding(top = 6.dp, start = 4.dp)
                                        .testTag("album_disc_$disc"),
                                )
                            }
                            AeroCard(
                                modifier = Modifier.fillMaxWidth()
                                    .testTag("album_detail_track_${track.id}"),
                                onClick = if (available) ({ playSelection(requestedTrackId = track.id) }) else null,
                            ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Text(
                                    presented.position?.trackNumber?.toString() ?: "—",
                                    fontSize = 13.sp, color = TextMuted, modifier = Modifier.width(36.dp),
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        track.title, color = if (playing.currentTrack?.id == track.id)
                                            PrimaryPinkContainer else PureWhite,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 2, overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        listOfNotNull(track.artist.takeIf { it.isNotBlank() },
                                            formatTimeMillis(track.duration), albumCodecLabel(track),
                                            if (available) null else "No disponible").joinToString(" · "),
                                        color = TextSecondary, fontSize = 12.sp, maxLines = 2,
                                    )
                                }
                                MichiIconAction(
                                    if (track.id in favoriteIds) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                                    if (track.id in favoriteIds) "Quitar ${track.title} de favoritos"
                                    else "Añadir ${track.title} a favoritos",
                                    onClick = {
                                        coroutineScope.launch {
                                            try { favorites.toggleFavorite(track.id) }
                                            catch (cancel: CancellationException) { throw cancel }
                                            catch (_: Exception) { feedback = "No se pudo actualizar Favoritos." }
                                        }
                                    },
                                    selected = track.id in favoriteIds,
                                    testTag = "album_track_favorite_${track.id}",
                                )
                                MichiIconAction(
                                    Icons.Filled.MoreVert, "Opciones de ${track.title}",
                                    onClick = { selectedTrackMenuId = track.id },
                                    testTag = "album_track_more_${track.id}",
                                )
                            }
                            }
                        }
                    }
                    item(key = "end") { Spacer(Modifier.height(16.dp)) }
                }
            }
        }
        selectedTrackMenuId?.let { menuTrackId ->
            // Resolve from live album snapshot each recomposition; no stale URI or tags.
            val track = tracks.singleOrNull { it.id == menuTrackId }
            if (track != null) TrackActionBottomSheet(
                track = track,
                isFavorite = track.id in favoriteIds,
                onDismiss = { selectedTrackMenuId = null },
                onPlayNow = {
                    playSelection(requestedTrackId = track.id)
                    selectedTrackMenuId = null
                },
                onPlayNext = {
                    // Use the current, scoped URI rather than a stale Track saved when the menu opened.
                    val current = eligibleTracks.singleOrNull { it.id == track.id }
                    if (current == null) feedback = "Esta pista no está disponible en la biblioteca seleccionada."
                    else try { sessionManager.playNext(current) }
                    catch (_: Exception) { feedback = "No se pudo añadir la pista como siguiente." }
                    selectedTrackMenuId = null
                },
                onAddToQueue = {
                    val current = eligibleTracks.singleOrNull { it.id == track.id }
                    if (current == null) feedback = "Esta pista no está disponible en la biblioteca seleccionada."
                    else try { sessionManager.addToQueue(current) }
                    catch (_: Exception) { feedback = "No se pudo añadir la pista a la cola." }
                    selectedTrackMenuId = null
                },
                onAddToPlaylist = { selectedTrackMenuId = null; trackToAddToPlaylistId = track.id },
                onToggleFavorite = {
                    coroutineScope.launch {
                        try { favorites.toggleFavorite(track.id) }
                        catch (cancel: CancellationException) { throw cancel }
                        catch (_: Exception) { feedback = "No se pudo actualizar Favoritos." }
                    }
                },
            )
        }
        trackToAddToPlaylistId?.let { playlistTrackId ->
            val track = tracks.singleOrNull { it.id == playlistTrackId }
            if (track != null) AddToPlaylistDialog(
                tracks = listOf(track),
                onDismiss = { trackToAddToPlaylistId = null },
            )
        }
        if (qualityOpen) {
            MichiBottomSheet(onDismissRequest = { qualityOpen = false }) {
                // Lazy rendering avoids composing hundreds of technical rows at once.
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 600.dp)
                        .testTag("album_quality_popup"),
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    item(key = "quality_intro") {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("Calidad de los archivos", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = PureWhite)
                            Text("Datos de ${tracks.size} ${if (tracks.size == 1) "pista" else "pistas"} de la biblioteca.", color = TextSecondary)
                        }
                    }
                    itemsIndexed(tracks, key = { index, track -> "quality_${track.id}_$index" }) { _, track ->
                        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            HorizontalDivider(color = TextMuted.copy(alpha = 0.25f))
                            Text(track.title, color = PureWhite, fontWeight = FontWeight.Medium)
                            Text(
                                listOfNotNull(albumCodecLabel(track)?.let { "Formato de archivo: $it" },
                                    sampleRateLabel(track.sampleRate)?.let { "Frecuencia: $it" },
                                    track.channels.takeIf { it in 1..8 }?.let { "Canales: $it" })
                                    .ifEmpty { listOf("No hay parámetros técnicos suficientes en los metadatos.") }
                                    .joinToString(" · "),
                                color = TextSecondary, fontSize = 13.sp,
                            )
                        }
                    }
                    item(key = "quality_footer") {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("Son metadatos declarados por los archivos; no describen la señal real de salida.",
                                color = TextSecondary, fontSize = 12.sp)
                            TextButton(onClick = { qualityOpen = false }) { Text("Cerrar", color = TextSecondary) }
                        }
                    }
                }
            }
        }
        if (historyOpen && album != null) {
            AlbumEnrichmentPanel(
                localAlbumTitle = album.album.title,
                localArtist = album.album.artist,
                localAlbumId = album.album.id,
                localTrackIds = tracks.map { it.id },
                libraryId = scoped.identityLibraryId,
                onDismiss = { historyOpen = false },
            )
        }
        if (playlistOpen) {
            MichiBottomSheet(onDismissRequest = { if (!savingPlaylist) playlistOpen = false }) {
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp)
                        .testTag("album_playlist_popup"),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text("Añadir álbum a lista", fontSize = 19.sp, fontWeight = FontWeight.Bold, color = PureWhite)
                    if (playlists.isEmpty()) Text("No hay listas disponibles. Puedes crear una desde Biblioteca > Listas.", color = TextSecondary)
                    else playlists.forEach { playlist ->
                        TextButton(
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !savingPlaylist,
                            onClick = {
                                savingPlaylist = true
                                viewModel.addAlbumToPlaylist(playlist.id, orderedTracks) { success ->
                                    savingPlaylist = false
                                    if (success) playlistOpen = false
                                    feedback = if (success) "Álbum añadido a ${playlist.name}" else "No se pudo añadir el álbum."
                                }
                            },
                        ) { Text(playlist.name, color = PureWhite) }
                    }
                    TextButton(onClick = { playlistOpen = false }, enabled = !savingPlaylist) {
                        Text("Cancelar", color = TextSecondary)
                    }
                }
            }
        }
        if (menuOpen) {
            MichiBottomSheet(onDismissRequest = { menuOpen = false }) {
                Column(Modifier.fillMaxWidth().padding(20.dp)) {
                    Text("Opciones del álbum", fontSize = 19.sp, fontWeight = FontWeight.Bold, color = PureWhite)
                    TextButton(onClick = { menuOpen = false; qualityOpen = true }) {
                        Text("Información de calidad", color = PureWhite)
                    }
                    TextButton(onClick = { menuOpen = false; historyOpen = true }) {
                        Text("Cronología externa · MusicBrainz", color = PureWhite)
                    }
                    TextButton(onClick = { menuOpen = false }) { Text("Cerrar", color = TextSecondary) }
                }
            }
        }
    }
}
