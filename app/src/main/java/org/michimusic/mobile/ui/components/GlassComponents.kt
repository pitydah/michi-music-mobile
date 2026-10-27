package org.michimusic.mobile.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.Role
import org.michimusic.mobile.ui.theme.LocalVisualPreferences
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.michimusic.mobile.ui.theme.AeroGraphite
import org.michimusic.mobile.ui.theme.AeroGraphiteRaised
import org.michimusic.mobile.ui.theme.AeroHairline
import org.michimusic.mobile.ui.theme.MichiShapes
import org.michimusic.mobile.ui.theme.AeroSurfaceRole
import org.michimusic.mobile.ui.theme.aeroMaterialStyle
import org.michimusic.mobile.ui.theme.AeroSheen
import org.michimusic.mobile.ui.theme.AeroTrack
import org.michimusic.mobile.ui.theme.GlassBorderHigh
import org.michimusic.mobile.ui.theme.GlassBorderLow
import org.michimusic.mobile.ui.theme.GlassFillHigh
import org.michimusic.mobile.ui.theme.GlassFillLow
import org.michimusic.mobile.ui.theme.PrimaryPink
import org.michimusic.mobile.ui.theme.PrimaryPinkContainer
import org.michimusic.mobile.ui.theme.PureWhite
import org.michimusic.mobile.ui.theme.SecondaryPurple
import org.michimusic.mobile.ui.theme.TertiaryCyan
import org.michimusic.mobile.ui.theme.TertiaryCyanContainer

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding

fun formatTimeSeconds(seconds: Float): String = mediaElapsedLabel(seconds)

fun formatTimeMillis(millis: Long): String = mediaDurationLabel(millis)

fun formatRemainingTimeSeconds(current: Float, total: Float): String =
    mediaRemainingLabel(current, total)

@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    shape: Shape = MichiShapes.md,
    backgroundColor: Color = GlassFillLow,
    borderColor: Color = GlassBorderLow,
    glowColor: Color? = null,
    onClick: (() -> Unit)? = null,
    contentPadding: Dp? = null,
    accent: Color? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    val highContrast = LocalVisualPreferences.current.highContrast
    val role = if (backgroundColor == GlassFillHigh) AeroSurfaceRole.Floating else AeroSurfaceRole.Raised
    val material = aeroMaterialStyle(role, highContrast)
    val resolvedFill = when {
        highContrast -> material.fill
        backgroundColor == GlassFillLow || backgroundColor == GlassFillHigh -> material.fill
        else -> backgroundColor // Preserve an explicitly requested semantic surface.
    }
    val resolvedBorder = when {
        highContrast -> material.outline
        glowColor != null -> glowColor.copy(alpha = 0.25f)
        accent != null -> accent.copy(alpha = 0.25f)
        borderColor == GlassBorderLow || borderColor == GlassBorderHigh -> material.outline
        else -> borderColor
    }
    val baseModifier = modifier
        .then(if (material.elevation > 0.dp) Modifier.shadow(material.elevation, shape) else Modifier)
        .clip(shape)
        .background(resolvedFill)
        .then(if (material.borderWidth > 0.dp) Modifier.border(material.borderWidth, resolvedBorder, shape) else Modifier)
        .then(
            if (onClick != null) Modifier.clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(color = PrimaryPinkContainer),
                role = Role.Button,
                onClick = onClick,
            ) else Modifier
        )
        .then(if (contentPadding != null) Modifier.padding(contentPadding) else Modifier)

    Box(modifier = baseModifier) { content() }
}

@Composable
fun GlassOverlayCard(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(16.dp),
    content: @Composable BoxScope.() -> Unit,
) {
    GlassCard(
        modifier = modifier,
        shape = shape,
        backgroundColor = GlassFillHigh,
        borderColor = GlassBorderHigh,
        content = content,
    )
}

@Composable
fun PulsingDot(
    modifier: Modifier = Modifier,
    color: Color = TertiaryCyan,
    size: Dp = 8.dp,
) {
    val reducedMotion = org.michimusic.mobile.ui.theme.LocalVisualPreferences.current.reducedMotion
    if (LocalVisualPreferences.current.reducedMotion) {
        Box(modifier = modifier.size(size * 2), contentAlignment = Alignment.Center) {
            Box(Modifier.size(size).background(color, CircleShape))
        }
        return
    }
    val transition = rememberInfiniteTransition(label = "pulse_dot")
    val scale by transition.animateFloat(
        initialValue = 0.85f,
        targetValue = 1.25f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "dot_scale",
    )
    val glowAlpha by transition.animateFloat(
        initialValue = 0.3f,
        targetValue = 0.85f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "dot_glow",
    )

    Box(
        modifier = modifier
            .size(size * 2)
            .drawBehind {
                drawCircle(
                    color = color.copy(alpha = if (reducedMotion) 0.25f else glowAlpha),
                    radius = (size.toPx() * if (reducedMotion) 0.85f else scale),
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(size)
                .background(color, CircleShape),
        )
    }
}

@Composable
fun PulsingRadarRing(
    modifier: Modifier = Modifier,
    color: Color = TertiaryCyan,
    size: Dp = 48.dp,
) {
    val reducedMotion = org.michimusic.mobile.ui.theme.LocalVisualPreferences.current.reducedMotion
    if (LocalVisualPreferences.current.reducedMotion) {
        Box(modifier = modifier.size(size), contentAlignment = Alignment.Center) {
            Box(Modifier.size(size * 0.75f).background(color.copy(alpha = 0.15f), CircleShape))
        }
        return
    }
    val transition = rememberInfiniteTransition(label = "radar_ring")
    val animatedRadius by transition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(1800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "radius",
    )
    val animatedAlpha by transition.animateFloat(
        initialValue = 0.7f,
        targetValue = 0.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(1800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "alpha",
    )

    Box(
        modifier = modifier
            .size(size)
            .drawBehind {
                drawCircle(
                    color = color.copy(alpha = if (reducedMotion) 0f else animatedAlpha),
                    radius = (size.toPx() / 2f) * if (reducedMotion) 0.75f else animatedRadius,
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(size * 0.75f)
                .background(color.copy(alpha = 0.15f), CircleShape),
        )
    }
}

@Composable
fun EqualizerWaveBars(
    modifier: Modifier = Modifier,
    isPlaying: Boolean = true,
    barCount: Int = 4,
    color: Color = TertiaryCyan,
) {
    val reducedMotion = org.michimusic.mobile.ui.theme.LocalVisualPreferences.current.reducedMotion
    if (!isPlaying || LocalVisualPreferences.current.reducedMotion) {
        Row(modifier = modifier.height(18.dp), verticalAlignment = Alignment.Bottom) {
            repeat(barCount.coerceIn(0, 16)) { i ->
                val fraction = if (!isPlaying) 0.25f else listOf(0.55f, 0.8f, 0.4f, 0.65f)[i % 4]
                Box(Modifier.width(2.5.dp).fillMaxHeight(fraction)
                    .clip(RoundedCornerShape(1.dp)).background(color))
                if (i < barCount - 1) Spacer(Modifier.width(2.dp))
            }
        }
        return
    }
    val transition = rememberInfiniteTransition(label = "eq_bars")
    val h1 by transition.animateFloat(
        initialValue = 0.3f, targetValue = 0.95f,
        animationSpec = infiniteRepeatable(tween(450, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "h1",
    )
    val h2 by transition.animateFloat(
        initialValue = 0.8f, targetValue = 0.35f,
        animationSpec = infiniteRepeatable(tween(550, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "h2",
    )
    val h3 by transition.animateFloat(
        initialValue = 0.4f, targetValue = 1.0f,
        animationSpec = infiniteRepeatable(tween(380, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "h3",
    )
    val h4 by transition.animateFloat(
        initialValue = 0.9f, targetValue = 0.45f,
        animationSpec = infiniteRepeatable(tween(620, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "h4",
    )

    val heights = listOf(h1, h2, h3, h4)

    Row(
        modifier = modifier.height(18.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        for (i in 0 until barCount) {
            val scale = if (isPlaying && !reducedMotion) heights[i % heights.size] else 0.25f
            Box(
                modifier = Modifier
                    .width(2.5.dp)
                    .fillMaxHeight(fraction = scale)
                    .clip(RoundedCornerShape(1.dp))
                    .background(color),
            )
            if (i < barCount - 1) {
                Spacer(modifier = Modifier.width(2.dp))
            }
        }
    }
}

@Composable
fun GradientProgressBar(
    modifier: Modifier = Modifier,
    currentValue: Float,
    maxValue: Float,
    onSeek: (Float) -> Unit,
    trackHeight: Dp = 5.dp,
    showThumb: Boolean = true,
    testTag: String = "progress_bar",
) {
    val fraction = seekProgressFraction(currentValue, maxValue)
    val progress = fraction ?: 0f
    val canSeek = fraction != null
    val reducedMotion = LocalVisualPreferences.current.reducedMotion
    var isDragging by remember { mutableStateOf(false) }
    val animatedThumbSize by animateDpAsState(
        targetValue = if (isDragging) 18.dp else 14.dp,
        animationSpec = if (reducedMotion) snap() else tween(150),
        label = "thumb_size",
    )
    val animatedTrackHeight by animateDpAsState(
        targetValue = if (isDragging) trackHeight + 2.dp else trackHeight,
        animationSpec = if (reducedMotion) snap() else tween(150),
        label = "track_height",
    )

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(32.dp)
            .testTag(testTag)
            .semantics {
                progressBarRangeInfo = if (canSeek) ProgressBarRangeInfo(progress, 0f..1f)
                else ProgressBarRangeInfo.Indeterminate
                if (canSeek) {
                    setProgress { selected ->
                        val target = seekPositionForFraction(selected, maxValue)
                        if (target != null) { onSeek(target); true } else false
                    }
                }
            }
            .then(if (canSeek) Modifier.pointerInput(maxValue) {
                detectTapGestures(
                    onPress = {
                        isDragging = true
                        try { awaitRelease() } finally { isDragging = false }
                    },
                    onTap = { offset ->
                        seekPositionForFraction(offset.x / size.width.toFloat(), maxValue)?.let(onSeek)
                    },
                )
            } else Modifier)
            .then(if (canSeek) Modifier.pointerInput(maxValue) {
                detectHorizontalDragGestures(
                    onDragStart = { isDragging = true },
                    onDragEnd = { isDragging = false },
                    onDragCancel = { isDragging = false },
                ) { change, _ ->
                    change.consume()
                    seekPositionForFraction(change.position.x / size.width.toFloat(), maxValue)?.let(onSeek)
                }
            } else Modifier),
        contentAlignment = Alignment.CenterStart,
    ) {
        val totalWidth = maxWidth

        // Background Track
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(animatedTrackHeight)
                .clip(CircleShape)
                .background(AeroTrack),
        )

        // Elapsed Progress Gradient
        Box(
            modifier = Modifier
                .width(totalWidth * progress)
                .height(animatedTrackHeight)
                .clip(CircleShape)
                .drawBehind {
                    drawRect(
                        brush = Brush.horizontalGradient(
                            colors = listOf(
                                PrimaryPinkContainer.copy(alpha = 0.78f),
                                PrimaryPinkContainer,
                            ),
                        ),
                    )
                },
        )

        // Scrubber Thumb
        if (showThumb && canSeek) {
            val halfThumb = animatedThumbSize / 2
            val thumbOffset = (totalWidth * progress) - halfThumb
            Box(
                modifier = Modifier
                    .offset(x = thumbOffset.coerceIn(0.dp, (totalWidth - animatedThumbSize).coerceAtLeast(0.dp)))
                    .size(animatedThumbSize)
                    .shadow(elevation = if (isDragging) 6.dp else 2.dp, shape = CircleShape)
                    .clip(CircleShape)
                    .background(PureWhite)
                    .border(
                        if (isDragging) 2.5.dp else 2.dp,
                        if (isDragging) PrimaryPinkContainer else AeroGraphite,
                        CircleShape,
                    ),
            )
        }
    }
}
