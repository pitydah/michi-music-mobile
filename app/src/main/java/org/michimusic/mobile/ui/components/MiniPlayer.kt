package org.michimusic.mobile.ui.components

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import org.michimusic.mobile.ui.theme.AccentPink
import org.michimusic.mobile.ui.theme.SurfaceDark
import org.michimusic.mobile.ui.theme.SurfaceElevated
import org.michimusic.mobile.ui.theme.TextDim
import org.michimusic.mobile.ui.theme.TextMuted
import org.michimusic.mobile.ui.theme.TextPrimary
import org.michimusic.mobile.ui.theme.TextSecondary
import org.michimusic.player.MichiPlaybackService

@Composable
fun MiniPlayer(
    modifier: Modifier = Modifier,
    onClick: () -> Unit = {},
) {
    val controller = remember { MichiPlaybackService.companionController }
    val state by controller?.state?.collectAsState() ?: remember {
        androidx.compose.runtime.mutableStateOf(org.michimusic.player.PlayerState())
    }
    val track = state.currentTrack

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .background(SurfaceElevated, RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        if (state.duration > 0 && track != null) {
            LinearProgressIndicator(
                progress = { state.position.toFloat() / state.duration.toFloat() },
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopCenter)
                    .padding(top = 0.dp),
                trackColor = SurfaceDark,
                color = AccentPink,
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (track != null) {
                val coverUri = if (track.coverId.isNotEmpty())
                    "content://media/external/audio/albumart/${track.coverId}" else null
                if (coverUri != null) {
                    AsyncImage(
                        model = coverUri,
                        contentDescription = null,
                        modifier = Modifier
                            .size(40.dp)
                            .clip(RoundedCornerShape(6.dp)),
                        contentScale = ContentScale.Crop,
                    )
                    Spacer(Modifier.width(10.dp))
                } else {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(AccentPink.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Default.MusicNote, null, tint = AccentPink, modifier = Modifier.size(20.dp))
                    }
                    Spacer(Modifier.width(10.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        text = track.title,
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextPrimary,
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
            } else {
                Text(
                    text = "Sin reproducción",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextDim,
                    modifier = Modifier.weight(1f),
                )
            }

            Spacer(Modifier.width(8.dp))

            IconButton(onClick = { controller?.skipPrevious() }) {
                Icon(
                    imageVector = Icons.Default.SkipPrevious,
                    contentDescription = "Previous",
                    tint = TextMuted,
                    modifier = Modifier.size(20.dp),
                )
            }
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(AccentPink, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                IconButton(onClick = {
                    if (state.isPlaying) controller?.pause() else controller?.play()
                }) {
                    Icon(
                        imageVector = if (state.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (state.isPlaying) "Pause" else "Play",
                        tint = SurfaceDark,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
            IconButton(onClick = { controller?.skipNext() }) {
                Icon(
                    imageVector = Icons.Default.SkipNext,
                    contentDescription = "Next",
                    tint = TextMuted,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}
