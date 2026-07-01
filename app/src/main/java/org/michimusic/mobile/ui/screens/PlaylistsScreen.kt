package org.michimusic.mobile.ui.screens

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
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.activity.ComponentActivity
import org.koin.androidx.compose.koinViewModel
import org.michimusic.core.models.Playlist
import org.michimusic.core.models.Track
import org.michimusic.mobile.screens.PlaylistsViewModel
import org.michimusic.mobile.ui.components.GlassCard
import org.michimusic.mobile.ui.components.MichiEmptyState
import org.michimusic.mobile.ui.components.MichiLoadingState
import org.michimusic.mobile.ui.components.TrackRow
import org.michimusic.mobile.ui.theme.AccentPink
import org.michimusic.mobile.ui.theme.MichiSpacing
import org.michimusic.mobile.ui.theme.SurfaceDark
import org.michimusic.mobile.ui.theme.TextMuted
import org.michimusic.mobile.ui.theme.TextPrimary
import org.michimusic.mobile.ui.theme.TextSecondary
import org.michimusic.mobile.ui.rememberAudioController

@Composable
fun PlaylistsScreen() {
    val viewModel: PlaylistsViewModel = koinViewModel(
        viewModelStoreOwner = LocalContext.current as ComponentActivity,
    )
    val playlists by viewModel.playlists.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val controller = rememberAudioController()
    var expandedPlaylistId by remember { mutableStateOf<String?>(null) }

    Box(modifier = Modifier.fillMaxSize().background(SurfaceDark)) {
        if (isLoading) {
            MichiLoadingState(text = "Cargando playlists...")
            return
        }

        if (playlists.isEmpty()) {
            MichiEmptyState(
                icon = Icons.Default.QueueMusic,
                title = "Sin playlists",
                description = "Crea playlists en tu app de música favorita",
            )
            return
        }

        Column(modifier = Modifier.fillMaxSize().padding(horizontal = MichiSpacing.lg)) {
            Spacer(Modifier.height(MichiSpacing.lg))
            Text("Playlists", style = MaterialTheme.typography.headlineMedium, color = TextPrimary)
            Text("${playlists.size} listas", style = MaterialTheme.typography.bodySmall, color = TextMuted)
            Spacer(Modifier.height(MichiSpacing.md))

            LazyColumn(verticalArrangement = Arrangement.spacedBy(MichiSpacing.sm)) {
                items(playlists) { (playlist, tracks) ->
                    val isExpanded = expandedPlaylistId == playlist.id
                    GlassCard(
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column {
                            Row(
                                modifier = Modifier.fillMaxWidth().clickable {
                                    expandedPlaylistId = if (isExpanded) null else playlist.id
                                },
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(44.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(AccentPink.copy(alpha = 0.12f)),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(Icons.Default.QueueMusic, null, tint = AccentPink, modifier = Modifier.size(24.dp))
                                }
                                Spacer(Modifier.width(MichiSpacing.md))
                                Column(Modifier.weight(1f)) {
                                    Text(playlist.name, style = MaterialTheme.typography.titleSmall, color = TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text("${tracks.size} canciones", style = MaterialTheme.typography.bodySmall, color = TextMuted)
                                }
                            }

                            if (isExpanded && tracks.isNotEmpty()) {
                                Spacer(Modifier.height(MichiSpacing.sm))
                                tracks.forEachIndexed { index, track ->
                                    TrackRow(
                                        title = track.title,
                                        artist = track.artist,
                                        duration = track.duration,
                                        onPlay = {
                                            controller.playQueue(tracks, index)
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
                item { Spacer(Modifier.height(MichiSpacing.xxl)) }
            }
        }
    }
}

private fun Modifier.background(color: androidx.compose.ui.graphics.Color): Modifier = this
