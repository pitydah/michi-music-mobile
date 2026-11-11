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
import org.michimusic.mobile.ui.seeker.waveformContinuousForWidth
import org.michimusic.mobile.ui.seeker.ContinuousWaveformPresentation
import org.michimusic.mobile.ui.seeker.WaveformMeasuredPreview
import org.michimusic.mobile.ui.seeker.waveformRenderLevels
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin
import org.michimusic.mobile.ui.theme.AeroSeekerStyle
import org.michimusic.mobile.ui.theme.MichiAuroraBlue
import org.michimusic.mobile.ui.theme.MichiAuroraCyan
import org.michimusic.mobile.ui.theme.MichiAuroraLavender
import org.michimusic.mobile.ui.theme.aeroWavyMotionAllowed

/**
 * Draw-only treatment: the parent GradientProgressBar retains its sole semantic
 * and gesture layer. Never attach pointerInput or a second testTag here.
 * External height is owned by the parent (44 dp for waveform styles), even with
 * large font scale; the touch target remains at least 48 dp.
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
    barsPreview: WaveformMeasuredPreview? = null,
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
                // Independent bars, not a filled wave. The count adapts to the
                // real width (about 45-60 at 360 dp) and never exceeds the PCM
                // bins available; peak reduction preserves transients.
                val desired = (w / 6.0.dp.toPx()).roundToInt()
                val barCount = desired.coerceIn(
                    if (w >= 300.dp.toPx()) 45 else 24,
                    60,
                )
                // Null represents UNMEASURED time, not silence. Never draw a
                // generic tiny bar in an unanalysed time window.
                val bins: List<Float?> = barsPreview?.barsForWidth(barCount)
                    ?: waveformRenderLevels(amplitudes.orEmpty(), barCount)
                if (bins.isNotEmpty()) {
                    if (barsPreview != null) drawLine(
                        quiet.copy(alpha = 0.45f), Offset(0f, y), Offset(w, y),
                        strokeWidth = hairline, cap = StrokeCap.Round,
                    )
                    val cell = w / bins.size
                    val barWidth = (cell * 0.46f)
                        .coerceIn(2.5.dp.toPx(), 3.5.dp.toPx())
                    bins.forEachIndexed { index, measured ->
                        val level = measured ?: return@forEachIndexed
                        val x = (index + 0.5f) * cell
                        val half = (1.6.dp.toPx() + level * 19.dp.toPx())
                            .coerceAtMost(h * 0.47f)
                        drawLine(
                            if (x <= playedWidth) MichiAuroraCyan else quiet,
                            Offset(x, y - half),
                            Offset(x, y + half),
                            strokeWidth = barWidth,
                            cap = StrokeCap.Round,
                        )
                    }
                }
            }
            AeroSeekerStyle.WAVEFORM_CONTINUOUS -> {
                val samples = amplitudes.orEmpty()
                if (samples.size >= 2) {
                    val maxPoints = (w / 2.4.dp.toPx()).roundToInt().coerceIn(80, 180)
                    val levels = ContinuousWaveformPresentation.envelope(
                        waveformContinuousForWidth(samples, maxPoints),
                    )
                    if (levels.size >= 2) {
                        val step = w / (levels.size - 1)
                        // One unipolar silhouette, with a shared lower baseline.
                        // The played part recolors the SAME path; no mirrored lobes,
                        // center seam, synthetic peaks, or second gesture layer.
                        val geom = ContinuousWaveformPresentation.geometry(
                            heightPx = h, insetPx = 2.dp.toPx(), floorPx = 1.25.dp.toPx(),
                        )
                        val baseline = geom.baselinePx // shares the parent thumb's centre axis
                        val contour = levels.mapIndexed { index, level ->
                            Offset(index * step, ContinuousWaveformPresentation.contourY(level, geom))
                        }
                        val contourPath = smoothThrough(contour)
                        val fill = Path().apply {
                            moveTo(0f, baseline)
                            lineTo(contour.first().x, contour.first().y)
                            for (index in 1 until contour.size) {
                                val previous = contour[index - 1]
                                val current = contour[index]
                                quadraticTo(previous.x, previous.y,
                                    (previous.x + current.x) / 2f,
                                    (previous.y + current.y) / 2f)
                            }
                            lineTo(contour.last().x, contour.last().y)
                            lineTo(w, baseline)
                            close()
                        }
                        drawPath(fill, quiet)
                        drawPath(contourPath, Color.White.copy(alpha = 0.28f),
                            style = Stroke(width = 0.9.dp.toPx(), cap = StrokeCap.Round))
                        clipRect(right = playedWidth) {
                            drawPath(fill, active)
                            drawPath(contourPath, MichiAuroraCyan,
                                style = Stroke(width = 1.1.dp.toPx(), cap = StrokeCap.Round))
                        }
                        if (playedWidth > 2.dp.toPx() && playedWidth < w - 2.dp.toPx()) {
                            // Short cursor beneath the centre-aligned thumb; never a
                            // full-height divider that visually cuts the waveform.
                            val index = ((levels.lastIndex * fraction).roundToInt())
                                .coerceIn(0, levels.lastIndex)
                            val top = contour[index].y
                            drawLine(Color.White.copy(alpha = 0.34f),
                                Offset(playedWidth, top), Offset(playedWidth, baseline),
                                strokeWidth = 1.dp.toPx(), cap = StrokeCap.Round)
                        }
                    }
                }
            }
            AeroSeekerStyle.LIQUID_GLASS -> {
                // Aero Capsule: a machined pill, not a generic rail. One outer
                // anodized body, a recessed channel and a precise played fill.
                // Draw-only; the parent owns gestures and the thumb.
                val railHeight = AeroCapsuleMetrics.RailHeightDp.dp.toPx()
                val railTop = y - railHeight / 2f
                val railRadius = androidx.compose.ui.geometry.CornerRadius(railHeight / 2f)
                val inset = AeroCapsuleMetrics.InsetDp.dp.toPx()
                val innerHeight = AeroCapsuleMetrics.ChannelHeightDp.dp.toPx()
                val innerTop = y - innerHeight / 2f
                val innerRadius = androidx.compose.ui.geometry.CornerRadius(innerHeight / 2f)
                val innerWidth = (w - inset * 2f).coerceAtLeast(0f)
                val playedInner = (playedWidth - inset).coerceIn(0f, innerWidth)
                drawRoundRect(
                    org.michimusic.mobile.ui.theme.AeroGraphite,
                    Offset(0f, railTop), Size(w, railHeight), cornerRadius = railRadius,
                )
                drawRoundRect(
                    Color.White.copy(alpha = 0.18f),
                    Offset(0f, railTop), Size(w, railHeight), cornerRadius = railRadius,
                    style = Stroke(width = 0.8.dp.toPx()),
                )
                if (innerWidth > 0f) {
                    drawRoundRect(
                        Color.Black.copy(alpha = 0.48f),
                        Offset(inset, innerTop), Size(innerWidth, innerHeight), cornerRadius = innerRadius,
                    )
                    drawRoundRect(
                        Color.White.copy(alpha = 0.08f),
                        Offset(inset, innerTop), Size(innerWidth, innerHeight), cornerRadius = innerRadius,
                        style = Stroke(width = 0.55.dp.toPx()),
                    )
                    if (playedInner > 0f) {
                        drawRoundRect(
                            brush = active,
                            topLeft = Offset(inset, innerTop),
                            size = Size(playedInner, innerHeight),
                            cornerRadius = androidx.compose.ui.geometry.CornerRadius(
                                minOf(playedInner / 2f, innerHeight / 2f),
                            ),
                        )
                    }
                }
                if (w > inset * 3f) {
                    // A single specular hairline reads as an anodized lip and
                    // never becomes a glass/blur effect.
                    drawLine(
                        color = Color.White.copy(alpha = 0.26f),
                        start = Offset(inset + 2.dp.toPx(), railTop + 2.dp.toPx()),
                        end = Offset(w - inset - 2.dp.toPx(), railTop + 2.dp.toPx()),
                        strokeWidth = 0.65.dp.toPx(),
                        cap = StrokeCap.Round,
                    )
                }
            }
            AeroSeekerStyle.ORIGINAL -> Unit // Original uses the unmodified legacy implementation.
        }
    }
}

/**
 * Smooths a polyline through midpoints without flattening it into a generic
 * sine. The control point keeps each measured sample as an anchor, so isolated
 * transients remain visible.
 */
private fun smoothThrough(points: List<Offset>): Path {
    val path = Path()
    if (points.isEmpty()) return path
    path.moveTo(points.first().x, points.first().y)
    for (index in 1 until points.size) {
        val previous = points[index - 1]
        val current = points[index]
        path.quadraticTo(
            previous.x,
            previous.y,
            (previous.x + current.x) / 2f,
            (previous.y + current.y) / 2f,
        )
    }
    val last = points.last()
    path.lineTo(last.x, last.y)
    return path
}
