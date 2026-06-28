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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.activity.ComponentActivity
import org.koin.androidx.compose.koinViewModel
import org.michimusic.mobile.screens.AlbumsViewModel
import org.michimusic.mobile.ui.components.GlassCard
import org.michimusic.mobile.ui.components.TrackRow
import org.michimusic.mobile.ui.theme.AccentPink
import org.michimusic.mobile.ui.theme.SurfaceDark
import org.michimusic.mobile.ui.theme.TextPrimary
import org.michimusic.mobile.ui.theme.TextSecondary
import org.michimusic.mobile.ui.getAudioController

@Composable
fun PlaylistScreen() {
    val viewModel: AlbumsViewModel = koinViewModel(
        viewModelStoreOwner = LocalContext.current as ComponentActivity,
    )
    val allTracks by viewModel.allTracks.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val playlistName = "Todas las canciones"

    if (isLoading) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(SurfaceDark),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            CircularProgressIndicator(color = AccentPink)
        }
        return
    }

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
                text = playlistName,
                style = MaterialTheme.typography.headlineMedium,
                color = TextPrimary,
            )
            IconButton(onClick = {
                getAudioController()?.playQueue(allTracks, 0)
            }) {
                Icon(
                    imageVector = Icons.Default.PlayArrow,
                    contentDescription = "Play all",
                    tint = AccentPink,
                )
            }
        }

        Text(
            text = "${allTracks.size} canciones",
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary,
        )

        Spacer(Modifier.height(12.dp))

        val audioController = remember { getAudioController() }
        val playerState by (audioController?.state?.collectAsState() ?: remember {
            androidx.compose.runtime.mutableStateOf(org.michimusic.player.PlayerState())
        })
        val activeIndex = playerState.queueIndex

        GlassCard(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                itemsIndexed(allTracks) { index, track ->
                    TrackRow(
                        title = track.title,
                        artist = track.artist,
                        duration = track.duration,
                        isActive = index == activeIndex,
                        onPlay = {
                            getAudioController()?.playQueue(allTracks, index)
                        },
                    )
                }
                item { Spacer(Modifier.height(8.dp)) }
            }
        }
    }
}
