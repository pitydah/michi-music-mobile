package org.michimusic.mobile.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.koin.androidx.compose.koinViewModel
import org.michimusic.mobile.screens.AlbumsViewModel
import org.michimusic.mobile.ui.components.GlassCard
import org.michimusic.mobile.ui.components.PremiumButton
import org.michimusic.mobile.ui.components.PremiumEmptyState
import org.michimusic.mobile.ui.components.PremiumLoadingState
import org.michimusic.mobile.ui.components.ScreenHeader
import org.michimusic.mobile.ui.components.TrackArtwork
import org.michimusic.mobile.ui.components.TrackRow
import org.michimusic.mobile.ui.coverflow.MichiCoverFlowHost
import org.michimusic.mobile.ui.theme.AccentCoral
import org.michimusic.mobile.ui.theme.SurfaceDark
import org.michimusic.mobile.ui.theme.TextPrimary
import org.michimusic.mobile.ui.theme.TextSecondary
import org.koin.compose.koinInject
import org.michimusic.player.AudioController

@Composable
fun AlbumsScreen() {
    val viewModel: AlbumsViewModel = koinViewModel()
    val albums by viewModel.albums.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val audioController: AudioController = koinInject()

    LaunchedEffect(Unit) { viewModel.loadMedia() }

    if (isLoading) {
        PremiumLoadingState(
            text = "Escaneando música local...",
            modifier = Modifier
                .fillMaxSize()
                .background(SurfaceDark),
        )
        return
    }

    if (albums.isEmpty()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(SurfaceDark)
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            PremiumEmptyState(
                icon = Icons.Default.Album,
                title = "No se encontraron canciones",
                subtitle = "Revisa permisos o sincroniza desde Michi KDE",
            )
        }
        return
    }

    var selectedIndex by remember { mutableIntStateOf(0) }
    val selectedAlbum = albums.getOrNull(selectedIndex)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(SurfaceDark)
            .padding(horizontal = 16.dp),
    ) {
        Spacer(Modifier.height(16.dp))

        ScreenHeader(
            title = "Albums",
            subtitle = "${albums.size} colecciones en tu biblioteca",
        ) {
            Text(
                text = "${selectedIndex + 1}/${albums.size}",
                style = MaterialTheme.typography.bodySmall,
                color = AccentCoral,
            )
        }

        Spacer(Modifier.height(8.dp))

        MichiCoverFlowHost(
            albums = albums.map { local ->
                org.michimusic.mobile.library.coverflow.CoverFlowAlbum(
                    id = local.album.id,
                    title = local.album.title,
                    artist = local.album.artist,
                    year = local.album.year,
                    trackCount = local.tracks.size,
                    hasArt = local.album.coverId.isNotEmpty(),
                    coverUri = if (local.album.coverId.isNotEmpty())
                        "content://media/external/audio/albumart/${local.album.coverId}"
                    else "",
                )
            },
            onCurrentChanged = { selectedIndex = it },
            modifier = Modifier
                .fillMaxWidth()
                .height(240.dp),
        )

        Spacer(Modifier.height(16.dp))

        if (selectedAlbum != null) {
            GlassCard(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TrackArtwork(coverId = selectedAlbum.album.coverId, size = 72.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = selectedAlbum.album.title,
                            style = MaterialTheme.typography.titleLarge,
                            color = TextPrimary,
                            maxLines = 1,
                        )
                        Text(
                            text = selectedAlbum.album.artist,
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextSecondary,
                        )
                        Text(
                            text = "${selectedAlbum.album.year} · ${selectedAlbum.tracks.size} canciones",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary,
                        )
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            PremiumButton(
                text = "Reproducir álbum",
                icon = Icons.Default.PlayArrow,
                onClick = { audioController.playQueue(selectedAlbum.tracks, 0) },
                enabled = selectedAlbum.tracks.isNotEmpty(),
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(12.dp))

            GlassCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            ) {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(selectedAlbum.tracks) { track ->
                        TrackRow(
                            title = track.title,
                            artist = track.artist,
                            duration = track.duration,
                            onPlay = {
                                val queue = selectedAlbum.tracks
                                val startIdx = queue.indexOf(track).coerceAtLeast(0)
                                audioController.playQueue(queue, startIdx)
                            },
                        )
                    }
                    item { Spacer(Modifier.height(8.dp)) }
                }
            }
        }
    }
}
