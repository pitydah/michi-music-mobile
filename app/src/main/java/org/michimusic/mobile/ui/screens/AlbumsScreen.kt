package org.michimusic.mobile.ui.screens

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.activity.ComponentActivity
import coil3.compose.AsyncImage
import org.koin.androidx.compose.koinViewModel
import org.michimusic.mobile.screens.AlbumsViewModel
import org.michimusic.mobile.ui.components.GlassCard
import org.michimusic.mobile.ui.components.MichiEmptyState
import org.michimusic.mobile.ui.components.MichiLoadingState
import org.michimusic.mobile.ui.components.MichiSectionHeader
import org.michimusic.mobile.ui.components.TrackRow
import org.michimusic.mobile.ui.coverflow.MichiCoverFlowHost
import org.michimusic.mobile.ui.theme.AccentPink
import org.michimusic.mobile.ui.theme.MichiSpacing
import org.michimusic.mobile.ui.theme.SurfaceDark
import org.michimusic.mobile.ui.theme.SurfaceElevated
import org.michimusic.mobile.ui.theme.TextMuted
import org.michimusic.mobile.ui.theme.TextPrimary
import org.michimusic.mobile.ui.theme.TextSecondary
import org.michimusic.mobile.ui.rememberAudioController

@Composable
fun AlbumsScreen() {
    val viewModel: AlbumsViewModel = koinViewModel(
        viewModelStoreOwner = LocalContext.current as ComponentActivity,
    )
    val albums by viewModel.albums.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val controller = rememberAudioController()

    Box(modifier = Modifier.fillMaxSize().background(SurfaceDark)) {
        if (isLoading) {
            MichiLoadingState(text = "Escaneando música local...")
            return
        }

        if (albums.isEmpty()) {
            MichiEmptyState(
                icon = Icons.Default.PlayArrow,
                title = "No se encontraron canciones",
                description = "Asegúrate de tener música en el dispositivo y haber concedido permisos",
            )
            return
        }

        var selectedIndex by remember { mutableIntStateOf(0) }
        if (selectedIndex >= albums.size) selectedIndex = 0
        val selectedAlbum = albums.getOrNull(selectedIndex)

        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = MichiSpacing.lg),
        ) {
            Spacer(Modifier.height(MichiSpacing.lg))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Biblioteca", style = MaterialTheme.typography.headlineMedium, color = TextPrimary)
                Text("${selectedIndex + 1}/${albums.size}", style = MaterialTheme.typography.bodySmall, color = AccentPink)
            }

            Spacer(Modifier.height(MichiSpacing.sm))

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
                modifier = Modifier.fillMaxWidth().height(240.dp),
            )

            Spacer(Modifier.height(MichiSpacing.lg))

            if (selectedAlbum != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AsyncImage(
                        model = if (selectedAlbum.album.coverId.isNotEmpty())
                            "content://media/external/audio/albumart/${selectedAlbum.album.coverId}"
                        else "",
                        contentDescription = null,
                        modifier = Modifier.size(72.dp).clip(RoundedCornerShape(10.dp)),
                        contentScale = ContentScale.Crop,
                    )
                    Spacer(Modifier.width(MichiSpacing.md))
                    Column(Modifier.weight(1f)) {
                        Text(selectedAlbum.album.title, style = MaterialTheme.typography.titleLarge, color = TextPrimary)
                        Text(selectedAlbum.album.artist, style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
                        Text("${selectedAlbum.album.year} · ${selectedAlbum.tracks.size} canciones", style = MaterialTheme.typography.bodySmall, color = TextMuted)
                    }
                    Spacer(Modifier.width(MichiSpacing.sm))
                    IconButton(
                        onClick = { controller.playQueue(selectedAlbum.tracks, 0) }
                    ) {
                        Icon(Icons.Default.PlayArrow, null, tint = AccentPink, modifier = Modifier.size(32.dp))
                    }
                }

                Spacer(Modifier.height(MichiSpacing.md))

                GlassCard(
                    modifier = Modifier.fillMaxWidth().weight(1f),
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
                                    controller.playQueue(queue, startIdx)
                                },
                            )
                        }
                        item { Spacer(Modifier.height(8.dp)) }
                    }
                }
            }
        }
    }
}
