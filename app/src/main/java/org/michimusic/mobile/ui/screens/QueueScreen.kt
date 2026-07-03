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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.koin.compose.koinInject
import org.michimusic.mobile.ui.components.GlassCard
import org.michimusic.mobile.ui.components.PremiumButton
import org.michimusic.mobile.ui.components.PremiumEmptyState
import org.michimusic.mobile.ui.components.PremiumIconButton
import org.michimusic.mobile.ui.components.PremiumScreen
import org.michimusic.mobile.ui.components.PremiumStatPill
import org.michimusic.mobile.ui.components.PremiumTrackItem
import org.michimusic.mobile.ui.components.ScreenHeader
import org.michimusic.mobile.ui.theme.AccentCoral
import org.michimusic.mobile.ui.theme.AccentPink
import org.michimusic.mobile.ui.theme.SurfaceElevated
import org.michimusic.mobile.ui.theme.TextDim
import org.michimusic.mobile.ui.theme.TextMuted
import org.michimusic.mobile.ui.theme.TextPrimary
import org.michimusic.player.AudioController

@Composable
fun QueueScreen() {
    val controller: AudioController = koinInject()
    val state by controller.state.collectAsState()
    val queue = state.queue
    val currentIndex = state.queueIndex

    PremiumScreen {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
        ) {
            Spacer(Modifier.height(16.dp))

            ScreenHeader(
                title = "Cola",
                subtitle = if (queue.isEmpty()) {
                    "Reproduce desde cualquier pantalla"
                } else {
                    "${queue.size} canciones preparadas"
                },
            ) {
                if (queue.isNotEmpty()) {
                    PremiumIconButton(
                        icon = Icons.Default.Clear,
                        contentDescription = "Limpiar cola",
                        onClick = { controller.clearQueue() },
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            QueueStatusCard(
                count = queue.size,
                currentTitle = state.currentTrack?.title,
                isPlaying = state.isPlaying,
                onClear = { controller.clearQueue() },
            )

            Spacer(Modifier.height(16.dp))

            if (queue.isEmpty()) {
                PremiumEmptyState(
                    icon = Icons.AutoMirrored.Filled.QueueMusic,
                    title = "No hay canciones en la cola",
                    subtitle = "Elige un álbum, búsqueda o canción reciente",
                )
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    itemsIndexed(queue) { index, track ->
                        val isCurrent = index == currentIndex
                        PremiumTrackItem(
                            title = track.title,
                            subtitle = track.artist.ifEmpty { track.album },
                            coverId = track.coverId,
                            isActive = isCurrent,
                            trailing = {
                                Spacer(Modifier.width(8.dp))
                                QueueLeadingIcon(isCurrent = isCurrent, number = index + 1)
                            },
                            onClick = { controller.playQueue(queue, index) },
                        )
                    }
                    item { Spacer(Modifier.height(8.dp)) }
                }
            }
        }
    }
}

@Composable
private fun QueueStatusCard(
    count: Int,
    currentTitle: String?,
    isPlaying: Boolean,
    onClear: () -> Unit,
) {
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                AccentCoral.copy(alpha = 0.28f),
                                AccentPink.copy(alpha = 0.18f),
                            )
                        )
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = if (isPlaying) Icons.Default.PlayArrow else Icons.AutoMirrored.Filled.QueueMusic,
                    contentDescription = null,
                    tint = AccentCoral,
                    modifier = Modifier.size(22.dp),
                )
            }
            Column(Modifier.weight(1f)) {
                Text(
                    text = if (count == 0) "Cola vacía" else "$count canciones",
                    style = MaterialTheme.typography.titleMedium,
                    color = TextPrimary,
                )
                Text(
                    text = currentTitle ?: "Sin reproducción activa",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (count > 0) {
                PremiumButton(
                    text = "Limpiar",
                    icon = Icons.Default.Clear,
                    onClick = onClear,
                )
            } else {
                PremiumStatPill("Lista")
            }
        }
    }
}

@Composable
private fun QueueLeadingIcon(isCurrent: Boolean, number: Int) {
    Box(
        modifier = Modifier
            .size(30.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (isCurrent) AccentCoral.copy(alpha = 0.18f) else SurfaceElevated.copy(alpha = 0.7f)),
        contentAlignment = Alignment.Center,
    ) {
        if (isCurrent) {
            Icon(
                Icons.Default.PlayArrow,
                contentDescription = "Reproduciendo",
                tint = AccentCoral,
                modifier = Modifier.size(18.dp),
            )
        } else {
            Text(
                text = "$number",
                style = MaterialTheme.typography.labelSmall,
                color = TextDim,
            )
        }
    }
}
