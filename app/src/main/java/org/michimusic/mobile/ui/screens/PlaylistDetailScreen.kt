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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import org.michimusic.mobile.screens.PlaylistDetailUiState
import org.michimusic.mobile.screens.PlaylistsViewModel
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

    LaunchedEffect(playlistId) {
        viewModel.loadPlaylistDetail(playlistId)
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
                    }
                    // trackCount > 0: track listing is out of scope for this stage.
                }

                PlaylistDetailUiState.NotFound -> {
                    PlaylistNotFoundState()
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
