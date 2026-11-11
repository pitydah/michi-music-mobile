package org.michimusic.mobile.ui.screens

import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import org.michimusic.mobile.ui.components.premium.MichiIconAction
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.zIndex
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.koin.androidx.compose.koinViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import org.michimusic.core.models.Track
import org.michimusic.data.repository.MoveDirection
import org.michimusic.mobile.screens.PlaylistDetailUiState
import org.michimusic.mobile.screens.PlaylistsViewModel
import org.michimusic.mobile.ui.components.AddTracksDialog
import org.michimusic.mobile.ui.components.AlbumArtView
import org.michimusic.mobile.ui.components.DeletePlaylistDialog
import org.michimusic.mobile.ui.components.GlassCard
import org.michimusic.mobile.ui.components.AeroActionButton
import org.michimusic.mobile.ui.components.AeroActionKind
import org.michimusic.mobile.ui.components.AeroCard
import org.michimusic.mobile.ui.theme.AeroFinish
import org.michimusic.mobile.ui.theme.PrimaryPinkContainer
import org.michimusic.mobile.ui.components.RemoveTrackFromPlaylistDialog
import org.michimusic.mobile.ui.components.RenamePlaylistDialog
import org.michimusic.mobile.ui.components.aeroMenuTopLight
import org.michimusic.mobile.ui.components.coverStyleFor
import org.michimusic.mobile.ui.theme.GlassBorderLow
import org.michimusic.mobile.ui.theme.GlassFillLow
import org.michimusic.mobile.ui.theme.LocalVisualPreferences
import org.michimusic.mobile.ui.theme.PureWhite
import org.michimusic.mobile.ui.theme.SecondaryPurple
import org.michimusic.mobile.ui.theme.SurfaceObsidian
import org.michimusic.mobile.ui.theme.TertiaryCyan
import org.michimusic.mobile.ui.theme.TextMuted
import org.michimusic.mobile.ui.theme.TextSecondary
import org.michimusic.mobile.ui.theme.MichiSpacing
import org.michimusic.mobile.ui.theme.MichiTypography
import org.michimusic.mobile.ui.theme.AeroGraphite
import org.michimusic.player.AudioController

@Composable
fun PlaylistDetailScreen(
    playlistId: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: PlaylistsViewModel = koinViewModel(),
    audioController: AudioController = koinInject(),
    sessionManager: org.michimusic.mobile.playback.PlaybackSessionManager? = null,
) {
    val uiState by viewModel.selectedPlaylistState.collectAsStateWithLifecycle()
    val availableTracks by viewModel.availableTracks.collectAsStateWithLifecycle()
    val isLoadingAvailableTracks by viewModel.isLoadingAvailableTracks.collectAsStateWithLifecycle()
    val isAddingTracks by viewModel.isAddingTracks.collectAsStateWithLifecycle()
    val addTracksError by viewModel.addTracksError.collectAsStateWithLifecycle()
    val isRemovingTrack by viewModel.isRemovingTrack.collectAsStateWithLifecycle()
    val isDeletingPlaylist by viewModel.isDeletingPlaylist.collectAsStateWithLifecycle()
    val isRenamingPlaylist by viewModel.isRenamingPlaylist.collectAsStateWithLifecycle()
    val isReorderingTrack by viewModel.isReorderingTrack.collectAsStateWithLifecycle()

    var showAddTracksDialog by remember { mutableStateOf(false) }
    var trackPendingRemoval by remember { mutableStateOf<Track?>(null) }
    var showDeletePlaylistDialog by remember { mutableStateOf(false) }
    var showRenamePlaylistDialog by remember { mutableStateOf(false) }
    var showOptionsMenu by remember { mutableStateOf(false) }
    var showVisualEditor by remember { mutableStateOf(false) }
    var isSavingVisual by remember { mutableStateOf(false) }
    var visualEditError by remember { mutableStateOf<String?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }
    val appContext = LocalContext.current.applicationContext
    val visualStore = remember(appContext) { PlaylistVisualStore(appContext) }
    val visualScope = rememberCoroutineScope()
    var visualState by remember(playlistId) { mutableStateOf(PlaylistVisualState()) }
    var trackDensity by remember { mutableStateOf(PlaylistTrackDensity.COMPACT) }
    var coverRequestId by remember { mutableStateOf<String?>(null) }
    var bannerRequestId by remember { mutableStateOf<String?>(null) }
    // Observability, not the last tap: the row marker follows the real player/session.
    val activePlayback = if (sessionManager != null) {
        val session by sessionManager.sessionState.collectAsStateWithLifecycle()
        val local by audioController.state.collectAsStateWithLifecycle()
        org.michimusic.mobile.playback.resolvePlaybackPresentation(session, local)
    } else null
    val activeTrackId = activePlayback?.currentTrack?.id
    val coverPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val requestId = coverRequestId
        coverRequestId = null
        if (uri != null && requestId == playlistId) visualScope.launch {
            when (val result = visualStore.importPickedImage(playlistId, "cover", uri)) {
                is PlaylistImageImportResult.Success -> {
                    visualState = visualStore.read(playlistId)
                    snackbarHostState.showSnackbar("Portada actualizada")
                }
                is PlaylistImageImportResult.Failure -> snackbarHostState.showSnackbar(
                    "${playlistImageImportErrorMessage(result.reason)} La portada anterior se mantiene.",
                )
            }
        }
    }
    val bannerPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val requestId = bannerRequestId
        bannerRequestId = null
        if (uri != null && requestId == playlistId) visualScope.launch {
            when (val result = visualStore.importPickedImage(playlistId, "banner", uri)) {
                is PlaylistImageImportResult.Success -> {
                    visualState = visualStore.read(playlistId)
                    snackbarHostState.showSnackbar("Banner actualizado")
                }
                is PlaylistImageImportResult.Failure -> snackbarHostState.showSnackbar(
                    "${playlistImageImportErrorMessage(result.reason)} El banner anterior se mantiene.",
                )
            }
        }
    }
    LaunchedEffect(playlistId) { visualState = visualStore.read(playlistId) }
    LaunchedEffect(Unit) {
        trackDensity = visualStore.readDensity()
        visualStore.pruneOrphanPrivateImages()
    }

    LaunchedEffect(playlistId) {
        viewModel.loadPlaylistDetail(playlistId)
    }

    LaunchedEffect(addTracksError) {
        addTracksError?.let { message ->
            snackbarHostState.showSnackbar(message)
            viewModel.clearAddTracksError()
        }
    }

    val existingTrackIds = remember(uiState) {
        (uiState as? PlaylistDetailUiState.Found)?.playlist?.trackIds?.toSet() ?: emptySet()
    }
    val pickableTracks = remember(availableTracks, existingTrackIds) {
        availableTracks.filterNot { it.id in existingTrackIds }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(SurfaceObsidian)
            .testTag("playlist_detail_screen"),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(horizontal = MichiSpacing.screenHorizontal),
        ) {
            Spacer(Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MichiIconAction(
                    icon = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Volver",
                    onClick = onBack,
                    tint = PureWhite,
                    iconSize = 24.dp,
                    minTargetSize = MichiSpacing.minTouchTarget,
                    testTag = "playlist_detail_back_button",
                )

                Spacer(Modifier.width(10.dp))

                Column(modifier = Modifier.weight(1f)) {
                    // The found playlist's PLAYLIST capsule, title and count live
                    // in the continuous hero below; keep fallback labels and
                    // semantics for loading/not-found/error.
                    if (uiState !is PlaylistDetailUiState.Found) {
                        Text("PLAYLIST", style = MichiTypography.screenEyebrow)
                        Text(
                            text = when (uiState) {
                                PlaylistDetailUiState.NotFound -> "Playlist no encontrada"
                                is PlaylistDetailUiState.Error -> "Error"
                                else -> "Cargando..."
                            },
                            style = MichiTypography.sectionTitle,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.testTag("playlist_detail_title"),
                        )
                    }
                }

                if (uiState !is PlaylistDetailUiState.NotFound) {
                    Box {
                        MichiIconAction(
                            icon = Icons.Filled.MoreVert,
                            contentDescription = "Más opciones",
                            onClick = { showOptionsMenu = true },
                            tint = PureWhite,
                            iconSize = 22.dp,
                            minTargetSize = MichiSpacing.minTouchTarget,
                            testTag = "playlist_detail_options_button",
                        )

                        DropdownMenu(
                            expanded = showOptionsMenu,
                            onDismissRequest = { showOptionsMenu = false },
                            modifier = Modifier
                                .aeroMenuTopLight(LocalVisualPreferences.current.highContrast)
                                .testTag("playlist_detail_options_menu"),
                        ) {
                            DropdownMenuItem(
                                text = { Text("Renombrar") },
                                leadingIcon = {
                                    Icon(Icons.Filled.Edit, contentDescription = null, tint = TextMuted)
                                },
                                onClick = {
                                    showOptionsMenu = false
                                    showRenamePlaylistDialog = true
                                },
                                modifier = Modifier.testTag("playlist_detail_rename_menu_item"),
                            )
                            DropdownMenuItem(
                                text = { Text("Editar apariencia de playlist") },
                                leadingIcon = {
                                    Icon(Icons.Filled.Tune, contentDescription = null, tint = TextMuted)
                                },
                                onClick = {
                                    showOptionsMenu = false
                                    visualEditError = null
                                    showVisualEditor = true
                                },
                                modifier = Modifier.testTag("playlist_detail_edit_visual"),
                            )
                            DropdownMenuItem(
                                text = { Text("Cambiar portada") },
                                onClick = {
                                    showOptionsMenu = false
                                    coverRequestId = playlistId
                                    coverPicker.launch(arrayOf("image/*"))
                                },
                                modifier = Modifier.testTag("playlist_detail_edit_cover"),
                            )
                            DropdownMenuItem(
                                text = { Text("Cambiar banner") },
                                onClick = {
                                    showOptionsMenu = false
                                    bannerRequestId = playlistId
                                    bannerPicker.launch(arrayOf("image/*"))
                                },
                                modifier = Modifier.testTag("playlist_detail_edit_banner"),
                            )
                            DropdownMenuItem(
                                text = { Text("Quitar portada personalizada") },
                                enabled = visualState.coverUri != null,
                                onClick = {
                                    showOptionsMenu = false
                                    visualScope.launch {
                                        val removed = visualStore.saveImage(playlistId, "cover", null)
                                        visualState = visualStore.read(playlistId)
                                        snackbarHostState.showSnackbar(
                                            if (removed) "Portada personalizada quitada"
                                            else "No se pudo quitar la portada personalizada.",
                                        )
                                    }
                                },
                                modifier = Modifier.testTag("playlist_detail_remove_cover"),
                            )
                            DropdownMenuItem(
                                text = { Text("Quitar banner personalizado") },
                                enabled = visualState.bannerUri != null,
                                onClick = {
                                    showOptionsMenu = false
                                    visualScope.launch {
                                        val removed = visualStore.saveImage(playlistId, "banner", null)
                                        visualState = visualStore.read(playlistId)
                                        snackbarHostState.showSnackbar(
                                            if (removed) "Banner personalizado quitado"
                                            else "No se pudo quitar el banner personalizado.",
                                        )
                                    }
                                },
                                modifier = Modifier.testTag("playlist_detail_remove_banner"),
                            )
                            DropdownMenuItem(
                                text = { Text(if (visualState.pinned) "Quitar fijación" else "Fijar playlist") },
                                onClick = {
                                    showOptionsMenu = false
                                    visualScope.launch {
                                        visualStore.setPinned(playlistId, !visualState.pinned)
                                        visualState = visualStore.read(playlistId)
                                    }
                                },
                                modifier = Modifier.testTag("playlist_detail_pin"),
                            )
                            DropdownMenuItem(
                                text = { Text("Ajustar encuadre y escala") },
                                enabled = visualState.bannerUri != null,
                                onClick = {
                                    showOptionsMenu = false
                                    visualEditError = null
                                    showVisualEditor = true
                                },
                                modifier = Modifier.testTag("playlist_detail_banner_focal"),
                            )
                            DropdownMenuItem(
                                text = { Text("Restablecer portada y banner") },
                                onClick = {
                                    showOptionsMenu = false
                                    visualScope.launch {
                                        val result = visualStore.applyVisualEdit(
                                            playlistId,
                                            PlaylistVisualEditRequest(
                                                cover = PlaylistVisualImageChange.Remove,
                                                banner = PlaylistVisualImageChange.Remove,
                                            ),
                                        )
                                        visualState = visualStore.read(playlistId)
                                        snackbarHostState.showSnackbar(
                                            if (result is PlaylistVisualEditResult.Success) {
                                                "Portada y banner restablecidos"
                                            } else {
                                                "No se pudo restablecer la apariencia."
                                            },
                                        )
                                    }
                                },
                                modifier = Modifier.testTag("playlist_detail_clear_images"),
                            )
                            DropdownMenuItem(
                                text = { Text("Eliminar playlist") },
                                leadingIcon = {
                                    Icon(Icons.Filled.Delete, contentDescription = null, tint = TextMuted)
                                },
                                onClick = {
                                    showOptionsMenu = false
                                    showDeletePlaylistDialog = true
                                },
                                modifier = Modifier.testTag("playlist_detail_delete_menu_item"),
                            )
                        }
                    }

                    Spacer(Modifier.width(10.dp))

                    MichiIconAction(
                        icon = Icons.Filled.Add,
                        contentDescription = "Añadir canciones",
                        onClick = {
                            viewModel.loadAvailableTracks()
                            showAddTracksDialog = true
                        },
                        tint = PureWhite,
                        iconSize = 24.dp,
                        minTargetSize = MichiSpacing.minTouchTarget,
                        testTag = "playlist_detail_add_tracks_button",
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            when (val state = uiState) {
                PlaylistDetailUiState.Loading -> {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .testTag("playlist_detail_loading"),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(color = org.michimusic.mobile.ui.theme.PrimaryPinkContainer)
                    }
                }

                is PlaylistDetailUiState.Found -> {
                    PlaylistVisualHero(
                        id = playlistId,
                        title = state.playlist.name,
                        trackCount = state.playlist.tracks.size,
                        coverIds = state.playlist.coverIds,
                        visual = visualState,
                        modifier = Modifier.testTag("playlist_detail_visual_identity"),
                    )
                    if (state.playlist.tracks.isNotEmpty()) {
                        val playableQueue = state.playlist.tracks
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            AeroActionButton(
                                label = "Reproducir",
                                icon = Icons.Filled.PlayArrow,
                                kind = AeroActionKind.Primary,
                                modifier = Modifier.weight(1f).testTag("playlist_detail_play_all"),
                                onClick = {
                                    if (sessionManager != null) sessionManager.playQueue(playableQueue, 0)
                                    else audioController.playQueue(playableQueue, 0)
                                },
                            )
                            AeroActionButton(
                                label = "Mezclar",
                                icon = Icons.Filled.Shuffle,
                                kind = AeroActionKind.Quiet,
                                modifier = Modifier.weight(0.55f)
                                    .testTag("playlist_detail_shuffle_all"),
                                onClick = {
                                    val shuffled = playableQueue.shuffled()
                                    if (sessionManager != null) sessionManager.playQueue(shuffled, 0)
                                    else audioController.playQueue(shuffled, 0)
                                },
                            )
                        }
                    }
                    // Gate on resolvable tracks, not the persisted trackCount: a playlist can
                    // have trackCount > 0 while every stored id has become unresolvable, in
                    // which case there is nothing to render and the empty state is correct.
                    if (state.playlist.tracks.isEmpty()) {
                        // The empty state sits close to the hero, hides density and play
                        // affordances, and keeps one obvious primary action. The top "+"
                        // remains a complementary shortcut.
                        EmptyPlaylistState(
                            onAddTracks = {
                                viewModel.loadAvailableTracks()
                                showAddTracksDialog = true
                            },
                        )
                    } else {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text("Canciones", color = PureWhite, style = MichiTypography.metadata)
                            TextButton(
                                onClick = {
                                    val next = if (trackDensity == PlaylistTrackDensity.COMPACT)
                                        PlaylistTrackDensity.EXPANDED else PlaylistTrackDensity.COMPACT
                                    trackDensity = next
                                    visualScope.launch { visualStore.setDensity(next) }
                                },
                                modifier = Modifier.heightIn(min = 48.dp).testTag("playlist_detail_density_toggle"),
                            ) { Text(if (trackDensity == PlaylistTrackDensity.COMPACT) "Vista compacta" else "Vista ampliada") }
                        }
                        PlaylistTracksList(
                            tracks = state.playlist.tracks,
                            density = trackDensity,
                            activeTrackId = activeTrackId,
                            activeTrackPlaying = activePlayback?.isPlaying == true,
                            removeEnabled = !isRemovingTrack,
                            moveEnabled = !isReorderingTrack,
                            onRemoveClick = { track -> trackPendingRemoval = track },
                            onTrackClick = { track ->
                                val tracks = state.playlist.tracks
                                val index = tracks.indexOfFirst { it.id == track.id }
                                if (index >= 0) {
                                    if (sessionManager != null) {
                                        sessionManager.playQueue(tracks, index)
                                    } else {
                                        audioController.playQueue(tracks, index)
                                    }
                                }
                            },
                            onMoveUp = { track -> viewModel.moveTrack(playlistId, track.id, MoveDirection.UP) },
                            onReorder = { trackId, from, to, visibleIds ->
                                viewModel.moveTrackToPosition(playlistId, trackId, from, to, visibleIds)
                            },
                            onMoveDown = { track -> viewModel.moveTrack(playlistId, track.id, MoveDirection.DOWN) },
                        )
                    }
                }

                PlaylistDetailUiState.NotFound -> {
                    PlaylistNotFoundState()
                }

                is PlaylistDetailUiState.Error -> {
                    PlaylistErrorState(
                        message = state.message,
                        onRetry = { viewModel.loadPlaylistDetail(playlistId) },
                    )
                }
            }
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter),
        )

        if (showVisualEditor) {
            PlaylistVisualEditorDialog(
                visual = visualState,
                isSaving = isSavingVisual,
                errorMessage = visualEditError,
                onDismiss = {
                    if (!isSavingVisual) {
                        showVisualEditor = false
                        visualEditError = null
                    }
                },
                onConfirm = { request ->
                    if (!isSavingVisual) {
                        isSavingVisual = true
                        visualEditError = null
                        visualScope.launch {
                            when (val result = visualStore.applyVisualEdit(playlistId, request)) {
                                PlaylistVisualEditResult.Success -> {
                                    visualState = visualStore.read(playlistId)
                                    showVisualEditor = false
                                    isSavingVisual = false
                                    snackbarHostState.showSnackbar("Apariencia actualizada")
                                }
                                is PlaylistVisualEditResult.Rejected -> {
                                    val fieldLabel = if (result.field == "banner") "Banner" else "Portada"
                                    visualEditError = "$fieldLabel: ${playlistImageImportErrorMessage(result.reason)}"
                                    isSavingVisual = false
                                    snackbarHostState.showSnackbar(
                                        "$fieldLabel sin cambios. ${playlistImageImportErrorMessage(result.reason)}",
                                    )
                                }
                            }
                        }
                    }
                },
            )
        }

        if (showAddTracksDialog) {
            AddTracksDialog(
                availableTracks = pickableTracks,
                isLoadingTracks = isLoadingAvailableTracks,
                isSaving = isAddingTracks,
                onConfirm = { selectedIds ->
                    viewModel.addTracksToPlaylist(playlistId, selectedIds) { success ->
                        if (success) showAddTracksDialog = false
                    }
                },
                onDismiss = { if (!isAddingTracks) showAddTracksDialog = false },
            )
        }

        trackPendingRemoval?.let { track ->
            RemoveTrackFromPlaylistDialog(
                trackTitle = track.title,
                onConfirm = {
                    viewModel.removeTrackFromPlaylist(playlistId, track.id)
                    trackPendingRemoval = null
                },
                onDismiss = { trackPendingRemoval = null },
            )
        }

        if (showDeletePlaylistDialog) {
            DeletePlaylistDialog(
                isDeleting = isDeletingPlaylist,
                onConfirm = {
                    viewModel.deletePlaylist(playlistId) { success ->
                        if (success) {
                            showDeletePlaylistDialog = false
                            visualScope.launch {
                                visualStore.clear(playlistId)
                                onBack()
                            }
                        }
                    }
                },
                onDismiss = { showDeletePlaylistDialog = false },
            )
        }

        if (showRenamePlaylistDialog) {
            val currentName = (uiState as? PlaylistDetailUiState.Found)?.playlist?.name.orEmpty()
            RenamePlaylistDialog(
                currentName = currentName,
                isSaving = isRenamingPlaylist,
                onConfirm = { newName ->
                    viewModel.renamePlaylist(playlistId, newName) { success ->
                        if (success) showRenamePlaylistDialog = false
                    }
                },
                onDismiss = { showRenamePlaylistDialog = false },
            )
        }
    }
}

@Composable
private fun PlaylistTracksList(
    tracks: List<Track>,
    density: PlaylistTrackDensity,
    activeTrackId: String?,
    activeTrackPlaying: Boolean,
    removeEnabled: Boolean,
    moveEnabled: Boolean,
    onRemoveClick: (Track) -> Unit,
    onTrackClick: (Track) -> Unit,
    onMoveUp: (Track) -> Unit,
    onMoveDown: (Track) -> Unit,
    onReorder: (String, Int, Int, List<String>) -> Unit,
) {
    val listState = rememberLazyListState()
    val visibleIds = remember(tracks) { tracks.map { it.id } }
    val uniqueVisibleIds = remember(visibleIds) { visibleIds.size == visibleIds.toSet().size }
    var activeId by remember(visibleIds) { mutableStateOf<String?>(null) }
    var fromIndex by remember(visibleIds) { mutableIntStateOf(-1) }
    var targetIndex by remember(visibleIds) { mutableIntStateOf(-1) }
    var verticalShift by remember(visibleIds) { mutableFloatStateOf(0f) }
    var dragEdge by remember(visibleIds) { mutableIntStateOf(0) }
    val thresholdPx = with(LocalDensity.current) { 52.dp.toPx() }
    val autoscrollStepPx = with(LocalDensity.current) { 8.dp.toPx() }

    fun resetDrag() {
        activeId = null
        fromIndex = -1
        targetIndex = -1
        verticalShift = 0f
        dragEdge = 0
    }

    // One loop for the whole drag. It starts only while the finger is in an
    // edge zone and stops on release/cancel/dispose, so no stray job survives.
    LaunchedEffect(activeId, dragEdge, listState) {
        if (activeId == null || dragEdge == 0) return@LaunchedEffect
        while (isActive) {
            listState.scrollBy(dragEdge * autoscrollStepPx)
            delay(16)
        }
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize().testTag("playlist_detail_track_list"),
        verticalArrangement = Arrangement.spacedBy(playlistTrackLayout(density).gapDp.dp),
    ) {
        itemsIndexed(tracks, key = { _, track -> track.id }) { index, track ->
            val draggingThis = activeId == track.id
            val dragModifier = if (moveEnabled && tracks.size > 1 && uniqueVisibleIds) {
                Modifier.pointerInput(track.id, visibleIds) {
                    detectDragGesturesAfterLongPress(
                        onDragStart = {
                            if (activeId == null) {
                                activeId = track.id
                                fromIndex = index
                                targetIndex = index
                                verticalShift = 0f
                            }
                        },
                        onDrag = { change, amount ->
                            if (activeId != track.id) return@detectDragGesturesAfterLongPress
                            change.consume()
                            verticalShift += amount.y
                            val layout = listState.layoutInfo
                            val origin = layout.visibleItemsInfo.firstOrNull { it.key == track.id }
                            if (origin != null) {
                                val center = origin.offset + origin.size / 2f + verticalShift
                                val destination = layout.visibleItemsInfo.minByOrNull {
                                    kotlin.math.abs(it.offset + it.size / 2f - center)
                                }
                                if (destination != null && destination.index in tracks.indices) {
                                    targetIndex = destination.index
                                }
                                val direction = when {
                                    center < layout.viewportStartOffset + thresholdPx -> -1
                                    center > layout.viewportEndOffset - thresholdPx -> 1
                                    else -> 0
                                }
                                dragEdge = direction
                            }
                        },
                        onDragEnd = {
                            val origin = fromIndex
                            val destination = targetIndex
                            val id = activeId
                            resetDrag()
                            if (id == track.id && origin in tracks.indices &&
                                destination in tracks.indices && origin != destination
                            ) onReorder(id, origin, destination, visibleIds)
                        },
                        onDragCancel = { resetDrag() },
                    )
                }
            } else Modifier
            PlaylistTrackRow(
                track,
                density = density,
                isObservedActive = track.id == activeTrackId,
                isObservedPlaying = track.id == activeTrackId && activeTrackPlaying,
                removeEnabled = removeEnabled,
                moveEnabled = moveEnabled,
                canMoveUp = index > 0,
                canMoveDown = index < tracks.lastIndex,
                onRemoveClick = { onRemoveClick(track) },
                onClick = { if (activeId == null) onTrackClick(track) },
                onMoveUp = { onMoveUp(track) },
                onMoveDown = { onMoveDown(track) },
                dragModifier = dragModifier
                    .graphicsLayer {
                        translationY = if (draggingThis) verticalShift else 0f
                        scaleX = if (draggingThis) 1.018f else 1f
                        scaleY = if (draggingThis) 1.018f else 1f
                        alpha = if (draggingThis) 0.97f else 1f
                        shadowElevation = if (draggingThis) 12.dp.toPx() else 0f
                        shape = RoundedCornerShape(16.dp)
                    }
                    .zIndex(if (draggingThis) 2f else 0f),
                dropTarget = activeId != null && targetIndex == index && !draggingThis,
            )
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun PlaylistTrackRow(
    track: Track,
    density: PlaylistTrackDensity,
    isObservedActive: Boolean,
    isObservedPlaying: Boolean,
    removeEnabled: Boolean,
    moveEnabled: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onRemoveClick: () -> Unit,
    onClick: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    dragModifier: Modifier = Modifier,
    dropTarget: Boolean = false,
) {
    var showTrackMenu by remember { mutableStateOf(false) }
    val rowLayout = playlistTrackLayout(density)

    AeroCard(
        modifier = Modifier
            .fillMaxWidth()
            .then(dragModifier)
            .then(if (dropTarget) Modifier.border(1.5.dp, PrimaryPinkContainer, RoundedCornerShape(12.dp))
                else Modifier)
            .testTag("playlist_detail_track_${track.id}"),
        finish = AeroFinish.Plain,
        onClick = onClick,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = rowLayout.verticalPaddingDp.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            AlbumArtView(
                coverStyle = coverStyleFor(track.coverId.ifEmpty { track.title }),
                imageModel = track.coverId,
                fallbackMediaUri = track.filepath,
                modifier = Modifier
                    .size(rowLayout.artworkDp.dp)
                    .clip(RoundedCornerShape(8.dp)),
            )

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = track.title,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = PureWhite,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = track.artist,
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (density == PlaylistTrackDensity.EXPANDED &&
                    (track.album.isNotBlank() || track.format.isNotBlank())) {
                    Text(
                        listOfNotNull(track.album.takeIf { it.isNotBlank() },
                            track.format.takeIf { it.isNotBlank() }).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            if (isObservedActive) {
                Icon(
                    Icons.Filled.GraphicEq,
                    contentDescription = if (isObservedPlaying) {
                        "Sonando ahora: ${track.title}"
                    } else {
                        "Pista actual en pausa: ${track.title}"
                    },
                    tint = TertiaryCyan,
                    modifier = Modifier.size(16.dp)
                        .testTag("playlist_detail_track_active_${track.id}"),
                )
            }

            // Secondary reorder action, tucked behind a small overflow menu so it doesn't
            // compete visually with tap-to-play or the existing remove action.
            Box {
                MichiIconAction(
                    icon = Icons.Filled.MoreVert,
                    contentDescription = "Más opciones para ${track.title}",
                    onClick = { showTrackMenu = true },
                    tint = TextMuted,
                    iconSize = 18.dp,
                    minTargetSize = MichiSpacing.minTouchTarget,
                    testTag = "playlist_detail_track_options_${track.id}",
                )

                DropdownMenu(
                    expanded = showTrackMenu,
                    onDismissRequest = { showTrackMenu = false },
                    modifier = Modifier
                        .aeroMenuTopLight(LocalVisualPreferences.current.highContrast)
                        .testTag("playlist_detail_track_menu_${track.id}"),
                ) {
                    DropdownMenuItem(
                        text = { Text("Mover arriba") },
                        leadingIcon = {
                            Icon(Icons.Filled.KeyboardArrowUp, contentDescription = null, tint = TextMuted)
                        },
                        enabled = canMoveUp && moveEnabled,
                        onClick = {
                            showTrackMenu = false
                            onMoveUp()
                        },
                        modifier = Modifier.testTag("playlist_detail_move_up_${track.id}"),
                    )
                    DropdownMenuItem(
                        text = { Text("Mover abajo") },
                        leadingIcon = {
                            Icon(Icons.Filled.KeyboardArrowDown, contentDescription = null, tint = TextMuted)
                        },
                        enabled = canMoveDown && moveEnabled,
                        onClick = {
                            showTrackMenu = false
                            onMoveDown()
                        },
                        modifier = Modifier.testTag("playlist_detail_move_down_${track.id}"),
                    )
                    // UI5: one 48dp overflow target instead of two trailing targets.
                    // Removing still invokes the original confirmation dialog callback.
                    DropdownMenuItem(
                        text = { Text("Quitar de la playlist") },
                        leadingIcon = {
                            Icon(Icons.Filled.Delete, contentDescription = null, tint = TextMuted)
                        },
                        enabled = removeEnabled,
                        onClick = {
                            showTrackMenu = false
                            onRemoveClick()
                        },
                        modifier = Modifier.testTag("playlist_detail_remove_track_${track.id}"),
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyPlaylistState(onAddTracks: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 18.dp)
            .testTag("playlist_detail_empty_state"),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            Icons.AutoMirrored.Filled.QueueMusic,
            contentDescription = null,
            tint = TextMuted,
            modifier = Modifier.size(56.dp),
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = "Esta playlist todavía no tiene canciones",
            style = MaterialTheme.typography.titleMedium,
            color = PureWhite,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = "Añade canciones de tu biblioteca para empezar.",
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary,
        )
        Spacer(Modifier.height(16.dp))
        AeroActionButton(
            label = "Añadir canciones",
            onClick = onAddTracks,
            icon = Icons.Filled.Add,
            kind = AeroActionKind.Primary,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("playlist_detail_empty_add_tracks"),
        )
    }
}

@Composable
private fun PlaylistNotFoundState() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .testTag("playlist_detail_not_found_state"),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                Icons.Filled.SearchOff,
                contentDescription = null,
                tint = TextMuted,
                modifier = Modifier.size(56.dp),
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = "No pudimos encontrar esta playlist",
                style = MaterialTheme.typography.titleMedium,
                color = PureWhite,
            )
            Text(
                text = "Puede que haya sido eliminada",
                style = MaterialTheme.typography.bodySmall,
                color = TextMuted,
            )
        }
    }
}

@Composable
private fun PlaylistErrorState(
    message: String,
    onRetry: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .testTag("playlist_detail_error_state"),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                Icons.Filled.SearchOff,
                contentDescription = null,
                tint = TextMuted,
                modifier = Modifier.size(56.dp),
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = "Error al cargar la playlist",
                style = MaterialTheme.typography.titleMedium,
                color = PureWhite,
            )
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = TextMuted,
            )
            Spacer(Modifier.height(16.dp))
            androidx.compose.material3.Button(
                onClick = onRetry,
                modifier = Modifier.testTag("playlist_detail_retry_button"),
            ) {
                Text("Reintentar")
            }
        }
    }
}
