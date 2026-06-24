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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import org.michimusic.mobile.ui.components.TrackRow
import org.michimusic.mobile.ui.coverflow.MichiCoverFlowHost
import org.michimusic.mobile.ui.theme.AccentPink
import org.michimusic.mobile.ui.theme.SurfaceDark
import org.michimusic.mobile.ui.theme.TextPrimary
import org.michimusic.mobile.ui.theme.TextSecondary
import org.michimusic.player.MichiPlaybackService

@Composable
fun AlbumsScreen() {
    val viewModel: AlbumsViewModel = koinViewModel()
    val albums by viewModel.albums.collectAsState()
    val allTracks by viewModel.allTracks.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()

    if (isLoading) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(SurfaceDark),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            CircularProgressIndicator(color = AccentPink)
            Spacer(Modifier.height(12.dp))
            Text("Escaneando música local...", color = TextSecondary)
        }
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
            Text(
                text = "No se encontraron canciones",
                style = MaterialTheme.typography.headlineSmall,
                color = TextPrimary,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Asegúrate de tener música en el dispositivo y haber concedido permisos",
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary,
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

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Albums",
                style = MaterialTheme.typography.headlineMedium,
                color = TextPrimary,
            )
            Text(
                text = "${selectedIndex + 1}/${albums.size}",
                style = MaterialTheme.typography.bodySmall,
                color = AccentPink,
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
                )
            },
            onCurrentChanged = { selectedIndex = it },
            modifier = Modifier
                .fillMaxWidth()
                .height(240.dp),
        )

        Spacer(Modifier.height(16.dp))

        if (selectedAlbum != null) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = selectedAlbum.album.title,
                        style = MaterialTheme.typography.titleLarge,
                        color = TextPrimary,
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
                                MichiPlaybackService.companionController?.let { ctrl ->
                                    val queue = selectedAlbum.tracks
                                    val startIdx = queue.indexOf(track).coerceAtLeast(0)
                                    ctrl.playQueue(queue, startIdx)
                                }
                            },
                        )
                    }
                    item { Spacer(Modifier.height(8.dp)) }
                }
            }
        }
    }
}
