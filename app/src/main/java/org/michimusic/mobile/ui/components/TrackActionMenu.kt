package org.michimusic.mobile.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import org.michimusic.core.models.Track
import org.michimusic.data.repository.PlaylistRepository
import org.michimusic.mobile.ui.theme.GlassBorderHigh
import org.michimusic.mobile.ui.theme.GlassBorderLow
import org.michimusic.mobile.ui.theme.GlassFillLow
import org.michimusic.mobile.ui.theme.PrimaryPink
import org.michimusic.mobile.ui.theme.PureWhite
import org.michimusic.mobile.ui.theme.SurfaceDark
import org.michimusic.mobile.ui.theme.SurfaceObsidian
import org.michimusic.mobile.ui.theme.TertiaryCyan
import org.michimusic.mobile.ui.theme.TextMuted
import org.michimusic.mobile.ui.theme.TextSecondary

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrackActionBottomSheet(
    track: Track,
    isFavorite: Boolean = false,
    onDismiss: () -> Unit,
    onPlayNow: () -> Unit = {},
    onPlayNext: () -> Unit = {},
    onAddToQueue: () -> Unit = {},
    onAddToPlaylist: () -> Unit = {},
    onToggleFavorite: () -> Unit = {},
    onViewSignalTruth: (() -> Unit)? = null,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = SurfaceDark,
        tonalElevation = 8.dp,
        dragHandle = {
            Spacer(
                Modifier
                    .padding(vertical = 10.dp)
                    .width(36.dp)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(PureWhite.copy(alpha = 0.2f))
            )
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp),
        ) {
            // Header: Track preview
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                AlbumArtView(
                    coverStyle = coverStyleFor(track.title),
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(8.dp)),
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = track.title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = PureWhite,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = track.artist.ifBlank { "Artista desconocido" },
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            TrackActionMenuItem(
                icon = Icons.Default.PlayArrow,
                title = "Reproducir ahora",
                onClick = {
                    onPlayNow()
                    onDismiss()
                },
            )

            TrackActionMenuItem(
                icon = Icons.AutoMirrored.Filled.PlaylistPlay,
                title = "Reproducir siguiente",
                onClick = {
                    onPlayNext()
                    onDismiss()
                },
            )

            TrackActionMenuItem(
                icon = Icons.AutoMirrored.Filled.QueueMusic,
                title = "Añadir a la cola",
                onClick = {
                    onAddToQueue()
                    onDismiss()
                },
            )

            TrackActionMenuItem(
                icon = Icons.AutoMirrored.Filled.PlaylistAdd,
                title = "Añadir a playlist",
                onClick = {
                    onAddToPlaylist()
                    onDismiss()
                },
            )

            TrackActionMenuItem(
                icon = if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                title = if (isFavorite) "Quitar de favoritos" else "Añadir a favoritos",
                tint = if (isFavorite) PrimaryPink else PureWhite,
                onClick = {
                    onToggleFavorite()
                    onDismiss()
                },
            )

            if (onViewSignalTruth != null) {
                TrackActionMenuItem(
                    icon = Icons.Default.GraphicEq,
                    title = "Cadena de audio y señal (Signal Truth)",
                    tint = TertiaryCyan,
                    onClick = {
                        onViewSignalTruth()
                        onDismiss()
                    },
                )
            }

            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun TrackActionMenuItem(
    icon: ImageVector,
    title: String,
    tint: androidx.compose.ui.graphics.Color = PureWhite,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(22.dp),
        )
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = PureWhite,
        )
    }
}

@Composable
fun AddToPlaylistDialog(
    tracks: List<Track>,
    onDismiss: () -> Unit,
    playlistRepo: PlaylistRepository = koinInject(),
    onSuccess: (() -> Unit)? = null,
) {
    val coroutineScope = rememberCoroutineScope()
    val playlists by playlistRepo.observePlaylists().collectAsState(initial = emptyList())
    var isAdding by remember { mutableStateOf(false) }
    var addError by remember { mutableStateOf<String?>(null) }

    Dialog(onDismissRequest = {
        if (!isAdding) onDismiss()
    }) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = SurfaceObsidian,
            border = BorderStroke(1.dp, GlassBorderHigh),
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = "Añadir a playlist",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = PureWhite,
                )
                val description = if (tracks.size == 1) {
                    "Selecciona una playlist para «${tracks.first().title}»"
                } else {
                    "Selecciona una playlist para ${tracks.size} canciones"
                }
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                )

                if (addError != null) {
                    Text(
                        text = addError.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                if (isAdding) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 16.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            color = PrimaryPink,
                            strokeWidth = 2.dp,
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = "Añadiendo canciones...",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary,
                        )
                    }
                } else if (playlists.isEmpty()) {
                    Text(
                        text = "No tienes playlists creadas.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextMuted,
                        modifier = Modifier.padding(vertical = 12.dp),
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height((playlists.size * 56).coerceAtMost(240).dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(playlists, key = { it.id }) { pl ->
                            GlassCard(
                                modifier = Modifier.fillMaxWidth(),
                                backgroundColor = GlassFillLow,
                                borderColor = GlassBorderLow,
                                onClick = {
                                    if (!isAdding) {
                                        isAdding = true
                                        addError = null
                                        coroutineScope.launch {
                                            try {
                                                val result = playlistRepo.addTracksToPlaylist(pl.id, tracks.map { it.id })
                                                if (result is PlaylistRepository.PlaylistAddResult.Success) {
                                                    if (result.addedCount > 0) {
                                                        onSuccess?.invoke()
                                                        onDismiss()
                                                    } else {
                                                        addError = if (tracks.size == 1) {
                                                            "La canción ya estaba en esta playlist"
                                                        } else {
                                                            "Las canciones ya estaban en esta playlist"
                                                        }
                                                    }
                                                } else {
                                                    addError = "No se pudieron añadir las canciones a la playlist"
                                                }
                                            } catch (c: kotlinx.coroutines.CancellationException) {
                                                throw c
                                            } catch (e: Exception) {
                                                addError = "Error al añadir a playlist: ${e.message}"
                                            } finally {
                                                isAdding = false
                                            }
                                        }
                                    }
                                },
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    Text(
                                        text = pl.name,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        color = PureWhite,
                                    )
                                    Text(
                                        text = "${pl.trackCount} pistas",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = TextSecondary,
                                    )
                                }
                            }
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(
                        onClick = onDismiss,
                        enabled = !isAdding,
                    ) {
                        Text(text = "Cancelar", color = TextSecondary)
                    }
                }
            }
        }
    }
}
