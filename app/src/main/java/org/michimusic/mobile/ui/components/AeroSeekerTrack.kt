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
import org.michimusic.mobile.ui.seeker.waveformBarsForWidth
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
                // A 320dp device must not draw all 256 bins as an unreadable hairline comb.
                // Deterministic peak reduction uses only the decoder's actual PCM envelope.
                val bins = waveformBarsForWidth(amplitudes.orEmpty(),
                    (w / 4.dp.toPx()).toInt().coerceAtLeast(1))
                if (bins.isNotEmpty()) {
                    val cell = w / bins.size
                    bins.forEachIndexed { index, peak ->
                        val x = (index + 0.5f) * cell
                        val half = (1.8.dp.toPx() + peak * 12.2.dp.toPx())
                            .coerceAtMost(h * 0.44f)
                        drawLine(if (x <= playedWidth) MichiAuroraCyan else quiet,
                            Offset(x, y - half), Offset(x, y + half),
                            strokeWidth = (cell * 0.58f).coerceIn(1.dp.toPx(), 2.6.dp.toPx()),
                            cap = StrokeCap.Round)
                    }
                }
            }
            AeroSeekerStyle.WAVEFORM_CONTINUOUS -> {
                val samples = amplitudes.orEmpty()
                if (samples.isNotEmpty()) {
                    val path = Path()
                    val upperContour = Path()
                    val step = w / (samples.size - 1).coerceAtLeast(1)
                    val peakLimit = h * 0.43f
                    samples.forEachIndexed { i, raw ->
                        val x = i * step
                        val dy = (raw.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0f) *
                            14.5.dp.toPx().coerceAtMost(peakLimit)
                        if (i == 0) {
                            path.moveTo(x, y - dy)
                            upperContour.moveTo(x, y - dy)
                        } else {
                            path.lineTo(x, y - dy)
                            upperContour.lineTo(x, y - dy)
                        }
                    }
                    for (i in samples.lastIndex downTo 0) {
                        val raw = samples[i]
                        val dy = (raw.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0f) *
                            14.5.dp.toPx().coerceAtMost(peakLimit)
                        path.lineTo(i * step, y + dy)
                    }
                    path.close()
                    drawPath(path, quiet)
                    drawPath(upperContour, Color.White.copy(alpha = 0.24f),
                        style = Stroke(width = 0.8.dp.toPx(), cap = StrokeCap.Round))
                    clipRect(right = playedWidth) {
                        drawPath(path, active)
                        drawPath(upperContour, MichiAuroraCyan,
                            style = Stroke(width = 0.9.dp.toPx(), cap = StrokeCap.Round))
                    }
                }
            }
            AeroSeekerStyle.LIQUID_GLASS -> {
                // Aero Capsule: one compact anodized rail, recessed channel and a
                // precise played region. Draw-only; the parent owns gestures/thumb.
                val railHeight = 9.dp.toPx()
                val railTop = y - railHeight / 2f
                val railRadius = androidx.compose.ui.geometry.CornerRadius(railHeight / 2f)
                val inset = 2.dp.toPx()
                val innerHeight = 4.dp.toPx()
                val innerTop = y - innerHeight / 2f
                val innerRadius = androidx.compose.ui.geometry.CornerRadius(innerHeight / 2f)
                val innerWidth = (w - inset * 2f).coerceAtLeast(1f)
                val playedInner = (playedWidth - inset).coerceIn(0f, innerWidth)
                drawRoundRect(
                    org.michimusic.mobile.ui.theme.AeroGraphite,
                    Offset(0f, railTop), Size(w, railHeight), cornerRadius = railRadius,
                )
                drawRoundRect(
                    Color.White.copy(alpha = 0.14f),
                    Offset(0f, railTop), Size(w, railHeight), cornerRadius = railRadius,
                    style = Stroke(width = 0.65.dp.toPx()),
                )
                drawRoundRect(
                    Color.Black.copy(alpha = 0.55f),
                    Offset(inset, innerTop), Size(innerWidth, innerHeight), cornerRadius = innerRadius,
                )
                drawRoundRect(
                    Color.White.copy(alpha = 0.08f),
                    Offset(inset, innerTop), Size(innerWidth, innerHeight), cornerRadius = innerRadius,
                    style = Stroke(width = 0.45.dp.toPx()),
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
                        color = Color.White.copy(alpha = 0.34f),
                        start = Offset(inset * 1.5f, railTop + 0.8.dp.toPx()),
                        end = Offset(w - inset * 1.5f, railTop + 0.8.dp.toPx()),
                        strokeWidth = 0.55.dp.toPx(),
                        cap = StrokeCap.Round,
                    )
                }
            }
            AeroSeekerStyle.ORIGINAL -> Unit // Original uses the unmodified legacy implementation.
        }
    }
}
