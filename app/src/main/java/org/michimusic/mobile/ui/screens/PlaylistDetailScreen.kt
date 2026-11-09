package org.michimusic.mobile.ui.screens

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
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.SearchOff
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.koin.androidx.compose.koinViewModel
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
import org.michimusic.mobile.ui.components.AeroCard
import org.michimusic.mobile.ui.theme.AeroFinish
import org.michimusic.mobile.ui.components.RemoveTrackFromPlaylistDialog
import org.michimusic.mobile.ui.components.RenamePlaylistDialog
import org.michimusic.mobile.ui.components.coverStyleFor
import org.michimusic.mobile.ui.theme.GlassBorderLow
import org.michimusic.mobile.ui.theme.GlassFillLow
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
    val snackbarHostState = remember { SnackbarHostState() }
    val appContext = LocalContext.current.applicationContext
    val visualStore = remember(appContext) { PlaylistVisualStore(appContext) }
    val visualScope = rememberCoroutineScope()
    var visualState by remember(playlistId) { mutableStateOf(PlaylistVisualState()) }
    var trackDensity by remember { mutableStateOf(PlaylistTrackDensity.COMPACT) }
    var coverRequestId by remember { mutableStateOf<String?>(null) }
    var bannerRequestId by remember { mutableStateOf<String?>(null) }
    val coverPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val requestId = coverRequestId
        coverRequestId = null
        if (uri != null && requestId == playlistId) visualScope.launch {
            val accepted = visualStore.savePickedImage(playlistId, "cover", uri)
            if (!accepted) snackbarHostState.showSnackbar("No se pudo conservar el acceso a la imagen.")
            else {
                visualState = visualStore.read(playlistId)
                snackbarHostState.showSnackbar("Portada actualizada")
            }
        }
    }
    val bannerPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val requestId = bannerRequestId
        bannerRequestId = null
        if (uri != null && requestId == playlistId) visualScope.launch {
            val accepted = visualStore.savePickedImage(playlistId, "banner", uri)
            if (!accepted) snackbarHostState.showSnackbar("No se pudo conservar el acceso al banner.")
            else {
                visualState = visualStore.read(playlistId)
                snackbarHostState.showSnackbar("Banner actualizado")
            }
        }
    }
    LaunchedEffect(playlistId) { visualState = visualStore.read(playlistId) }
    LaunchedEffect(Unit) { trackDensity = visualStore.readDensity() }

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
                    Text("PLAYLIST", style = MichiTypography.screenEyebrow)
                    // The found playlist title/count live in the continuous hero below;
                    // keep fallback labels and semantics for loading/not-found/error.
                    if (uiState !is PlaylistDetailUiState.Found) {
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
                            modifier = Modifier.testTag("playlist_detail_options_menu"),
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
                                        visualStore.saveImage(playlistId, "cover", null)
                                        visualState = visualStore.read(playlistId)
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
                                        visualStore.saveImage(playlistId, "banner", null)
                                        visualState = visualStore.read(playlistId)
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
                                text = { Text("Ajustar encuadre del banner") },
                                enabled = visualState.bannerUri != null,
                                onClick = {
                                    showOptionsMenu = false
                                    visualScope.launch {
                                        visualStore.setBannerFocal(playlistId, nextPlaylistBannerFocal(visualState.bannerFocalY))
                                        visualState = visualStore.read(playlistId)
                                    }
                                },
                                modifier = Modifier.testTag("playlist_detail_banner_focal"),
                            )
                            DropdownMenuItem(
                                text = { Text("Restablecer portada y banner") },
                                onClick = {
                                    showOptionsMenu = false
                                    visualScope.launch {
                                        visualStore.saveImage(playlistId, "cover", null)
                                        visualStore.saveImage(playlistId, "banner", null)
                                        visualState = visualStore.read(playlistId)
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
                    // Gate on resolvable tracks, not the persisted trackCount: a playlist can
                    // have trackCount > 0 while every stored id has become unresolvable, in
                    // which case there is nothing to render and the empty state is correct.
                    if (state.playlist.tracks.isEmpty()) {
                        EmptyPlaylistState()
                    } else {
                        PlaylistTracksList(
                            tracks = state.playlist.tracks,
                            density = trackDensity,
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
    removeEnabled: Boolean,
    moveEnabled: Boolean,
    onRemoveClick: (Track) -> Unit,
    onTrackClick: (Track) -> Unit,
    onMoveUp: (Track) -> Unit,
    onMoveDown: (Track) -> Unit,
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .testTag("playlist_detail_track_list"),
        verticalArrangement = Arrangement.spacedBy(playlistTrackLayout(density).gapDp.dp),
    ) {
        itemsIndexed(tracks, key = { _, track -> track.id }) { index, track ->
            PlaylistTrackRow(
                track,
                density = density,
                removeEnabled = removeEnabled,
                moveEnabled = moveEnabled,
                canMoveUp = index > 0,
                canMoveDown = index < tracks.lastIndex,
                onRemoveClick = { onRemoveClick(track) },
                onClick = { onTrackClick(track) },
                onMoveUp = { onMoveUp(track) },
                onMoveDown = { onMoveDown(track) },
            )
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun PlaylistTrackRow(
    track: Track,
    density: PlaylistTrackDensity,
    removeEnabled: Boolean,
    moveEnabled: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onRemoveClick: () -> Unit,
    onClick: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
) {
    var showTrackMenu by remember { mutableStateOf(false) }
    val rowLayout = playlistTrackLayout(density)

    AeroCard(
        modifier = Modifier
            .fillMaxWidth()
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
                    modifier = Modifier.testTag("playlist_detail_track_menu_${track.id}"),
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
private fun EmptyPlaylistState() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .testTag("playlist_detail_empty_state"),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
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
        }
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
