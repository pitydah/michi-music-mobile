package org.michimusic.mobile.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.koin.compose.koinInject
import org.michimusic.mobile.playback.PlaybackSessionManager
import org.michimusic.mobile.ui.theme.AeroGraphiteRaised
import org.michimusic.mobile.ui.theme.AeroHairline
import org.michimusic.mobile.ui.theme.GlassBorderLow
import org.michimusic.mobile.ui.theme.GlassFillHigh
import org.michimusic.mobile.ui.theme.MichiShapes
import org.michimusic.mobile.ui.theme.MichiSpacing
import org.michimusic.mobile.ui.theme.MichiTypography
import org.michimusic.mobile.ui.theme.OnSurfaceVariant
import androidx.compose.ui.graphics.Brush
import org.michimusic.mobile.ui.theme.PrimaryPinkContainer
import org.michimusic.mobile.ui.theme.PureWhite
import org.michimusic.mobile.ui.theme.SurfaceObsidian
import org.michimusic.mobile.ui.theme.TertiaryCyan
import org.michimusic.player.AudioController

@Composable
fun MiniPlayer(
    modifier: Modifier = Modifier,
    onClick: () -> Unit = {},
    visible: Boolean = true,
) {
    val sessionManager: PlaybackSessionManager = koinInject()
    val sessionState by sessionManager.sessionState.collectAsState()
    val activeEndpoint = sessionState.activeEndpoint

    val audioController: AudioController = koinInject()
    val localState by audioController.state.collectAsState()

    val currentTrack = sessionState.currentTrack ?: localState.currentTrack
    val reducedMotion = org.michimusic.mobile.ui.theme.LocalVisualPreferences.current.reducedMotion

    AnimatedVisibility(
        visible = visible && currentTrack != null,
        enter = if (reducedMotion) EnterTransition.None else slideInVertically(initialOffsetY = { it }) + fadeIn(),
        exit = if (reducedMotion) ExitTransition.None else slideOutVertically(targetOffsetY = { it }) + fadeOut(),
        modifier = modifier,
    ) {
        if (currentTrack != null) {
            val isPlaying = sessionState.isPlaying
            val progressFraction = if (sessionState.duration > 0) {
                (sessionState.position.toFloat() / sessionState.duration.toFloat()).coerceIn(0f, 1f)
            } else {
                0f
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = MichiSpacing.screenHorizontal, vertical = 4.dp)
                    .clip(MichiShapes.md)
                    .background(AeroGraphiteRaised)
                    .border(0.65.dp, AeroHairline, MichiShapes.md)
                    .testTag("mini_player_bar"),
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    // Unknown duration is not 0% completion. Hide the timeline
                    // instead of rendering a misleading empty progress bar.
                    if (sessionState.duration > 0) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(2.dp)
                                .background(Color(0x22FFFFFF)),
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth(fraction = progressFraction)
                                    .height(2.dp)
                                    .background(PrimaryPinkContainer),
                            )
                        }
                    }

                    // Player row content
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // One coherent opening target, separate from actual transport buttons.
                        Row(
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = MichiSpacing.minTouchTarget)
                                .clip(MichiShapes.sm)
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = ripple(color = PrimaryPinkContainer),
                                    role = Role.Button,
                                    onClick = onClick,
                                )
                                .semantics(mergeDescendants = true) {
                                    contentDescription = "Abrir reproductor: ${currentTrack.title}, ${currentTrack.artist}"
                                },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            // Album artwork thumbnail
                            AlbumArtView(
                                coverStyle = coverStyleFor(currentTrack.coverId.ifEmpty { currentTrack.title }),
                                imageModel = currentTrack.coverId.takeIf { it.isNotBlank() },
                                fallbackMediaUri = currentTrack.filepath,
                                modifier = Modifier
                                    .size(42.dp)
                                    .clip(MichiShapes.xs),
                            )

                            Spacer(modifier = Modifier.width(12.dp))

                            // Title, Artist & Real Active Endpoint
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = currentTrack.title,
                                    color = PureWhite,
                                    style = MichiTypography.trackTitle,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = currentTrack.artist,
                                        color = OnSurfaceVariant,
                                        style = MichiTypography.metadata,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f, fill = false),
                                    )
                                    Spacer(modifier = Modifier.width(5.dp))
                                    Box(
                                        modifier = Modifier
                                            .size(5.dp)
                                            .clip(CircleShape)
                                            .background(if (activeEndpoint.isLocal) TertiaryCyan else PrimaryPinkContainer),
                                    )
                                    Spacer(modifier = Modifier.width(5.dp))
                                    Text(
                                        text = activeEndpoint.name,
                                        color = OnSurfaceVariant,
                                        style = MichiTypography.microLabel,
                                        modifier = Modifier.widthIn(max = 84.dp),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }

                        }
                        Spacer(modifier = Modifier.width(6.dp))

                        // Play / Pause Button
                        IconButton(
                            onClick = { sessionManager.playPause() },
                            modifier = Modifier
                                .size(MichiSpacing.minTouchTarget)
                                .testTag("mini_player_play_pause"),
                        ) {
                            Icon(
                                imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                                contentDescription = if (isPlaying) "Pausar" else "Reproducir",
                                tint = PureWhite,
                                modifier = Modifier.size(26.dp),
                            )
                        }

                        // Next Track Button
                        IconButton(
                            onClick = { sessionManager.skipNext() },
                            modifier = Modifier
                                .size(MichiSpacing.minTouchTarget)
                                .testTag("mini_player_next"),
                        ) {
                            Icon(
                                imageVector = Icons.Filled.SkipNext,
                                contentDescription = "Siguiente Canción",
                                tint = PureWhite,
                                modifier = Modifier.size(22.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}
