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
import org.michimusic.mobile.ui.components.premium.MichiIconAction
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
import org.michimusic.mobile.playback.ActivePlaybackPresentation
import org.michimusic.mobile.playback.PlaybackSessionManager
import org.michimusic.mobile.playback.resolvePlaybackPresentation
import org.michimusic.mobile.ui.motion.AeroTrackArtworkMotion
import org.michimusic.mobile.ui.motion.AeroTrackTitleMotion
import org.michimusic.mobile.ui.motion.aeroNowPlayingTransitionPolicy
import org.michimusic.mobile.ui.theme.AeroGraphiteRaised
import org.michimusic.mobile.ui.theme.AeroHairline
import org.michimusic.mobile.ui.theme.AeroFinish
import org.michimusic.mobile.ui.theme.AeroSurfaceRole
import org.michimusic.mobile.ui.theme.MichiShapes
import org.michimusic.mobile.ui.theme.MichiSpacing
import org.michimusic.mobile.ui.theme.MichiTypography
import org.michimusic.mobile.ui.theme.OnSurfaceVariant
import androidx.compose.ui.graphics.Brush
import org.michimusic.mobile.ui.theme.PrimaryPinkContainer
import org.michimusic.mobile.ui.theme.PureWhite
import org.michimusic.mobile.ui.theme.TertiaryCyan
import org.michimusic.player.AudioController

/**
 * Wiring owner: resolves the playback authorities and forwards intents.
 * Every visual decision belongs to [MiniPlayerContent], which can be rendered
 * from a fixed state without a dependency-injection graph.
 */
@Composable
fun MiniPlayer(
    modifier: Modifier = Modifier,
    onClick: () -> Unit = {},
    visible: Boolean = true,
) {
    val sessionManager: PlaybackSessionManager = koinInject()
    val sessionState by sessionManager.sessionState.collectAsStateWithLifecycle()
    val audioController: AudioController = koinInject()
    val localState by audioController.state.collectAsStateWithLifecycle()

    MiniPlayerContent(
        presentation = resolvePlaybackPresentation(sessionState, localState),
        modifier = modifier,
        visible = visible,
        onClick = onClick,
        onPlayPause = { sessionManager.playPause() },
        onNext = { sessionManager.skipNext() },
    )
}

/**
 * Plain, caller-drivable mini player: immutable presentation plus intents, so
 * its labels, accessibility descriptions and callbacks are testable without
 * Koin, services or a running playback engine.
 */
@Composable
fun MiniPlayerContent(
    presentation: ActivePlaybackPresentation,
    modifier: Modifier = Modifier,
    visible: Boolean = true,
    onClick: () -> Unit = {},
    onPlayPause: () -> Unit = {},
    onNext: () -> Unit = {},
) {
    val currentTrack = presentation.currentTrack
    val activeEndpoint = presentation.endpoint
    val isPlaying = presentation.isPlaying
    val progressFraction = presentation.progressFraction
    val visualPreferences = org.michimusic.mobile.ui.theme.LocalVisualPreferences.current
    val transitionPolicy = aeroNowPlayingTransitionPolicy(
        reducedMotion = visualPreferences.reducedMotion,
        highContrast = visualPreferences.highContrast,
    )

    AnimatedVisibility(
        visible = visible && currentTrack != null,
        enter = if (!transitionPolicy.animateMiniPlayerVisibility) EnterTransition.None else slideInVertically(initialOffsetY = { it }) + fadeIn(),
        exit = if (!transitionPolicy.animateMiniPlayerVisibility) ExitTransition.None else slideOutVertically(targetOffsetY = { it }) + fadeOut(),
        modifier = modifier,
    ) {
        if (currentTrack != null) {
            AeroSurface(
                role = AeroSurfaceRole.Floating,
                finish = AeroFinish.Smoked,
                shape = MichiShapes.md,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = MichiSpacing.screenHorizontal, vertical = 4.dp)
                    .testTag("mini_player_bar"),
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    // Unknown duration is not 0% completion. Hide the timeline
                    // instead of rendering a misleading empty progress bar.
                    if (presentation.duration > 0) {
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
                            AeroTrackArtworkMotion(
                                track = currentTrack,
                                modifier = Modifier.size(42.dp)
                                    .clip(MichiShapes.xs),
                                cornerRadius = 8.dp,
                            )

                            Spacer(modifier = Modifier.width(12.dp))

                            // Title, Artist & Real Active Endpoint
                            Column(modifier = Modifier.weight(1f)) {
                                AeroTrackTitleMotion(
                                    trackId = currentTrack.id,
                                    title = currentTrack.title,
                                    color = PureWhite,
                                    style = MichiTypography.trackTitle,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = currentTrack.artist,
                                        color = OnSurfaceVariant,
                                        style = MichiTypography.metadata,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier
                                            .weight(1f)
                                            .testTag("mini_player_artist"),
                                    )
                                    Spacer(modifier = Modifier.width(5.dp))
                                    Box(
                                        modifier = Modifier
                                            .size(5.dp)
                                            .clip(CircleShape)
                                            .background(if (activeEndpoint.isLocal) TertiaryCyan else PrimaryPinkContainer)
                                            .testTag("mini_player_endpoint_status"),
                                    )
                                    Spacer(modifier = Modifier.width(5.dp))
                                    Text(
                                        text = activeEndpoint.name,
                                        color = OnSurfaceVariant,
                                        style = MichiTypography.microLabel,
                                        modifier = Modifier
                                            .widthIn(max = 84.dp)
                                            .testTag("mini_player_endpoint"),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }

                        }
                        Spacer(modifier = Modifier.width(6.dp))

                        // Aero v1.3: icon-only transport; the 48dp hitbox stays invisible.
                        MichiIconAction(
                            icon = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            contentDescription = if (isPlaying) "Pausar" else "Reproducir",
                            onClick = onPlayPause,
                            tint = PureWhite,
                            iconSize = 26.dp,
                            minTargetSize = MichiSpacing.minTouchTarget,
                            testTag = "mini_player_play_pause",
                        )

                        // Next Track Button
                        MichiIconAction(
                            icon = Icons.Filled.SkipNext,
                            contentDescription = "Siguiente Canción",
                            onClick = onNext,
                            tint = PureWhite,
                            iconSize = 22.dp,
                            minTargetSize = MichiSpacing.minTouchTarget,
                            testTag = "mini_player_next",
                        )
                    }
                }
            }
        }
    }
}
