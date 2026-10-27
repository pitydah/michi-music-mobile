package org.michimusic.mobile.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import org.michimusic.core.models.Track
import org.michimusic.data.repository.FavoritesRepository
import org.michimusic.mobile.ui.components.AddToPlaylistDialog
import org.michimusic.mobile.ui.components.TrackActionBottomSheet
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.koin.compose.koinInject
import org.michimusic.mobile.ui.components.AlbumArtView
import org.michimusic.mobile.ui.components.EqualizerWaveBars
import org.michimusic.mobile.ui.components.GlassCard
import org.michimusic.mobile.ui.components.formatTimeMillis
import org.michimusic.mobile.ui.theme.GlassBorderHigh
import org.michimusic.mobile.ui.theme.GlassBorderLow
import org.michimusic.mobile.ui.theme.GlassFillHigh
import org.michimusic.mobile.ui.theme.GlassFillLow
import org.michimusic.mobile.ui.theme.PrimaryPink
import org.michimusic.mobile.ui.theme.PrimaryPinkContainer
import org.michimusic.mobile.ui.theme.PureWhite
import org.michimusic.mobile.ui.theme.SecondaryPurple
import org.michimusic.mobile.ui.theme.SurfaceDark
import org.michimusic.mobile.ui.theme.SurfaceObsidian
import org.michimusic.mobile.ui.theme.TertiaryCyan
import org.michimusic.mobile.ui.theme.TextDim
import org.michimusic.mobile.ui.theme.TextMuted
import org.michimusic.mobile.ui.theme.TextPrimary
import org.michimusic.mobile.ui.theme.TextSecondary
import org.michimusic.mobile.ui.theme.MichiSpacing
import org.michimusic.mobile.ui.theme.MichiTypography
import org.michimusic.mobile.ui.theme.AeroGraphiteRaised
import org.michimusic.player.AudioController

@Composable
fun QueueScreen() {
    val controller: AudioController = koinInject()
    val favoritesRepo: FavoritesRepository = koinInject()
    val favoriteIds by favoritesRepo.observeFavoriteTrackIds().collectAsState(initial = emptySet())
    val coroutineScope = rememberCoroutineScope()
    val state by controller.state.collectAsState()
    val isReady by controller.isReady.collectAsState()
    val queue = state.queue
    val currentIndex = state.queueIndex
    var showClearConfirmation by remember { mutableStateOf(false) }

    var selectedTrackForMenu by remember { mutableStateOf<Track?>(null) }
    var trackToAddToPlaylist by remember { mutableStateOf<Track?>(null) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(SurfaceObsidian)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = MichiSpacing.screenHorizontal),
        ) {
            Spacer(Modifier.height(16.dp))

            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "REPRODUCCIÓN",
                        style = MichiTypography.screenEyebrow,
                    )
                    Text(
                        text = "Cola de reproducción",
                        style = MichiTypography.sectionTitle,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                if (queue.isNotEmpty() && isReady) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(AeroGraphiteRaised)
                            .border(0.75.dp, org.michimusic.mobile.ui.theme.AeroHairline, RoundedCornerShape(12.dp))
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = ripple(color = PrimaryPink),
                                onClick = { showClearConfirmation = true },
                            )
                            .height(48.dp)
                            .padding(horizontal = 12.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Icon(
                                Icons.Default.DeleteSweep,
                                contentDescription = "Limpiar cola",
                                tint = PrimaryPink,
                                modifier = Modifier.size(16.dp),
                            )
                            Text(
                                text = "Limpiar",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = PrimaryPink,
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            // Status Banner
            if (queue.isNotEmpty()) {
                GlassCard(
                    modifier = Modifier.fillMaxWidth(),
                    backgroundColor = GlassFillLow,
                    borderColor = GlassBorderLow,
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            EqualizerWaveBars(
                                isPlaying = state.isPlaying,
                                barCount = 4,
                                color = TertiaryCyan,
                            )

                            Text(
                                text = "${queue.size} canciones en cola",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = PureWhite,
                            )
                        }

                        Text(
                            text = if (currentIndex in queue.indices) "Pista ${currentIndex + 1} de ${queue.size}" else "Ninguna seleccionada",
                            fontSize = 12.sp,
                            color = TextSecondary,
                        )
                    }
                }

                Spacer(Modifier.height(16.dp))
            }

            // Queue List
            if (queue.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
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
                            text = "La cola está vacía",
                            style = MaterialTheme.typography.titleMedium,
                            color = PureWhite,
                        )
                        Text(
                            text = "Reproduce una canción, álbum o playlist para comenzar",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextMuted,
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    itemsIndexed(queue) { idx, track ->
                        val isCurrent = idx == currentIndex
                        GlassCard(
                            modifier = Modifier.fillMaxWidth(),
                            backgroundColor = if (isCurrent) AeroGraphiteRaised else GlassFillLow,
                            borderColor = GlassBorderLow,
                            accent = if (isCurrent) PrimaryPinkContainer else null,
                            onClick = { controller.playQueue(queue, idx) },
                        ) {
                            // Separate track metadata from queue actions. Four 44dp icons
                            // alongside title/artwork did not fit 320dp screens.
                            Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                // Index or Equalizer
                                Box(
                                    modifier = Modifier.size(28.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    if (isCurrent && state.isPlaying) {
                                        EqualizerWaveBars(
                                            isPlaying = true,
                                            barCount = 3,
                                            color = TertiaryCyan,
                                        )
                                    } else {
                                        Text(
                                            text = (idx + 1).toString(),
                                            fontSize = 12.sp,
                                            fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                                            color = if (isCurrent) TertiaryCyan else TextMuted,
                                            fontFamily = FontFamily.Monospace,
                                        )
                                    }
                                }

                                // Thumbnail
                                AlbumArtView(
                                    coverStyle = org.michimusic.mobile.ui.components.coverStyleFor(track.coverId.ifBlank { track.title }),
                                    imageModel = track.coverId,
                                    fallbackMediaUri = track.filepath,
                                    modifier = Modifier
                                        .size(42.dp)
                                        .clip(RoundedCornerShape(8.dp)),
                                )

                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = track.title,
                                        fontSize = 14.sp,
                                        fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.SemiBold,
                                        color = PureWhite,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        text = track.artist,
                                        fontSize = 12.sp,
                                        color = TextSecondary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }

                                Text(
                                    text = formatTimeMillis(track.duration),
                                    fontSize = 12.sp,
                                    color = TextMuted,
                                )

                                } // Track content row
                                Spacer(Modifier.height(8.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.End,
                                ) {
                                    if (idx > 0) {
                                        androidx.compose.material3.IconButton(
                                            onClick = { controller.moveQueueItem(idx, idx - 1) },
                                            modifier = Modifier.size(48.dp),
                                        ) {
                                            Icon(
                                                imageVector = androidx.compose.material.icons.Icons.Default.KeyboardArrowUp,
                                                contentDescription = "Subir ${track.title} en la cola",
                                                tint = TextSecondary,
                                                modifier = Modifier.size(20.dp),
                                            )
                                        }
                                    }
                                    if (idx < queue.lastIndex) {
                                        androidx.compose.material3.IconButton(
                                            onClick = { controller.moveQueueItem(idx, idx + 1) },
                                            modifier = Modifier.size(48.dp),
                                        ) {
                                            Icon(
                                                imageVector = androidx.compose.material.icons.Icons.Default.KeyboardArrowDown,
                                                contentDescription = "Bajar ${track.title} en la cola",
                                                tint = TextSecondary,
                                                modifier = Modifier.size(20.dp),
                                            )
                                        }
                                    }
                                    androidx.compose.material3.IconButton(
                                        onClick = { controller.removeFromQueue(idx) },
                                        modifier = Modifier.size(48.dp),
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Clear,
                                            contentDescription = "Quitar ${track.title} de la cola",
                                            tint = org.michimusic.mobile.ui.theme.ErrorColor,
                                            modifier = Modifier.size(18.dp),
                                        )
                                    }
                                    androidx.compose.material3.IconButton(
                                        onClick = { selectedTrackForMenu = track },
                                        modifier = Modifier.size(48.dp),
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.MoreVert,
                                            contentDescription = "Opciones de ${track.title}",
                                            tint = TextSecondary,
                                            modifier = Modifier.size(20.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }

                    item {
                        Spacer(Modifier.height(100.dp))
                    }
                }
            }
        }

        if (showClearConfirmation) {
            AlertDialog(
                onDismissRequest = { showClearConfirmation = false },
                title = { Text("Vaciar la cola") },
                text = { Text("Se quitarán ${queue.size} pistas de la cola actual. No se eliminarán archivos de tu biblioteca.") },
                confirmButton = {
                    TextButton(onClick = {
                        showClearConfirmation = false
                        controller.clearQueue()
                    }) { Text("Vaciar cola") }
                },
                dismissButton = {
                    TextButton(onClick = { showClearConfirmation = false }) { Text("Cancelar") }
                },
                containerColor = SurfaceDark,
                titleContentColor = PureWhite,
                textContentColor = TextSecondary,
            )
        }

        selectedTrackForMenu?.let { track ->
            TrackActionBottomSheet(
                track = track,
                isFavorite = favoriteIds.contains(track.id),
                onDismiss = { selectedTrackForMenu = null },
                onPlayNow = {
                    val idx = queue.indexOf(track).coerceAtLeast(0)
                    controller.playQueue(queue, idx)
                },
                onPlayNext = {
                    controller.playNext(track)
                },
                onAddToQueue = {
                    controller.addToQueue(track)
                },
                onAddToPlaylist = {
                    trackToAddToPlaylist = track
                },
                onToggleFavorite = {
                    coroutineScope.launch {
                        favoritesRepo.toggleFavorite(track.id)
                    }
                },
            )
        }

        trackToAddToPlaylist?.let { track ->
            AddToPlaylistDialog(
                tracks = listOf(track),
                onDismiss = { trackToAddToPlaylist = null },
            )
        }
    }
}
