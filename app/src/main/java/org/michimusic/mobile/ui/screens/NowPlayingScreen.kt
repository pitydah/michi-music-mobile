package org.michimusic.mobile.ui.screens

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Equalizer
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.outlined.FavoriteBorder
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
import androidx.compose.ui.graphics.Brush
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
import org.michimusic.mobile.ui.theme.OnSurfaceVariant
import org.michimusic.mobile.ui.theme.PrimaryPink
import org.michimusic.mobile.ui.theme.PrimaryPinkContainer
import org.michimusic.mobile.ui.theme.PureWhite
import org.michimusic.mobile.ui.theme.SecondaryPurple
import org.michimusic.mobile.ui.theme.SecondaryPurpleDeep
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

    var isFavorite by remember { mutableStateOf(false) }
    var isShuffle by remember { mutableStateOf(false) }
    var repeatMode by remember { mutableStateOf(0) } // 0: off, 1: all, 2: one
    var showQueueDialog by remember { mutableStateOf(false) }
    var showEqualizerDialog by remember { mutableStateOf(false) }

    val transition = rememberInfiniteTransition(label = "now_playing_glow")
    val glowRadiusScale by transition.animateFloat(
        initialValue = 0.85f,
        targetValue = 1.15f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "glow_radius",
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(SurfaceObsidian),
    ) {
        // Deep Space Ambient Glow Orbs
        Box(
            modifier = Modifier
                .fillMaxSize()
                .drawBehind {
                    drawCircle(
                        color = PrimaryPinkContainer.copy(alpha = 0.18f),
                        radius = size.width * 0.55f * glowRadiusScale,
                        center = Offset(size.width * 0.25f, size.height * 0.35f),
                    )
                    drawCircle(
                        color = TertiaryCyan.copy(alpha = 0.14f),
                        radius = size.width * 0.65f * glowRadiusScale,
                        center = Offset(size.width * 0.75f, size.height * 0.65f),
                    )
                },
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 24.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = onBack,
                    modifier = Modifier
                        .size(44.dp)
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
                        color = PrimaryPink,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 2.sp,
                    )
                    Text(
                        text = currentTrack?.album ?: "Michi Music",
                        color = OnSurfaceVariant,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                IconButton(
                    onClick = { showQueueDialog = true },
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(GlassFillLow),
                ) {
                    Icon(
                        imageVector = Icons.Filled.MoreVert,
                        contentDescription = "Opciones",
                        tint = PureWhite,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }

            // Central Album Artwork Container
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(vertical = 16.dp),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.88f)
                        .aspectRatio(1f)
                        .drawBehind {
                            drawCircle(
                                color = PrimaryPink.copy(alpha = 0.22f),
                                radius = size.maxDimension * 0.65f * glowRadiusScale,
                                center = center,
                            )
                        }
                        .clip(RoundedCornerShape(32.dp))
                        .border(1.5.dp, GlassBorderHigh, RoundedCornerShape(32.dp))
                        .testTag("now_playing_album_art"),
                ) {
                    AlbumArtView(
                        coverStyle = coverStyleFor(currentTrack?.coverId ?: currentTrack?.title),
                        imageModel = currentTrack?.filepath?.ifEmpty { currentTrack.coverId },
                        modifier = Modifier.fillMaxSize(),
                        cornerRadius = 32.dp,
                        borderWidth = 0.dp,
                    )
                }
            }

            // Track Information & Controls Section
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = currentTrack?.title ?: "Sin reproducción activa",
                            color = PureWhite,
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = (-0.5).sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = currentTrack?.artist ?: "Selecciona una canción",
                            color = OnSurfaceVariant.copy(alpha = 0.85f),
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Normal,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }

                    IconButton(
                        onClick = { isFavorite = !isFavorite },
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("now_playing_favorite_button"),
                    ) {
                        Icon(
                            imageVector = if (isFavorite) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                            contentDescription = "Favorito",
                            tint = if (isFavorite) PrimaryPinkContainer else OnSurfaceVariant,
                            modifier = Modifier.size(28.dp),
                        )
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
                        trackHeight = 6.dp,
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
                            color = OnSurfaceVariant,
                            fontSize = 12.sp,
                        )
                        Text(
                            text = formatRemainingTimeSeconds(positionSeconds, durationSeconds),
                            color = OnSurfaceVariant,
                            fontSize = 12.sp,
                        )
                    }
                }

                // Main Playback Controls
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Shuffle
                    IconButton(
                        onClick = { isShuffle = !isShuffle },
                        modifier = Modifier.size(40.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Shuffle,
                            contentDescription = "Aleatorio",
                            tint = if (isShuffle) TertiaryCyan else OnSurfaceVariant,
                            modifier = Modifier.size(24.dp),
                        )
                    }

                    // Skip Previous
                    IconButton(
                        onClick = { audioController.skipPrevious() },
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(GlassFillLow)
                            .testTag("now_playing_prev"),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.SkipPrevious,
                            contentDescription = "Anterior",
                            tint = PureWhite,
                            modifier = Modifier.size(32.dp),
                        )
                    }

                    // Large Glowing Center Play / Pause
                    Box(
                        modifier = Modifier
                            .size(76.dp)
                            .drawBehind {
                                drawCircle(
                                    color = PrimaryPinkContainer.copy(alpha = 0.45f),
                                    radius = 48.dp.toPx(),
                                )
                            }
                            .clip(CircleShape)
                            .background(
                                Brush.linearGradient(
                                    listOf(PrimaryPinkContainer, SecondaryPurpleDeep),
                                ),
                            )
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
                            modifier = Modifier.size(40.dp),
                        )
                    }

                    // Skip Next
                    IconButton(
                        onClick = { audioController.skipNext() },
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(GlassFillLow)
                            .testTag("now_playing_next"),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.SkipNext,
                            contentDescription = "Siguiente",
                            tint = PureWhite,
                            modifier = Modifier.size(32.dp),
                        )
                    }

                    // Repeat
                    IconButton(
                        onClick = { repeatMode = (repeatMode + 1) % 3 },
                        modifier = Modifier.size(40.dp),
                    ) {
                        Icon(
                            imageVector = if (repeatMode == 2) Icons.Filled.RepeatOne else Icons.Filled.Repeat,
                            contentDescription = "Repetir",
                            tint = if (repeatMode != 0) TertiaryCyan else OnSurfaceVariant,
                            modifier = Modifier.size(24.dp),
                        )
                    }
                }

                // Bottom Pill Row
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    var showAudioRouteDialog by remember { mutableStateOf(false) }

                    // Audio Output Route Selector Button (Headphones icon)
                    IconButton(
                        onClick = { showAudioRouteDialog = true },
                        modifier = Modifier
                            .size(42.dp)
                            .clip(CircleShape)
                            .background(GlassFillLow)
                            .border(1.dp, GlassBorderLow, CircleShape)
                            .testTag("audio_route_button"),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Headphones,
                            contentDescription = "Salidas de Audio",
                            tint = TertiaryCyan,
                            modifier = Modifier.size(20.dp),
                        )
                    }

                    // Up Next Capsule Pill
                    val queue = playerState.queue
                    val nextTrackTitle = if (queue.isNotEmpty()) queue.first().title else "Michi Music"
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(20.dp))
                            .background(GlassFillHigh)
                            .border(1.dp, GlassBorderHigh, RoundedCornerShape(20.dp))
                            .clickable { showQueueDialog = true }
                            .padding(horizontal = 16.dp, vertical = 10.dp)
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
                                modifier = Modifier.size(18.dp),
                            )
                            Text(
                                text = "Siguiente: $nextTrackTitle",
                                color = PureWhite,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }

                    // Equalizer Button
                    IconButton(
                        onClick = { showEqualizerDialog = true },
                        modifier = Modifier
                            .size(42.dp)
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

                    if (showAudioRouteDialog) {
                        org.michimusic.mobile.ui.components.AudioRouteDialog(onDismiss = { showAudioRouteDialog = false })
                    }
                }
            }
        }

        // Dialogs
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
