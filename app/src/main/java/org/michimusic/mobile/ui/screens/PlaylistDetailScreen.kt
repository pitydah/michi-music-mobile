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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material3.CircularProgressIndicator
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
import org.michimusic.core.models.Track
import org.michimusic.mobile.screens.PlaylistDetailUiState
import org.michimusic.mobile.screens.PlaylistsViewModel
import org.michimusic.mobile.ui.components.AddTracksDialog
import org.michimusic.mobile.ui.components.AlbumArtView
import org.michimusic.mobile.ui.components.GlassCard
import org.michimusic.mobile.ui.components.RemoveTrackFromPlaylistDialog
import org.michimusic.mobile.ui.components.coverStyleFor
import org.michimusic.mobile.ui.theme.GlassBorderLow
import org.michimusic.mobile.ui.theme.GlassFillLow
import org.michimusic.mobile.ui.theme.PureWhite
import org.michimusic.mobile.ui.theme.SecondaryPurple
import org.michimusic.mobile.ui.theme.SurfaceObsidian
import org.michimusic.mobile.ui.theme.TertiaryCyan
import org.michimusic.mobile.ui.theme.TextMuted
import org.michimusic.mobile.ui.theme.TextSecondary

@Composable
fun PlaylistDetailScreen(
    playlistId: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: PlaylistsViewModel = koinViewModel(),
) {
    val uiState by viewModel.selectedPlaylistState.collectAsState()
    val availableTracks by viewModel.availableTracks.collectAsState()
    val isLoadingAvailableTracks by viewModel.isLoadingAvailableTracks.collectAsState()
    val isAddingTracks by viewModel.isAddingTracks.collectAsState()
    val addTracksError by viewModel.addTracksError.collectAsState()
    val isRemovingTrack by viewModel.isRemovingTrack.collectAsState()

    var showAddTracksDialog by remember { mutableStateOf(false) }
    var trackPendingRemoval by remember { mutableStateOf<Track?>(null) }
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
        Box(
            modifier = Modifier
                .fillMaxSize()
                .drawBehind {
                    drawCircle(
                        color = SecondaryPurple.copy(alpha = 0.08f),
                        radius = size.width * 0.7f,
                        center = androidx.compose.ui.geometry.Offset(size.width * 0.9f, size.height * 0.1f),
                    )
                    drawCircle(
                        color = TertiaryCyan.copy(alpha = 0.06f),
                        radius = size.width * 0.6f,
                        center = androidx.compose.ui.geometry.Offset(size.width * 0.1f, size.height * 0.85f),
                    )
                },
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(horizontal = 20.dp),
        ) {
            Spacer(Modifier.height(24.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = onBack,
                    modifier = Modifier
                        .size(42.dp)
                        .clip(CircleShape)
                        .background(GlassFillLow)
                        .testTag("playlist_detail_back_button"),
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Volver",
                        tint = PureWhite,
                        modifier = Modifier.size(22.dp),
                    )
                }

                Spacer(Modifier.width(14.dp))

                Column {
                    Text(
                        text = "PLAYLIST",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = SecondaryPurple,
                        letterSpacing = 2.sp,
                    )
                    Text(
                        text = when (val state = uiState) {
                            is PlaylistDetailUiState.Found -> state.playlist.name
                            PlaylistDetailUiState.NotFound -> "Playlist no encontrada"
                            PlaylistDetailUiState.Loading -> "Cargando..."
                        },
                        style = MaterialTheme.typography.headlineSmall.copy(
                            fontWeight = FontWeight.Bold,
                            color = PureWhite,
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.testTag("playlist_detail_title"),
                    )
                    if (uiState is PlaylistDetailUiState.Found) {
                        val count = (uiState as PlaylistDetailUiState.Found).playlist.trackCount
                        Text(
                            text = if (count == 1) "1 canción" else "$count canciones",
                            fontSize = 13.sp,
                            color = TextSecondary,
                            modifier = Modifier.testTag("playlist_detail_track_count"),
                        )
                    }
                }

                Spacer(Modifier.weight(1f))

                if (uiState !is PlaylistDetailUiState.NotFound) {
                    IconButton(
                        onClick = {
                            viewModel.loadAvailableTracks()
                            showAddTracksDialog = true
                        },
                        modifier = Modifier
                            .size(42.dp)
                            .clip(CircleShape)
                            .background(GlassFillLow)
                            .testTag("playlist_detail_add_tracks_button"),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Add,
                            contentDescription = "Añadir canciones",
                            tint = PureWhite,
                            modifier = Modifier.size(22.dp),
                        )
                    }
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
                        CircularProgressIndicator(color = SecondaryPurple)
                    }
                }

                is PlaylistDetailUiState.Found -> {
                    if (state.playlist.trackCount == 0) {
                        EmptyPlaylistState()
                    } else {
                        PlaylistTracksList(
                            tracks = state.playlist.tracks,
                            removeEnabled = !isRemovingTrack,
                            onRemoveClick = { track -> trackPendingRemoval = track },
                        )
                    }
                }

                PlaylistDetailUiState.NotFound -> {
                    PlaylistNotFoundState()
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
    }
}

@Composable
private fun PlaylistTracksList(
    tracks: List<Track>,
    removeEnabled: Boolean,
    onRemoveClick: (Track) -> Unit,
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .testTag("playlist_detail_track_list"),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(tracks, key = { it.id }) { track ->
            PlaylistTrackRow(track, removeEnabled = removeEnabled, onRemoveClick = { onRemoveClick(track) })
        }
        item { Spacer(Modifier.height(100.dp)) }
    }
}

@Composable
private fun PlaylistTrackRow(track: Track, removeEnabled: Boolean, onRemoveClick: () -> Unit) {
    GlassCard(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("playlist_detail_track_${track.id}"),
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
                coverStyle = coverStyleFor(track.coverId.ifEmpty { track.title }),
                imageModel = track.filepath.ifEmpty { track.coverId },
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

            IconButton(
                onClick = onRemoveClick,
                enabled = removeEnabled,
                modifier = Modifier
                    .size(36.dp)
                    .testTag("playlist_detail_remove_track_${track.id}"),
            ) {
                Icon(
                    imageVector = Icons.Filled.Delete,
                    contentDescription = "Quitar de la playlist",
                    tint = TextMuted,
                    modifier = Modifier.size(20.dp),
                )
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
