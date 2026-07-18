package org.michimusic.mobile.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.paging.LoadState
import androidx.paging.compose.collectAsLazyPagingItems
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import org.michimusic.data.cache.CachedTrack
import org.michimusic.mobile.screens.SyncedTracksViewModel
import org.michimusic.mobile.ui.components.PremiumEmptyState
import org.michimusic.mobile.ui.components.PremiumScreen
import org.michimusic.mobile.ui.theme.AccentPink
import org.michimusic.mobile.ui.theme.SurfaceBorder
import org.michimusic.mobile.ui.theme.TextDim
import org.michimusic.mobile.ui.theme.TextMuted
import org.michimusic.mobile.ui.theme.TextPrimary
import org.michimusic.mobile.ui.theme.TextSecondary
import org.michimusic.player.AudioController

@Composable
fun SyncedTracksScreen(
    viewModel: SyncedTracksViewModel = koinViewModel(),
) {
    val pagedTracks = viewModel.pagedTracks.collectAsLazyPagingItems()
    val scope = rememberCoroutineScope()
    val audioController: AudioController = koinInject()

    PremiumScreen {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
        ) {
            Spacer(Modifier.height(16.dp))

            Text(
                text = "Sincronizadas",
                style = MaterialTheme.typography.headlineMedium,
                color = TextPrimary,
            )
            Text(
                text = if (pagedTracks.itemCount > 0) {
                    "${pagedTracks.itemCount} canciones disponibles offline"
                } else {
                    "Tu música descargada aparecerá aquí"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary,
            )

            Spacer(Modifier.height(16.dp))

            when (val refreshState = pagedTracks.loadState.refresh) {
                is LoadState.Loading -> LoadingSyncedState()
                is LoadState.Error -> ErrorSyncedState(refreshState.error.message ?: "Error desconocido")
                is LoadState.NotLoading -> {
                    if (pagedTracks.itemCount == 0) {
                        EmptySyncedState()
                    } else {
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(pagedTracks.itemCount, key = { index -> pagedTracks[index]?.id ?: index.toString() }) { index ->
                                val track = pagedTracks[index]
                                if (track != null) {
                                    SyncedTrackRow(
                                        track = track,
                                        onPlay = {
                                            scope.launch {
                                                val playable = viewModel.getPlayableTracks()
                                                val idx = playable.indexOfFirst { it.id == track.id }
                                                if (idx >= 0) {
                                                    audioController.playQueue(playable, idx)
                                                }
                                            }
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LoadingSyncedState() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator(color = AccentPink)
    }
}

@Composable
private fun ErrorSyncedState(message: String) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Error al cargar", style = MaterialTheme.typography.bodyLarge, color = TextDim)
            Spacer(Modifier.height(8.dp))
            Text(message, style = MaterialTheme.typography.bodySmall, color = TextMuted)
        }
    }
}

@Composable
private fun EmptySyncedState() {
    PremiumEmptyState(
        icon = Icons.Rounded.MusicNote,
        title = "No hay canciones sincronizadas",
        subtitle = "Conecta y sincroniza desde la pantalla Sync",
    )
}

@Composable
private fun SyncedTrackRow(
    track: CachedTrack,
    onPlay: () -> Unit,
) {
    val isDownloaded = track.filepath.isNotEmpty()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Color.White.copy(alpha = 0.055f))
            .border(0.5.dp, SurfaceBorder.copy(alpha = 1.2f), RoundedCornerShape(8.dp))
            .clickable(enabled = isDownloaded) { onPlay() }
            .padding(horizontal = 10.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(AccentPink.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center,
            ) {
                if (isDownloaded) {
                    Icon(
                        Icons.Rounded.DownloadDone,
                        contentDescription = "Descargado",
                        tint = AccentPink,
                        modifier = Modifier.size(20.dp),
                    )
                } else {
                    Icon(
                        Icons.Rounded.MusicNote,
                        contentDescription = null,
                        tint = TextDim,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = track.title,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (isDownloaded) TextPrimary else TextDim,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "${track.artist} · ${track.album}",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (isDownloaded) {
                IconButton(onClick = onPlay) {
                    Icon(
                        Icons.Rounded.PlayArrow,
                        contentDescription = "Reproducir",
                        tint = AccentPink,
                        modifier = Modifier.size(20.dp),
                    )
                }
            } else {
                Text(
                    text = "Pendiente",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextDim,
                )
            }
    }
}
