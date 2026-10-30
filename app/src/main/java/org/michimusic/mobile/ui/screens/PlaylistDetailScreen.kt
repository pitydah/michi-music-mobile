package org.michimusic.mobile.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import org.michimusic.core.models.Track
import org.michimusic.data.repository.MoveDirection
import org.michimusic.mobile.screens.PlaylistDetailUiState
import org.michimusic.mobile.screens.PlaylistsViewModel
import org.michimusic.mobile.ui.components.AddTracksDialog
import org.michimusic.mobile.ui.components.AlbumArtView
import org.michimusic.mobile.ui.components.DeletePlaylistDialog
import org.michimusic.mobile.ui.components.GlassCard
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
    val uiState by viewModel.selectedPlaylistState.collectAsState()
    val availableTracks by viewModel.availableTracks.collectAsState()
    val isLoadingAvailableTracks by viewModel.isLoadingAvailableTracks.collectAsState()
    val isAddingTracks by viewModel.isAddingTracks.collectAsState()
    val addTracksError by viewModel.addTracksError.collectAsState()
    val isRemovingTrack by viewModel.isRemovingTrack.collectAsState()
    val isDeletingPlaylist by viewModel.isDeletingPlaylist.collectAsState()
    val isRenamingPlaylist by viewModel.isRenamingPlaylist.collectAsState()
    val isReorderingTrack by viewModel.isReorderingTrack.collectAsState()

    var showAddTracksDialog by remember { mutableStateOf(false) }
    var trackPendingRemoval by remember { mutableStateOf<Track?>(null) }
    var showDeletePlaylistDialog by remember { mutableStateOf(false) }
    var showRenamePlaylistDialog by remember { mutableStateOf(false) }
    var showOptionsMenu by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }

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
                    Text(
                        text = "PLAYLIST",
                        style = MichiTypography.screenEyebrow,
                    )
                    Text(
                        text = when (val state = uiState) {
                            is PlaylistDetailUiState.Found -> state.playlist.name
                            PlaylistDetailUiState.NotFound -> "Playlist no encontrada"
                            is PlaylistDetailUiState.Error -> "Error"
                            PlaylistDetailUiState.Loading -> "Cargando..."
                        },
                        style = MichiTypography.sectionTitle,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.testTag("playlist_detail_title"),
                    )
                    if (uiState is PlaylistDetailUiState.Found) {
                        // Count of tracks actually resolvable/renderable (state.playlist.tracks),
                        // not the persisted trackCount - a stored trackId can outlive the file it
                        // points to (moved/deleted device track, unreachable remote fallback), so
                        // the header must match what PlaylistTracksList below actually renders.
                        val count = (uiState as PlaylistDetailUiState.Found).playlist.tracks.size
                        Text(
                            text = if (count == 1) "1 canción" else "$count canciones",
                            fontSize = 13.sp,
                            color = TextSecondary,
                            modifier = Modifier.testTag("playlist_detail_track_count"),
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

            Spacer(Modifier.height(32.dp))

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
                    // Gate on resolvable tracks, not the persisted trackCount: a playlist can
                    // have trackCount > 0 while every stored id has become unresolvable, in
                    // which case there is nothing to render and the empty state is correct.
                    if (state.playlist.tracks.isEmpty()) {
                        EmptyPlaylistState()
                    } else {
                        PlaylistTracksList(
                            tracks = state.playlist.tracks,
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
                            onBack()
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
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        itemsIndexed(tracks, key = { _, track -> track.id }) { index, track ->
            PlaylistTrackRow(
                track,
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

    GlassCard(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("playlist_detail_track_${track.id}"),
        backgroundColor = GlassFillLow,
        borderColor = GlassBorderLow,
        onClick = onClick,
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
                }
            }

            MichiIconAction(
                icon = Icons.Filled.Delete,
                contentDescription = "Quitar de la playlist",
                onClick = onRemoveClick,
                enabled = removeEnabled,
                tint = TextMuted,
                iconSize = 20.dp,
                minTargetSize = MichiSpacing.minTouchTarget,
                testTag = "playlist_detail_remove_track_${track.id}",
            )
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
