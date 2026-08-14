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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Equalizer
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.koin.compose.koinInject
import org.michimusic.mobile.ui.components.AlbumArtView
import org.michimusic.mobile.ui.components.EqualizerDialog
import org.michimusic.mobile.ui.components.GradientProgressBar
import org.michimusic.mobile.ui.components.QueueDialog
import org.michimusic.mobile.ui.components.coverStyleFor
import org.michimusic.mobile.ui.components.formatRemainingTimeSeconds
import org.michimusic.mobile.ui.components.formatTimeSeconds
import org.michimusic.mobile.ui.theme.GlassBorderHigh
import org.michimusic.mobile.ui.theme.GlassBorderLow
import org.michimusic.mobile.ui.theme.GlassFillHigh
import org.michimusic.mobile.ui.theme.GlassFillLow
import org.michimusic.mobile.ui.theme.MichiShapes
import org.michimusic.mobile.ui.theme.MichiSpacing
import org.michimusic.mobile.ui.theme.MichiTypography
import org.michimusic.mobile.ui.theme.OnSurfaceVariant
import org.michimusic.mobile.ui.theme.PrimaryPinkContainer
import org.michimusic.mobile.ui.theme.PureWhite
import org.michimusic.mobile.ui.theme.SurfaceObsidian
import org.michimusic.mobile.ui.theme.TertiaryCyan
import org.michimusic.player.AudioController

private fun cycleSleepTimer(current: Long): Long = when {
    current <= 0L -> 15 * 60_000L
    current <= 15 * 60_000L -> 30 * 60_000L
    current <= 30 * 60_000L -> 60 * 60_000L
    else -> 0L
}

@Composable
fun NowPlayingScreen(
    onBack: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val audioController: AudioController = koinInject()
    val playerState by audioController.state.collectAsState()
    val currentTrack = playerState.currentTrack
    val isPlaying = playerState.isPlaying
    val positionSeconds = (playerState.position / 1000f).coerceAtLeast(0f)
    val durationSeconds = (playerState.duration / 1000f).coerceAtLeast(1f)

    val isShuffle = playerState.shuffleMode
    val repeatMode = playerState.repeatMode
    var showQueueDialog by remember { mutableStateOf(false) }
    var showEqualizerDialog by remember { mutableStateOf(false) }
    var showAudioRouteDialog by remember { mutableStateOf(false) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(SurfaceObsidian)
            .testTag("now_playing_screen"),
    ) {
        // Subtle Hi-Fi Ambient Depth
        Box(
            modifier = Modifier
                .fillMaxSize()
                .drawBehind {
                    drawCircle(
                        color = PrimaryPinkContainer.copy(alpha = 0.08f),
                        radius = size.width * 0.5f,
                        center = Offset(size.width * 0.3f, size.height * 0.3f),
                    )
                    drawCircle(
                        color = TertiaryCyan.copy(alpha = 0.06f),
                        radius = size.width * 0.6f,
                        center = Offset(size.width * 0.7f, size.height * 0.7f),
                    )
                },
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 24.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            // Header Bar
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = onBack,
                    modifier = Modifier
                        .size(MichiSpacing.minTouchTarget)
                        .clip(CircleShape)
                        .background(GlassFillLow)
                        .testTag("now_playing_close_button"),
                ) {
                    Icon(
                        imageVector = Icons.Filled.ExpandMore,
                        contentDescription = "Colapsar",
                        tint = PureWhite,
                        modifier = Modifier.size(28.dp),
                    )
                }

                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "REPRODUCIENDO AHORA",
                        style = MichiTypography.screenEyebrow,
                    )
                    Text(
                        text = currentTrack?.album ?: "Michi Music",
                        style = MichiTypography.metadata,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    val sleepTimerRemaining = playerState.sleepTimerRemainingMs
                    val sleepActive = sleepTimerRemaining > 0L

                    IconButton(
                        onClick = {
                            val next = cycleSleepTimer(sleepTimerRemaining)
                            if (next <= 0L) audioController.cancelSleepTimer() else audioController.setSleepTimer(next)
                        },
                        modifier = Modifier
                            .size(MichiSpacing.minTouchTarget)
                            .clip(CircleShape)
                            .background(if (sleepActive) TertiaryCyan.copy(alpha = 0.2f) else GlassFillLow)
                            .border(1.dp, if (sleepActive) TertiaryCyan else GlassBorderLow, CircleShape)
                            .testTag("sleep_timer_button"),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Timer,
                            contentDescription = "Temporizador de Apagado",
                            tint = if (sleepActive) TertiaryCyan else PureWhite,
                            modifier = Modifier.size(20.dp),
                        )
                    }

                    IconButton(
                        onClick = { showQueueDialog = true },
                        modifier = Modifier
                            .size(MichiSpacing.minTouchTarget)
                            .clip(CircleShape)
                            .background(GlassFillLow)
                            .testTag("now_playing_queue_button"),
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.QueueMusic,
                            contentDescription = "Cola de Reproducción",
                            tint = PureWhite,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                }
            }

            // Central Album Artwork Container
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(vertical = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.85f)
                        .aspectRatio(1f)
                        .clip(MichiShapes.lg)
                        .border(1.dp, GlassBorderHigh, MichiShapes.lg)
                        .testTag("now_playing_album_art"),
                ) {
                    AlbumArtView(
                        coverStyle = coverStyleFor(currentTrack?.coverId?.ifEmpty { currentTrack.title } ?: currentTrack?.title),
                        imageModel = currentTrack?.filepath?.ifEmpty { currentTrack.coverId } ?: currentTrack?.coverId,
                        modifier = Modifier.fillMaxSize(),
                        cornerRadius = 24.dp,
                        borderWidth = 0.dp,
                    )
                }
            }

            // Track Info & Destination Capsule
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = currentTrack?.title ?: "Sin reproducción activa",
                            style = MichiTypography.screenTitle,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = currentTrack?.artist ?: "Selecciona una canción",
                            style = MichiTypography.sectionTitle.copy(fontWeight = FontWeight.Normal),
                            color = OnSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }

                    // Endpoint selector capsule
                    Box(
                        modifier = Modifier
                            .clip(MichiShapes.pill)
                            .background(GlassFillLow)
                            .border(1.dp, GlassBorderLow, MichiShapes.pill)
                            .clickable { showAudioRouteDialog = true }
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                            .testTag("audio_route_button"),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Headphones,
                                contentDescription = "Salidas de Audio",
                                tint = TertiaryCyan,
                                modifier = Modifier.size(16.dp),
                            )
                            Text(
                                text = "Este teléfono",
                                color = PureWhite,
                                style = MichiTypography.microLabel,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Icon(
                                imageVector = Icons.Filled.ExpandMore,
                                contentDescription = null,
                                tint = OnSurfaceVariant,
                                modifier = Modifier.size(14.dp),
                            )
                        }
                    }
                }

                // Progress Bar
                Column(modifier = Modifier.fillMaxWidth()) {
                    GradientProgressBar(
                        currentValue = positionSeconds,
                        maxValue = durationSeconds,
                        onSeek = { seekSec ->
                            audioController.seekTo((seekSec * 1000).toLong())
                        },
                        trackHeight = 5.dp,
                        showThumb = true,
                        testTag = "now_playing_seeker",
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = formatTimeSeconds(positionSeconds),
                            style = MichiTypography.microLabel,
                            color = OnSurfaceVariant,
                        )
                        Text(
                            text = formatRemainingTimeSeconds(positionSeconds, durationSeconds),
                            style = MichiTypography.microLabel,
                            color = OnSurfaceVariant,
                        )
                    }
                }

                // Main Playback Controls
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Shuffle
                    IconButton(
                        onClick = { audioController.toggleShuffle() },
                        modifier = Modifier
                            .size(MichiSpacing.minTouchTarget)
                            .testTag("now_playing_shuffle"),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Shuffle,
                            contentDescription = "Aleatorio",
                            tint = if (isShuffle) TertiaryCyan else OnSurfaceVariant,
                            modifier = Modifier.size(22.dp),
                        )
                    }

                    // Skip Previous
                    IconButton(
                        onClick = { audioController.skipPrevious() },
                        modifier = Modifier
                            .size(MichiSpacing.minTouchTarget)
                            .clip(CircleShape)
                            .background(GlassFillLow)
                            .testTag("now_playing_prev"),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.SkipPrevious,
                            contentDescription = "Anterior",
                            tint = PureWhite,
                            modifier = Modifier.size(28.dp),
                        )
                    }

                    // Center Play / Pause
                    Box(
                        modifier = Modifier
                            .size(68.dp)
                            .clip(CircleShape)
                            .background(PrimaryPinkContainer)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = ripple(color = PureWhite),
                                onClick = {
                                    if (isPlaying) audioController.pause() else audioController.play()
                                },
                            )
                            .testTag("now_playing_play_pause"),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            contentDescription = if (isPlaying) "Pausa" else "Reproducir",
                            tint = PureWhite,
                            modifier = Modifier.size(36.dp),
                        )
                    }

                    // Skip Next
                    IconButton(
                        onClick = { audioController.skipNext() },
                        modifier = Modifier
                            .size(MichiSpacing.minTouchTarget)
                            .clip(CircleShape)
                            .background(GlassFillLow)
                            .testTag("now_playing_next"),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.SkipNext,
                            contentDescription = "Siguiente",
                            tint = PureWhite,
                            modifier = Modifier.size(28.dp),
                        )
                    }

                    // Repeat
                    IconButton(
                        onClick = { audioController.cycleRepeatMode() },
                        modifier = Modifier
                            .size(MichiSpacing.minTouchTarget)
                            .testTag("now_playing_repeat"),
                    ) {
                        Icon(
                            imageVector = if (repeatMode == 1) Icons.Filled.RepeatOne else Icons.Filled.Repeat,
                            contentDescription = "Repetir",
                            tint = if (repeatMode != 0) TertiaryCyan else OnSurfaceVariant,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                }

                // Bottom Pill Row
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Up Next Capsule Pill
                    val queue = playerState.queue
                    val currentIndex = currentTrack?.let { cur -> queue.indexOfFirst { it.id == cur.id } } ?: -1
                    val nextTrack = if (currentIndex in queue.indices && currentIndex + 1 < queue.size) {
                        queue[currentIndex + 1]
                    } else if (playerState.repeatMode == 2 && queue.isNotEmpty()) {
                        queue.first()
                    } else {
                        null
                    }
                    val nextTrackTitle = nextTrack?.title ?: if (queue.isNotEmpty()) "Fin de la cola" else "Michi Music"

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(MichiShapes.pill)
                            .background(GlassFillLow)
                            .border(1.dp, GlassBorderLow, MichiShapes.pill)
                            .clickable { showQueueDialog = true }
                            .padding(horizontal = 14.dp, vertical = 8.dp)
                            .testTag("up_next_pill"),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.QueueMusic,
                                contentDescription = null,
                                tint = OnSurfaceVariant,
                                modifier = Modifier.size(16.dp),
                            )
                            Text(
                                text = "Siguiente: $nextTrackTitle",
                                color = PureWhite,
                                style = MichiTypography.microLabel,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(12.dp))

                    // Equalizer Button
                    IconButton(
                        onClick = { showEqualizerDialog = true },
                        modifier = Modifier
                            .size(MichiSpacing.minTouchTarget)
                            .clip(CircleShape)
                            .background(GlassFillLow)
                            .border(1.dp, GlassBorderLow, CircleShape)
                            .testTag("equalizer_button"),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Equalizer,
                            contentDescription = "Ecualizador",
                            tint = OnSurfaceVariant,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }
        }

        // Dialogs
        if (showAudioRouteDialog) {
            org.michimusic.mobile.ui.components.AudioRouteDialog(onDismiss = { showAudioRouteDialog = false })
        }

        if (showQueueDialog) {
            QueueDialog(
                queue = playerState.queue,
                currentTrack = currentTrack,
                onTrackSelect = { selected ->
                    val idx = playerState.queue.indexOfFirst { it.id == selected.id }.coerceAtLeast(0)
                    audioController.playQueue(playerState.queue, idx)
                },
                onDismiss = { showQueueDialog = false },
            )
        }

        if (showEqualizerDialog) {
            EqualizerDialog(onDismiss = { showEqualizerDialog = false })
        }
    }
}
