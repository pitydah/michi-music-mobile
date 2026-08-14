package org.michimusic.mobile.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
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

fun formatTimeSeconds(seconds: Float): String {
    val totalSecs = seconds.toInt().coerceAtLeast(0)
    val mins = totalSecs / 60
    val secs = totalSecs % 60
    return "$mins:${if (secs < 10) "0$secs" else "$secs"}"
}

fun formatTimeMillis(millis: Long): String {
    val totalSecs = (millis / 1000).toInt().coerceAtLeast(0)
    val mins = totalSecs / 60
    val secs = totalSecs % 60
    return "$mins:${if (secs < 10) "0$secs" else "$secs"}"
}

fun formatRemainingTimeSeconds(current: Float, total: Float): String {
    val remaining = (total - current).toInt().coerceAtLeast(0)
    val mins = remaining / 60
    val secs = remaining % 60
    return "-$mins:${if (secs < 10) "0$secs" else "$secs"}"
}

@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(14.dp),
    backgroundColor: Color = GlassFillLow,
    borderColor: Color = GlassBorderLow,
    glowColor: Color? = null,
    onClick: (() -> Unit)? = null,
    contentPadding: Dp? = null,
    accent: Color? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    val effectiveGlow = glowColor ?: accent
    val baseModifier = modifier
        .then(
            if (effectiveGlow != null) {
                Modifier.drawBehind {
                    drawCircle(
                        color = effectiveGlow.copy(alpha = 0.22f),
                        radius = size.maxDimension * 0.65f,
                        center = center,
                    )
                }
            } else {
                Modifier
            },
        )
        .clip(shape)
        .background(backgroundColor)
        .border(
            width = 1.dp,
            brush = Brush.verticalGradient(
                colors = listOf(
                    borderColor.copy(alpha = 0.35f),
                    borderColor.copy(alpha = 0.10f),
                ),
            ),
            shape = shape,
        )
        .then(
            if (contentPadding != null) {
                Modifier.padding(contentPadding)
            } else {
                Modifier
            }
        )

    val finalModifier = if (onClick != null) {
        baseModifier.clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = ripple(color = PrimaryPink),
            onClick = onClick,
        )
    } else {
        baseModifier
    }

    Box(modifier = finalModifier) {
        content()
    }
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
                    color = color.copy(alpha = glowAlpha),
                    radius = (size.toPx() * scale),
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
                    color = color.copy(alpha = animatedAlpha),
                    radius = (size.toPx() / 2f) * animatedRadius,
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
            val scale = if (isPlaying) heights[i % heights.size] else 0.25f
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
    val progress = if (maxValue > 0f) (currentValue / maxValue).coerceIn(0f, 1f) else 0f
    var isDragging by remember { mutableStateOf(false) }
    val animatedThumbSize by animateDpAsState(
        targetValue = if (isDragging) 18.dp else 14.dp,
        label = "thumb_size",
    )
    val animatedTrackHeight by animateDpAsState(
        targetValue = if (isDragging) trackHeight + 2.dp else trackHeight,
        label = "track_height",
    )

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(32.dp)
            .testTag(testTag)
            .pointerInput(maxValue) {
                detectTapGestures(
                    onPress = {
                        isDragging = true
                        try {
                            awaitRelease()
                        } finally {
                            isDragging = false
                        }
                    },
                    onTap = { offset ->
                        val newProgress = (offset.x / size.width.toFloat()).coerceIn(0f, 1f)
                        onSeek(newProgress * maxValue)
                    },
                )
            }
            .pointerInput(maxValue) {
                detectHorizontalDragGestures(
                    onDragStart = { isDragging = true },
                    onDragEnd = { isDragging = false },
                    onDragCancel = { isDragging = false },
                ) { change, _ ->
                    change.consume()
                    val newProgress = (change.position.x / size.width.toFloat()).coerceIn(0f, 1f)
                    onSeek(newProgress * maxValue)
                }
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        val totalWidth = maxWidth

        // Background Track
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(animatedTrackHeight)
                .clip(CircleShape)
                .background(Color(0x33FFFFFF)),
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
                                PrimaryPinkContainer,
                                SecondaryPurple,
                                TertiaryCyanContainer,
                            ),
                        ),
                    )
                },
        )

        // Scrubber Thumb
        if (showThumb && progress > 0f) {
            val halfThumb = animatedThumbSize / 2
            val thumbOffset = (totalWidth * progress) - halfThumb
            Box(
                modifier = Modifier
                    .offset(x = thumbOffset.coerceAtLeast(0.dp))
                    .size(animatedThumbSize)
                    .shadow(elevation = if (isDragging) 8.dp else 4.dp, shape = CircleShape)
                    .clip(CircleShape)
                    .background(PureWhite)
                    .border(
                        if (isDragging) 2.5.dp else 2.dp,
                        if (isDragging) TertiaryCyan else Color(0xFF090B11),
                        CircleShape,
                    ),
            )
        }
    }
}
