package org.michimusic.mobile.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.sin
import org.michimusic.mobile.ui.theme.AeroSeekerStyle
import org.michimusic.mobile.ui.theme.MichiAuroraBlue
import org.michimusic.mobile.ui.theme.MichiAuroraCyan
import org.michimusic.mobile.ui.theme.MichiAuroraLavender
import org.michimusic.mobile.ui.theme.aeroWavyMotionAllowed

/**
 * Draw-only treatment: the parent GradientProgressBar retains its sole semantic
 * and gesture layer. Never attach pointerInput or a second testTag here.
 * External height is owned by the parent (32 dp), even with large font scale.
 */
@Composable
internal fun AeroSeekerTrack(
    style: AeroSeekerStyle,
    progress: Float,
    playing: Boolean,
    reducedMotion: Boolean,
    dragging: Boolean,
    amplitudes: List<Float>?,
    modifier: Modifier,
) {
    val phase = remember { Animatable(0f) }
    LaunchedEffect(style, playing, reducedMotion, dragging) {
        phase.snapTo(0f)
        if (aeroWavyMotionAllowed(style, playing, reducedMotion, dragging)) {
            phase.animateTo(
                1f,
                infiniteRepeatable(animation = tween(1600, easing = LinearEasing), repeatMode = RepeatMode.Restart),
            )
        }
    }
    val fraction = progress.coerceIn(0f, 1f)
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        if (w <= 0f || h <= 0f) return@Canvas
        val y = h / 2f
        val playedWidth = w * fraction
        val hairline = 2.dp.toPx()
        val quiet = Color.White.copy(alpha = 0.24f)
        val active = Brush.horizontalGradient(listOf(MichiAuroraBlue, MichiAuroraCyan, MichiAuroraLavender))
        when (style) {
            AeroSeekerStyle.WAVY -> {
                val path = Path()
                val amplitude = if (dragging) 0f else 2.7.dp.toPx()
                val period = 42.dp.toPx().coerceAtLeast(1f)
                var x = 0f
                while (x <= w) {
                    val py = y + amplitude * sin((x / period + phase.value) * (2.0 * PI)).toFloat()
                    if (x == 0f) path.moveTo(0f, py) else path.lineTo(x, py)
                    x += 2.dp.toPx().coerceAtLeast(1f)
                }
                path.lineTo(w, y + amplitude * sin((w / period + phase.value) * (2.0 * PI)).toFloat())
                drawPath(path, quiet, style = Stroke(width = hairline, cap = StrokeCap.Round))
                clipRect(right = playedWidth) {
                    drawPath(path, active, style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round))
                }
            }
            AeroSeekerStyle.WAVEFORM_BARS -> {
                val a = amplitudes.orEmpty()
                val cell = w / a.size.coerceAtLeast(1)
                a.forEachIndexed { i, v ->
                    val x = (i + 0.5f) * cell
                    val halfHeight = (1.5.dp.toPx() + v * 9.dp.toPx()).coerceAtMost(h * 0.43f)
                    drawLine(if (x <= playedWidth) MichiAuroraCyan else quiet,
                        Offset(x, y - halfHeight), Offset(x, y + halfHeight),
                        strokeWidth = (cell * 0.60f).coerceIn(1.dp.toPx(), 3.dp.toPx()), cap = StrokeCap.Round)
                }
            }
            AeroSeekerStyle.WAVEFORM_CONTINUOUS -> {
                val a = amplitudes.orEmpty()
                if (a.isNotEmpty()) {
                    val path = Path()
                    val step = w / (a.size - 1).coerceAtLeast(1)
                    a.forEachIndexed { i, v ->
                        val x = i * step
                        val dy = v * 9.dp.toPx()
                        if (i == 0) path.moveTo(x, y - dy) else path.lineTo(x, y - dy)
                    }
                    for (i in a.lastIndex downTo 0) path.lineTo(i * step, y + a[i] * 9.dp.toPx())
                    path.close()
                    drawPath(path, quiet)
                    clipRect(right = playedWidth) { drawPath(path, active) }
                }
            }
            AeroSeekerStyle.LIQUID_GLASS -> {
                // No fake backdrop blur. A contained, low-cost specular lens is the API-safe fallback.
                val inset = 1.dp.toPx()
                val top = y - 4.dp.toPx()
                drawRoundRect(Color.White.copy(alpha = 0.12f), Offset(0f, top), Size(w, 8.dp.toPx()),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(4.dp.toPx()))
                drawRoundRect(brush = active, topLeft = Offset(0f, top),
                    size = Size(playedWidth.coerceAtLeast(0f), 8.dp.toPx()),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(4.dp.toPx()))
                if (playedWidth > 2 * inset) drawLine(Color.White.copy(alpha = 0.50f),
                    Offset(inset, top + inset), Offset(playedWidth - inset, top + inset),
                    strokeWidth = 0.75.dp.toPx())
            }
            AeroSeekerStyle.ORIGINAL -> Unit // Original uses the unmodified legacy implementation.
        }
    }
}
