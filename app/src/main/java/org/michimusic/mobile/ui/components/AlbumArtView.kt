package org.michimusic.mobile.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import org.michimusic.mobile.ui.theme.GlassBorderLow
import org.michimusic.mobile.ui.theme.PrimaryPink
import org.michimusic.mobile.ui.theme.PrimaryPinkContainer
import org.michimusic.mobile.ui.theme.PureWhite
import org.michimusic.mobile.ui.theme.SecondaryPurple
import org.michimusic.mobile.ui.theme.SecondaryPurpleDeep
import org.michimusic.mobile.ui.theme.SurfaceObsidian
import org.michimusic.mobile.ui.theme.TertiaryCyan
import org.michimusic.mobile.ui.theme.TertiaryCyanContainer

enum class CoverStyle {
    NEON_NEBULA,
    VOID_PULSE,
    CRYSTAL_ECHOES,
    NOCTURNAL_BEATS,
    PRISM,
    NEON_DRIFT,
    FRACTURED_LIGHT,
    MIDNIGHT_SYNTHWAVE,
    CYBERNETIC_DRIFT,
    ASTRAL_HORIZON
}

fun coverStyleFor(key: String?): CoverStyle {
    val styles = CoverStyle.values()
    val hash = (key?.hashCode() ?: 0).let { if (it < 0) -it else it }
    return styles[hash % styles.size]
}

@Composable
fun AlbumArtView(
    coverStyle: CoverStyle = CoverStyle.NEON_NEBULA,
    imageModel: Any? = null,
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 12.dp,
    borderWidth: Dp = 1.dp,
    borderColor: Color = GlassBorderLow,
) {
    val shape = RoundedCornerShape(cornerRadius)
    Box(
        modifier = modifier
            .clip(shape)
            .background(SurfaceObsidian)
            .border(borderWidth, borderColor, shape),
    ) {
        if (imageModel != null && imageModel.toString().isNotBlank()) {
            AsyncImage(
                model = imageModel,
                contentDescription = "Album Artwork",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            GenerativeCoverCanvas(coverStyle = coverStyle)
        }
    }
}

@Composable
fun GenerativeCoverCanvas(
    coverStyle: CoverStyle,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height

        when (coverStyle) {
            CoverStyle.NEON_NEBULA -> {
                // Deep space background
                drawRect(
                    brush = Brush.radialGradient(
                        colors = listOf(Color(0xFF280B22), SurfaceObsidian),
                        center = Offset(w * 0.5f, h * 0.5f),
                        radius = w * 0.8f,
                    ),
                )
                // Neon pink geometric star/crystal lattice
                val center = Offset(w * 0.5f, h * 0.5f)
                val r = w * 0.32f
                val starPath = Path()
                val points = 8
                for (i in 0 until points * 2) {
                    val angle = (i * Math.PI / points).toFloat()
                    val currR = if (i % 2 == 0) r else r * 0.45f
                    val x = center.x + currR * kotlin.math.cos(angle)
                    val y = center.y + currR * kotlin.math.sin(angle)
                    if (i == 0) starPath.moveTo(x, y) else starPath.lineTo(x, y)
                }
                starPath.close()

                // Outer pink glow
                drawPath(
                    path = starPath,
                    brush = Brush.linearGradient(
                        colors = listOf(PrimaryPinkContainer, SecondaryPurple),
                    ),
                    style = Stroke(width = 3.5.dp.toPx(), cap = StrokeCap.Round),
                )
                // Inner crystal facets
                drawLine(
                    color = PureWhite.copy(alpha = 0.8f),
                    start = Offset(w * 0.5f, h * 0.18f),
                    end = Offset(w * 0.5f, h * 0.82f),
                    strokeWidth = 1.5.dp.toPx(),
                )
                drawLine(
                    color = PureWhite.copy(alpha = 0.8f),
                    start = Offset(w * 0.18f, h * 0.5f),
                    end = Offset(w * 0.82f, h * 0.5f),
                    strokeWidth = 1.5.dp.toPx(),
                )
            }

            CoverStyle.VOID_PULSE -> {
                // Infinite obsidian void with soft cyan inner sphere
                drawRect(
                    brush = Brush.radialGradient(
                        colors = listOf(Color(0xFF042028), SurfaceObsidian),
                        center = Offset(w * 0.5f, h * 0.5f),
                        radius = w * 0.75f,
                    ),
                )
                // Glowing cyan sphere
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            TertiaryCyan.copy(alpha = 0.9f),
                            TertiaryCyanContainer.copy(alpha = 0.5f),
                            Color.Transparent,
                        ),
                        center = Offset(w * 0.5f, h * 0.5f),
                        radius = w * 0.35f,
                    ),
                    radius = w * 0.35f,
                    center = Offset(w * 0.5f, h * 0.5f),
                )
                // Glass sphere highlight rim
                drawCircle(
                    brush = Brush.linearGradient(
                        colors = listOf(PureWhite.copy(alpha = 0.7f), Color.Transparent),
                        start = Offset(w * 0.3f, h * 0.3f),
                        end = Offset(w * 0.7f, h * 0.7f),
                    ),
                    radius = w * 0.28f,
                    center = Offset(w * 0.5f, h * 0.5f),
                    style = Stroke(width = 2.dp.toPx()),
                )
            }

            CoverStyle.CRYSTAL_ECHOES -> {
                // Dark atmospheric purple environment with spikes
                drawRect(
                    brush = Brush.linearGradient(
                        colors = listOf(Color(0xFF220A38), SurfaceObsidian),
                    ),
                )
                val path = Path().apply {
                    moveTo(0f, h * 0.7f)
                    lineTo(w * 0.2f, h * 0.3f)
                    lineTo(w * 0.35f, h * 0.6f)
                    lineTo(w * 0.5f, h * 0.15f)
                    lineTo(w * 0.65f, h * 0.55f)
                    lineTo(w * 0.8f, h * 0.25f)
                    lineTo(w, h * 0.7f)
                    lineTo(w, h)
                    lineTo(0f, h)
                    close()
                }
                drawPath(
                    path = path,
                    brush = Brush.verticalGradient(
                        colors = listOf(SecondaryPurple, Color(0xFF160624)),
                    ),
                )
                drawPath(
                    path = path,
                    color = PureWhite.copy(alpha = 0.9f),
                    style = Stroke(width = 2.dp.toPx()),
                )
            }

            CoverStyle.NOCTURNAL_BEATS -> {
                drawRect(
                    brush = Brush.linearGradient(
                        colors = listOf(Color(0xFF1C1E26), Color(0xFF0F1015)),
                    ),
                )
                for (i in 1..4) {
                    drawLine(
                        color = PrimaryPinkContainer.copy(alpha = 0.6f),
                        start = Offset(w * 0.2f, h * (0.2f * i)),
                        end = Offset(w * 0.8f, h * (0.2f * i)),
                        strokeWidth = 2.dp.toPx(),
                    )
                }
                drawCircle(
                    color = TertiaryCyan,
                    radius = w * 0.18f,
                    center = Offset(w * 0.5f, h * 0.5f),
                    style = Stroke(width = 3.dp.toPx()),
                )
            }

            CoverStyle.PRISM -> {
                drawRect(SurfaceObsidian)
                val prismPath = Path().apply {
                    moveTo(w * 0.5f, h * 0.25f)
                    lineTo(w * 0.8f, h * 0.75f)
                    lineTo(w * 0.2f, h * 0.75f)
                    close()
                }
                drawPath(
                    path = prismPath,
                    brush = Brush.linearGradient(
                        colors = listOf(PureWhite, Color(0xFFD0D0DD)),
                    ),
                )
                drawLine(
                    brush = Brush.horizontalGradient(
                        colors = listOf(Color.Transparent, TertiaryCyan, PrimaryPinkContainer, Color.Transparent),
                    ),
                    start = Offset(0f, h * 0.65f),
                    end = Offset(w, h * 0.65f),
                    strokeWidth = 2.5.dp.toPx(),
                )
            }

            CoverStyle.NEON_DRIFT -> {
                drawRect(
                    brush = Brush.verticalGradient(
                        colors = listOf(Color(0xFF0A0E1A), Color(0xFF280718)),
                    ),
                )
                drawCircle(
                    color = PrimaryPinkContainer.copy(alpha = 0.4f),
                    radius = w * 0.22f,
                    center = Offset(w * 0.3f, h * 0.4f),
                )
                drawCircle(
                    color = SecondaryPurple.copy(alpha = 0.35f),
                    radius = w * 0.28f,
                    center = Offset(w * 0.7f, h * 0.35f),
                )
                drawCircle(
                    color = TertiaryCyan.copy(alpha = 0.4f),
                    radius = w * 0.16f,
                    center = Offset(w * 0.55f, h * 0.7f),
                )
            }

            CoverStyle.FRACTURED_LIGHT -> {
                drawRect(Color(0xFF090A10))
                val p1 = Path().apply {
                    moveTo(w * 0.5f, h * 0.5f)
                    lineTo(w * 0.1f, 0f)
                    moveTo(w * 0.5f, h * 0.5f)
                    lineTo(w * 0.9f, h * 0.1f)
                    moveTo(w * 0.5f, h * 0.5f)
                    lineTo(w * 0.85f, h)
                    moveTo(w * 0.5f, h * 0.5f)
                    lineTo(w * 0.15f, h * 0.9f)
                    moveTo(w * 0.5f, h * 0.5f)
                    lineTo(0f, h * 0.5f)
                }
                drawCircle(
                    color = SecondaryPurpleDeep.copy(alpha = 0.7f),
                    radius = w * 0.35f,
                    center = Offset(w * 0.5f, h * 0.5f),
                )
                drawPath(
                    path = p1,
                    brush = Brush.radialGradient(
                        colors = listOf(PureWhite, SecondaryPurple, Color.Transparent),
                        center = Offset(w * 0.5f, h * 0.5f),
                    ),
                    style = Stroke(width = 2.dp.toPx()),
                )
            }

            CoverStyle.MIDNIGHT_SYNTHWAVE -> {
                drawRect(
                    brush = Brush.verticalGradient(
                        colors = listOf(Color(0xFF080D21), Color(0xFF380824)),
                    ),
                )
                for (i in 0..5) {
                    val y = h * (0.6f + i * 0.08f)
                    drawLine(
                        color = TertiaryCyan.copy(alpha = 0.5f),
                        start = Offset(0f, y),
                        end = Offset(w, y),
                        strokeWidth = 1.2.dp.toPx(),
                    )
                }
                val skyline = Path().apply {
                    moveTo(w * 0.1f, h * 0.6f)
                    lineTo(w * 0.1f, h * 0.35f)
                    lineTo(w * 0.25f, h * 0.35f)
                    lineTo(w * 0.25f, h * 0.45f)
                    lineTo(w * 0.4f, h * 0.25f)
                    lineTo(w * 0.55f, h * 0.25f)
                    lineTo(w * 0.55f, h * 0.4f)
                    lineTo(w * 0.7f, h * 0.3f)
                    lineTo(w * 0.85f, h * 0.3f)
                    lineTo(w * 0.85f, h * 0.6f)
                    close()
                }
                drawPath(
                    path = skyline,
                    brush = Brush.verticalGradient(
                        colors = listOf(Color(0xFF1C0524), Color(0xFF090A10)),
                    ),
                )
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(PrimaryPinkContainer, Color.Transparent),
                        center = Offset(w * 0.8f, h * 0.25f),
                        radius = w * 0.2f,
                    ),
                    radius = w * 0.18f,
                    center = Offset(w * 0.8f, h * 0.25f),
                )
            }

            CoverStyle.CYBERNETIC_DRIFT -> {
                drawRect(SurfaceObsidian)
                val curve1 = Path().apply {
                    moveTo(0f, h * 0.8f)
                    cubicTo(w * 0.3f, h * 0.2f, w * 0.7f, h * 0.9f, w, h * 0.3f)
                }
                val curve2 = Path().apply {
                    moveTo(0f, h * 0.3f)
                    cubicTo(w * 0.4f, h * 0.85f, w * 0.6f, h * 0.15f, w, h * 0.7f)
                }
                drawPath(
                    path = curve1,
                    brush = Brush.linearGradient(listOf(PrimaryPinkContainer, SecondaryPurple)),
                    style = Stroke(width = 4.dp.toPx(), cap = StrokeCap.Round),
                )
                drawPath(
                    path = curve2,
                    brush = Brush.linearGradient(listOf(TertiaryCyan, PrimaryPink)),
                    style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round),
                )
            }

            CoverStyle.ASTRAL_HORIZON -> {
                drawRect(
                    brush = Brush.radialGradient(
                        colors = listOf(Color(0xFF0A2B35), SurfaceObsidian),
                        center = Offset(w * 0.5f, h * 0.5f),
                        radius = w * 0.75f,
                    ),
                )
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(TertiaryCyan.copy(alpha = 0.6f), Color.Transparent),
                    ),
                    radius = w * 0.35f,
                    center = Offset(w * 0.5f, h * 0.45f),
                )
            }
        }
    }
}
