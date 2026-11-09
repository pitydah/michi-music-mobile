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
                // Always a recognizable sine. Paused, reduced-motion and dragging
                // runs simply keep phase at zero instead of flattening the control.
                val path = Path()
                val amplitude = 2.7.dp.toPx()
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
                // Draw-only optical rail: glass casing, recessed channel and one
                // specular line. No backdrop capture, blur, refraction or extra control.
                val railHeight = 12.dp.toPx()
                val railTop = y - railHeight / 2f
                val railRadius = androidx.compose.ui.geometry.CornerRadius(railHeight / 2f)
                val inset = 2.dp.toPx()
                val innerHeight = 5.dp.toPx()
                val innerTop = y - innerHeight / 2f
                val innerRadius = androidx.compose.ui.geometry.CornerRadius(innerHeight / 2f)
                val innerWidth = (w - inset * 2f).coerceAtLeast(1f)
                val playedInner = (playedWidth - inset).coerceIn(0f, innerWidth)
                drawRoundRect(
                    Color.White.copy(alpha = 0.055f),
                    Offset(0f, railTop), Size(w, railHeight), cornerRadius = railRadius,
                )
                drawRoundRect(
                    Color.White.copy(alpha = 0.18f),
                    Offset(0f, railTop), Size(w, railHeight), cornerRadius = railRadius,
                    style = Stroke(width = 0.75.dp.toPx()),
                )
                drawRoundRect(
                    Color.Black.copy(alpha = 0.30f),
                    Offset(inset, innerTop), Size(innerWidth, innerHeight), cornerRadius = innerRadius,
                )
                drawRoundRect(
                    Color.White.copy(alpha = 0.10f),
                    Offset(inset, innerTop), Size(innerWidth, innerHeight), cornerRadius = innerRadius,
                    style = Stroke(width = 0.5.dp.toPx()),
                )
                if (playedInner > 0f) {
                    drawRoundRect(
                        brush = active,
                        topLeft = Offset(inset, innerTop),
                        size = Size(playedInner, innerHeight),
                        cornerRadius = innerRadius,
                    )
                }
                if (w > inset * 3f) {
                    drawLine(
                        color = Color.White.copy(alpha = 0.55f),
                        start = Offset(inset * 1.5f, railTop + 0.9.dp.toPx()),
                        end = Offset(w - inset * 1.5f, railTop + 0.9.dp.toPx()),
                        strokeWidth = 0.75.dp.toPx(),
                        cap = StrokeCap.Round,
                    )
                }
                // A small optical bead marks the playhead; it is not fake refraction.
                if (playedInner > 0f) {
                    drawCircle(
                        color = Color.White.copy(alpha = 0.38f),
                        radius = 1.4.dp.toPx(),
                        center = Offset(playedWidth, y),
                    )
                }
            }
            AeroSeekerStyle.ORIGINAL -> Unit // Original uses the unmodified legacy implementation.
        }
    }
}
