package org.michimusic.mobile.ui.components.premium

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.michimusic.mobile.ui.theme.LocalVisualPreferences
import org.michimusic.mobile.ui.theme.MichiSpacing
import org.michimusic.mobile.ui.theme.OnSurfaceVariant
import org.michimusic.mobile.ui.theme.PureWhite
import org.michimusic.mobile.ui.theme.TertiaryCyan

/**
 * M60-R2 transport layout. Five invisible, symmetric slots on normal widths:
 * repeat | previous | PLAY / PAUSE | next | shuffle.
 * The previous and next glyphs are explicitly mirrored on the same canvas grid.
 * Play/pause is locked to the exact screen centre regardless of optional actions.
 * Very narrow widths move the two secondary actions below the primary trio,
 * rather than allowing touch targets to overlap.
 *
 * No circle, halo, gradient, backdrop, painted ripple or animated pulse is used.
 */
private const val PLAY_SLOT_WEIGHT = 1.35f
private const val COMPACT_SECONDARY_THRESHOLD_DP = 280f

/** Exposes the geometry for deterministic JVM tests without a Compose runtime. */
data class AeroTransportCenters(val previous: Float, val playPause: Float, val next: Float)

fun aeroTransportCenters(availableWidthDp: Float): AeroTransportCenters {
    require(availableWidthDp.isFinite() && availableWidthDp >= 0f)
    val center = availableWidthDp / 2f
    return if (availableWidthDp < COMPACT_SECONDARY_THRESHOLD_DP) {
        val step = availableWidthDp / 3f
        AeroTransportCenters(step / 2f, center, availableWidthDp - step / 2f)
    } else {
        val unit = availableWidthDp / (4f + PLAY_SLOT_WEIGHT)
        val distance = unit * (0.5f + PLAY_SLOT_WEIGHT / 2f)
        AeroTransportCenters(center - distance, center, center + distance)
    }
}

private enum class TransportSymbol { Previous, Next, Play, Pause }

/** Original vector geometry, drawn as physical pixels from one normalized grid. */
@Composable
private fun AeroTransportGlyph(
    symbol: TransportSymbol,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        if (w <= 0f || h <= 0f) return@Canvas
        when (symbol) {
            TransportSymbol.Previous, TransportSymbol.Next -> {
                val prev = symbol == TransportSymbol.Previous
                // The two skip symbols have exactly the same extents, mirrored.
                val stemX = if (prev) w * .16f else w * .765f
                drawRoundRect(
                    color = color,
                    topLeft = Offset(stemX, h * .17f),
                    size = Size(w * .075f, h * .66f),
                    cornerRadius = CornerRadius(w * .023f),
                )
                val triangle = Path().apply {
                    if (prev) {
                        moveTo(w * .715f, h * .17f)
                        lineTo(w * .285f, h * .5f)
                        lineTo(w * .715f, h * .83f)
                    } else {
                        moveTo(w * .285f, h * .17f)
                        lineTo(w * .715f, h * .5f)
                        lineTo(w * .285f, h * .83f)
                    }
                    close()
                }
                drawPath(triangle, color)
            }
            TransportSymbol.Pause -> {
                // Two identical slim vertical bars, optically centred.
                val barW = w * .125f
                val top = h * .105f
                val barH = h * .79f
                val radius = CornerRadius(barW * .48f)
                drawRoundRect(color, Offset(w * .2825f, top), Size(barW, barH), radius)
                drawRoundRect(color, Offset(w * .5925f, top), Size(barW, barH), radius)
            }
            TransportSymbol.Play -> {
                // Optical rather than bounding-box centring for the play triangle.
                val triangle = Path().apply {
                    moveTo(w * .235f, h * .13f)
                    lineTo(w * .795f, h * .5f)
                    lineTo(w * .235f, h * .87f)
                    close()
                }
                drawPath(triangle, color)
            }
        }
    }
}

@Composable
private fun AeroSkipAction(
    previous: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    testTag: String,
    hitSize: Dp,
) {
    val preferences = LocalVisualPreferences.current
    val interactions = remember { MutableInteractionSource() }
    val pressed by interactions.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (!preferences.reducedMotion && pressed) .94f else 1f,
        animationSpec = if (preferences.reducedMotion) snap() else tween(100),
        label = if (previous) "skip_previous_press" else "skip_next_press",
    )
    val description = if (previous) "Pista anterior" else "Siguiente pista"
    Box(
        modifier = Modifier.size(hitSize.coerceAtLeast(MichiSpacing.minTouchTarget))
            .clickable(
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
                interactionSource = interactions,
                indication = null,
            )
            .semantics { contentDescription = description }
            .testTag(testTag),
        contentAlignment = Alignment.Center,
    ) {
        AeroTransportGlyph(
            symbol = if (previous) TransportSymbol.Previous else TransportSymbol.Next,
            color = if (enabled) PureWhite else OnSurfaceVariant.copy(alpha = .5f),
            modifier = Modifier.size(32.dp).graphicsLayer { scaleX = scale; scaleY = scale },
        )
    }
}

@Composable
private fun AeroPlayPauseAction(
    state: PlaybackControlUiState,
    onClick: () -> Unit,
    testTag: String,
    hitSize: Dp,
) {
    val preferences = LocalVisualPreferences.current
    val haptic = LocalHapticFeedback.current
    val interactions = remember { MutableInteractionSource() }
    val pressed by interactions.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (!preferences.reducedMotion && pressed) .95f else 1f,
        animationSpec = if (preferences.reducedMotion) snap() else tween(110),
        label = "aero_transport_play_press",
    )
    val canControl = !state.isLoading && state.canPlayPause
    Box(
        modifier = Modifier.size(hitSize.coerceAtLeast(MichiSpacing.minTouchTarget))
            .clickable(
                enabled = canControl,
                role = Role.Button,
                interactionSource = interactions,
                indication = null,
                onClick = {
                    if (preferences.hapticsEnabled) haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onClick()
                },
            )
            .semantics { contentDescription = state.playPauseContentDescription() }
            .testTag(testTag),
        contentAlignment = Alignment.Center,
    ) {
        if (state.isLoading) {
            CircularProgressIndicator(
                modifier = Modifier.size(32.dp),
                color = PureWhite,
                strokeWidth = 2.5.dp,
            )
        } else {
            Crossfade(
                targetState = state.isPlaying,
                animationSpec = if (preferences.reducedMotion) snap() else tween(150),
                label = "aero_transport_play_pause_glyph",
            ) { playing ->
                AeroTransportGlyph(
                    symbol = if (playing) TransportSymbol.Pause else TransportSymbol.Play,
                    color = if (canControl) PureWhite else OnSurfaceVariant.copy(alpha = .5f),
                    modifier = Modifier.size(48.dp).graphicsLayer { scaleX = scale; scaleY = scale },
                )
            }
        }
    }
}

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
    val sideHit = secondaryButtonSize.coerceAtLeast(MichiSpacing.minTouchTarget)
    val playHit = playButtonSize.coerceAtLeast(MichiSpacing.minTouchTarget)
    val shuffleControl: @Composable () -> Unit = {
        if (onToggleShuffle != null) {
            MichiIconAction(
                icon = Icons.Filled.Shuffle,
                contentDescription = state.shuffleContentDescription(),
                onClick = onToggleShuffle,
                tint = if (state.isShuffleEnabled) TertiaryCyan else OnSurfaceVariant,
                iconSize = 21.dp,
                minTargetSize = sideHit,
                testTag = shuffleTestTag,
            )
        }
    }
    val repeatControl: @Composable () -> Unit = {
        if (onCycleRepeat != null) {
            MichiIconAction(
                icon = if (state.repeatMode == 1) Icons.Filled.RepeatOne else Icons.Filled.Repeat,
                contentDescription = state.repeatContentDescription(),
                onClick = onCycleRepeat,
                tint = if (state.repeatMode != 0) TertiaryCyan else OnSurfaceVariant,
                iconSize = 21.dp,
                minTargetSize = sideHit,
                testTag = repeatTestTag,
            )
        }
    }
    BoxWithConstraints(modifier = modifier.fillMaxWidth().testTag("aero_transport_symmetric")) {
        val compact = maxWidth < COMPACT_SECONDARY_THRESHOLD_DP.dp
        val sideSlot = if (compact) maxWidth / 3f else maxWidth / (4f + PLAY_SLOT_WEIGHT)
        val actualSideHit = sideHit.coerceAtMost(sideSlot).coerceAtLeast(MichiSpacing.minTouchTarget)
        val actualPlayHit = playHit
            .coerceAtMost(if (compact) sideSlot else sideSlot * PLAY_SLOT_WEIGHT)
            .coerceAtLeast(MichiSpacing.minTouchTarget)
        if (compact) {
            // Preserve the main optical triangle and all five controls on 240-279dp.
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        AeroSkipAction(true, state.canSkipPrevious, onSkipPrevious, prevTestTag, actualSideHit)
                    }
                    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        AeroPlayPauseAction(state, onPlayPause, playPauseTestTag, actualPlayHit)
                    }
                    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        AeroSkipAction(false, state.canSkipNext, onSkipNext, nextTestTag, actualSideHit)
                    }
                }
                if (onToggleShuffle != null || onCycleRepeat != null) {
                    Row(
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        repeatControl()
                        shuffleControl()
                    }
                }
            }
        } else {
            // Equal side slots + a centred wider play slot: left and right mirror.
            Row(
                modifier = Modifier.fillMaxWidth().heightIn(min = 76.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { repeatControl() }
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    AeroSkipAction(true, state.canSkipPrevious, onSkipPrevious, prevTestTag, actualSideHit)
                }
                Box(Modifier.weight(PLAY_SLOT_WEIGHT), contentAlignment = Alignment.Center) {
                    AeroPlayPauseAction(state, onPlayPause, playPauseTestTag, actualPlayHit)
                }
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    AeroSkipAction(false, state.canSkipNext, onSkipNext, nextTestTag, actualSideHit)
                }
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { shuffleControl() }
            }
        }
    }
}
