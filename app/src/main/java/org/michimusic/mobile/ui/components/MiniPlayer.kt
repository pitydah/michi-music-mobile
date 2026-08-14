package org.michimusic.mobile.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.koin.compose.koinInject
import org.michimusic.mobile.ui.theme.GlassBorderHigh
import org.michimusic.mobile.ui.theme.GlassFillOverlay
import org.michimusic.mobile.ui.theme.OnSurfaceVariant
import org.michimusic.mobile.ui.theme.PrimaryPinkContainer
import org.michimusic.mobile.ui.theme.PureWhite
import org.michimusic.mobile.ui.theme.SecondaryPurple
import org.michimusic.mobile.ui.theme.TertiaryCyan
import org.michimusic.player.AudioController

@Composable
fun MiniPlayer(
    modifier: Modifier = Modifier,
    onClick: () -> Unit = {},
    visible: Boolean = true,
) {
    val audioController: AudioController = koinInject()
    val state by audioController.state.collectAsState()
    val currentTrack = state.currentTrack

    AnimatedVisibility(
        visible = visible && currentTrack != null,
        enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
        exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
        modifier = modifier,
    ) {
        if (currentTrack != null) {
            val isPlaying = state.isPlaying
            val progressFraction = if (state.duration > 0) {
                (state.position.toFloat() / state.duration.toFloat()).coerceIn(0f, 1f)
            } else {
                0f
            }
            val shape = RoundedCornerShape(16.dp)

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp)
                    .drawBehind {
                        drawCircle(
                            color = TertiaryCyan.copy(alpha = 0.15f),
                            radius = size.maxDimension * 0.5f,
                            center = center,
                        )
                    }
                    .clip(shape)
                    .background(GlassFillOverlay)
                    .border(1.dp, GlassBorderHigh, shape)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = ripple(color = TertiaryCyan),
                        onClick = onClick,
                    )
                    .testTag("mini_player_bar"),
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    // Top mini progress bar
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(2.5.dp)
                            .background(Color(0x22FFFFFF)),
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(fraction = progressFraction)
                                .height(2.5.dp)
                                .background(
                                    Brush.horizontalGradient(
                                        listOf(PrimaryPinkContainer, SecondaryPurple, TertiaryCyan),
                                    ),
                                ),
                        )
                    }

                    // Player row content
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // Album artwork thumbnail
                        AlbumArtView(
                            coverStyle = coverStyleFor(currentTrack.coverId.ifEmpty { currentTrack.title }),
                            imageModel = currentTrack.coverId,
                            modifier = Modifier.size(44.dp),
                            cornerRadius = 10.dp,
                        )

                        Spacer(modifier = Modifier.width(12.dp))

                        // Title & Artist
                        Column(
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(
                                text = currentTrack.title,
                                color = PureWhite,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = currentTrack.artist,
                                color = TertiaryCyan,
                                fontSize = 12.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }

                        Spacer(modifier = Modifier.width(8.dp))

                        // Animated mini equalizer
                        EqualizerWaveBars(
                            isPlaying = isPlaying,
                            barCount = 3,
                            color = TertiaryCyan,
                        )

                        Spacer(modifier = Modifier.width(6.dp))

                        // Play / Pause Button
                        IconButton(
                            onClick = {
                                if (isPlaying) audioController.pause() else audioController.play()
                            },
                            modifier = Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .background(Color(0x1FFFFFFF))
                                .testTag("mini_player_play_pause"),
                        ) {
                            Icon(
                                imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                                contentDescription = if (isPlaying) "Pausar" else "Reproducir",
                                tint = PureWhite,
                                modifier = Modifier.size(22.dp),
                            )
                        }

                        // Next Track Button
                        IconButton(
                            onClick = { audioController.skipNext() },
                            modifier = Modifier
                                .size(48.dp)
                                .testTag("mini_player_next"),
                        ) {
                            Icon(
                                imageVector = Icons.Filled.SkipNext,
                                contentDescription = "Siguiente Canción",
                                tint = OnSurfaceVariant,
                                modifier = Modifier.size(24.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}
