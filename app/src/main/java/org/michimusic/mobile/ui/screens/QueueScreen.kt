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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ClearAll
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.michimusic.mobile.ui.components.GlassCard
import org.michimusic.mobile.ui.components.MichiBackground
import org.michimusic.mobile.ui.components.MichiEmptyState
import org.michimusic.mobile.ui.components.MichiScreen
import org.michimusic.mobile.ui.components.MichiSectionHeader
import org.michimusic.mobile.ui.theme.AccentPink
import org.michimusic.mobile.ui.theme.MichiRadius
import org.michimusic.mobile.ui.theme.MichiSpacing
import org.michimusic.mobile.ui.theme.SurfaceDark
import org.michimusic.mobile.ui.theme.TextDim
import org.michimusic.mobile.ui.theme.TextMuted
import org.michimusic.mobile.ui.theme.TextPrimary
import org.michimusic.mobile.ui.theme.TextSecondary
import org.michimusic.mobile.ui.rememberAudioController

@Composable
fun QueueScreen() {
    val controller = rememberAudioController()
    val state by controller.state.collectAsState()
    val queue = state.queue
    val currentIndex = state.queueIndex
    val currentTrack = state.currentTrack

    MichiBackground {
        Column(modifier = Modifier.fillMaxSize().padding(horizontal = MichiSpacing.lg)) {
            Spacer(Modifier.height(MichiSpacing.lg))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Cola de reproducción", style = MaterialTheme.typography.headlineMedium, color = TextPrimary)
                if (queue.isNotEmpty()) {
                    IconButton(onClick = { controller.clearQueue() }) {
                        Icon(Icons.Default.ClearAll, "Limpiar cola", tint = AccentPink)
                    }
                }
            }

            if (currentTrack != null) {
                Spacer(Modifier.height(MichiSpacing.sm))
                MichiSectionHeader(title = "Reproduciendo ahora")
                Spacer(Modifier.height(MichiSpacing.xs))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(MichiRadius.small))
                        .background(AccentPink.copy(alpha = 0.08f))
                        .padding(horizontal = MichiSpacing.md, vertical = MichiSpacing.sm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier.size(36.dp).clip(RoundedCornerShape(4.dp)).background(AccentPink),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Default.PlayArrow, null, tint = SurfaceDark, modifier = Modifier.size(18.dp))
                    }
                    Spacer(Modifier.width(MichiSpacing.md))
                    Column(Modifier.weight(1f)) {
                        Text(currentTrack.title, style = MaterialTheme.typography.bodyMedium, color = AccentPink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(currentTrack.artist, style = MaterialTheme.typography.bodySmall, color = TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }

            Spacer(Modifier.height(MichiSpacing.md))
            Text("${queue.size} canciones en cola", style = MaterialTheme.typography.bodyMedium, color = TextMuted)
            Spacer(Modifier.height(MichiSpacing.sm))

            if (queue.isEmpty()) {
                MichiEmptyState(
                    icon = Icons.Default.MusicNote,
                    title = "Cola vacía",
                    description = "Reproduce una canción para empezar",
                )
            } else {
                GlassCard(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                ) {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        itemsIndexed(queue) { index, track ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(MichiRadius.small))
                                    .then(
                                        if (index == currentIndex) Modifier.background(AccentPink.copy(alpha = 0.08f))
                                        else Modifier
                                    )
                                    .clickable { controller.playQueue(queue, index) }
                                    .padding(horizontal = MichiSpacing.md, vertical = MichiSpacing.sm),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(32.dp)
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(if (index == currentIndex) AccentPink else TextDim),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text("${index + 1}", style = MaterialTheme.typography.labelSmall, color = SurfaceDark)
                                }
                                Spacer(Modifier.width(MichiSpacing.md))
                                Column(Modifier.weight(1f)) {
                                    Text(track.title, style = MaterialTheme.typography.bodyMedium, color = if (index == currentIndex) AccentPink else TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(track.artist, style = MaterialTheme.typography.bodySmall, color = TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                        item { Spacer(Modifier.height(MichiSpacing.sm)) }
                    }
                }
            }
        }
    }
}
