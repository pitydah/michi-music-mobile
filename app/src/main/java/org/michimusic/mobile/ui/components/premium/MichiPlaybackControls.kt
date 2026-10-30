package org.michimusic.mobile.ui.components.premium

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.michimusic.mobile.ui.theme.AeroGraphiteRaised
import org.michimusic.mobile.ui.theme.AeroHairline
import org.michimusic.mobile.ui.theme.LocalVisualPreferences
import org.michimusic.mobile.ui.theme.MichiSpacing
import org.michimusic.mobile.ui.theme.OnSurfaceVariant
import org.michimusic.mobile.ui.theme.PureWhite
import org.michimusic.mobile.ui.theme.SurfaceObsidian
import org.michimusic.mobile.ui.theme.TertiaryCyan

/**
 * Standardized premium playback control bar.
 *
 * Rules:
 * - Pure white solid icons by default without artificial circular background pills on skip/repeat/shuffle.
 * - Central play/pause button has prominent scale and optional pulse (subdued under reducedMotion).
 * - Minimum touch target >= 48dp on all action buttons.
 * - Stateless composable receiving [PlaybackControlUiState] and emitting intent callbacks.
 */
@Composable
fun MichiPlaybackControls(
    state: PlaybackControlUiState,
    onPlayPause: () -> Unit,
    onSkipPrevious: () -> Unit,
    onSkipNext: () -> Unit,
    modifier: Modifier = Modifier,
    onToggleShuffle: (() -> Unit)? = null,
    onCycleRepeat: (() -> Unit)? = null,
    playButtonSize: Dp = 68.dp,
    secondaryButtonSize: Dp = MichiSpacing.minTouchTarget,
    playPauseTestTag: String = "controls_play_pause",
    prevTestTag: String = "controls_prev",
    nextTestTag: String = "controls_next",
    shuffleTestTag: String = "controls_shuffle",
    repeatTestTag: String = "controls_repeat",
) {
    val reducedMotion = LocalVisualPreferences.current.reducedMotion

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Optional Shuffle
        if (onToggleShuffle != null) {
            MichiIconAction(
                icon = Icons.Filled.Shuffle,
                contentDescription = state.shuffleContentDescription(),
                onClick = onToggleShuffle,
                tint = if (state.isShuffleEnabled) TertiaryCyan else OnSurfaceVariant,
                iconSize = 22.dp,
                minTargetSize = secondaryButtonSize,
                testTag = shuffleTestTag,
            )
        }

        // Previous
        MichiIconAction(
            icon = Icons.Filled.SkipPrevious,
            contentDescription = "Pista anterior",
            onClick = onSkipPrevious,
            enabled = state.canSkipPrevious,
            tint = PureWhite,
            iconSize = 30.dp,
            minTargetSize = secondaryButtonSize,
            testTag = prevTestTag,
        )

        // Primary Play / Pause
        val scale by animateFloatAsState(
            targetValue = if (state.isPlaying && !reducedMotion) 1.02f else 1.0f,
            animationSpec = if (reducedMotion) {
                androidx.compose.animation.core.snap()
            } else {
                spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMedium)
            },
            label = "play_button_scale",
        )

        val playInteractionSource = remember { MutableInteractionSource() }
        val isPlayPressed by playInteractionSource.collectIsPressedAsState()
        val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current

        val pressedScale by animateFloatAsState(
            targetValue = if (isPlayPressed && !reducedMotion) 0.92f else 1.0f,
            animationSpec = tween(durationMillis = 120),
            label = "play_pressed_scale",
        )
        val pressedAlpha by animateFloatAsState(
            targetValue = if (isPlayPressed) 0.65f else 1.0f,
            animationSpec = tween(durationMillis = 120),
            label = "play_pressed_alpha",
        )

        Box(
            modifier = Modifier
                .size(playButtonSize.coerceAtLeast(MichiSpacing.minTouchTarget))
                .scale(scale * pressedScale)
                .clickable(
                    onClick = {
                        haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)
                        onPlayPause()
                    },
                    enabled = !state.isLoading,
                    role = Role.Button,
                    interactionSource = playInteractionSource,
                    indication = null, // Tactile scale and opacity replaces circular ripple disks
                )
                .semantics { contentDescription = state.playPauseContentDescription() }
                .testTag(playPauseTestTag),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier.graphicsLayer { alpha = pressedAlpha },
                contentAlignment = Alignment.Center,
            ) {
                if (state.isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(36.dp),
                        color = PureWhite,
                        strokeWidth = 3.dp,
                    )
                } else {
                    Icon(
                        imageVector = if (state.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = null,
                        tint = PureWhite,
                        modifier = Modifier.size(42.dp),
                    )
                }
            }
        }

        // Next
        MichiIconAction(
            icon = Icons.Filled.SkipNext,
            contentDescription = "Siguiente pista",
            onClick = onSkipNext,
            enabled = state.canSkipNext,
            tint = PureWhite,
            iconSize = 30.dp,
            minTargetSize = secondaryButtonSize,
            testTag = nextTestTag,
        )

        // Optional Repeat
        if (onCycleRepeat != null) {
            val repeatIcon = if (state.repeatMode == 1) Icons.Filled.RepeatOne else Icons.Filled.Repeat
            MichiIconAction(
                icon = repeatIcon,
                contentDescription = state.repeatContentDescription(),
                onClick = onCycleRepeat,
                tint = if (state.repeatMode != 0) TertiaryCyan else OnSurfaceVariant,
                iconSize = 22.dp,
                minTargetSize = secondaryButtonSize,
                testTag = repeatTestTag,
            )
        }
    }
}
