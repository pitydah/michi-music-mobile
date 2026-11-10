package org.michimusic.mobile.ui.components

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import org.michimusic.mobile.ui.theme.AeroFinish
import org.michimusic.mobile.ui.theme.AeroFinishSpec

/**
 * A single static light direction, shared by every Aero finish. The tiny cached 128px
 * grain is monochrome; no per-frame RNG, runtime shaders, blur or extra elevations.
 * Decoration is drawn before content, with a strictly perimeter-only final lip.
 */
internal fun Modifier.aeroFinishDrawing(
    finish: AeroFinish,
    spec: AeroFinishSpec,
    grain: ImageBitmap?,
    shape: Shape = RectangleShape,
): Modifier {
    if (finish == AeroFinish.Plain) return this
    return this.drawWithCache {
        val end = (size.height * 0.70f).coerceAtLeast(1f)
        val light = if (spec.topLightAlpha > 0f) {
            Brush.verticalGradient(
                colors = listOf(
                    Color.White.copy(alpha = spec.topLightAlpha),
                    Color.White.copy(alpha = spec.topLightAlpha * 0.30f),
                    Color.Transparent,
                ),
                startY = 0f, endY = end,
            )
        } else null
        val inset = if (finish == AeroFinish.Inset) Brush.verticalGradient(
            listOf(Color.Black.copy(alpha = 0.065f), Color.Transparent),
            startY = 0f, endY = end,
        ) else null
        // Image alpha is already bounded to 6/255. Adjust per finish without
        // turning the design token into a second translucent overlay.
        val grainOpacity = (spec.grainAlpha / (6f / 255f)).coerceIn(0f, 1f)
        val grainBrush = if (grain != null && grainOpacity > 0f) object : ShaderBrush() {
            override fun createShader(size: Size): Shader =
                ImageShader(grain, TileMode.Repeated, TileMode.Repeated)
        } else null
        // One directional light follows the actual rounded perimeter instead of
        // overlaying straight top/left/right strokes inside the corners.
        // This remains draw-only: no extra layout, background capture or GPU blur.
        val edgeLight = if (spec.edgeAlpha > 0f) Brush.linearGradient(
            0f to Color.White.copy(alpha = spec.edgeAlpha),
            0.22f to Color.White.copy(alpha = spec.edgeAlpha * 0.68f),
            0.72f to Color.White.copy(alpha = spec.edgeAlpha * 0.20f),
            1f to Color.White.copy(alpha = spec.edgeAlpha * 0.07f),
            start = Offset.Zero,
            end = Offset(size.width.coerceAtLeast(1f), size.height.coerceAtLeast(1f)),
        ) else null
        val bottomShade = if (finish == AeroFinish.Anodized || finish == AeroFinish.Smoked) {
            Brush.verticalGradient(
                0f to Color.Transparent,
                1f to Color.Black.copy(alpha = 0.028f),
                startY = size.height * 0.72f,
                endY = size.height.coerceAtLeast(1f),
            )
        } else null
        val optical = org.michimusic.mobile.ui.theme.aeroOpticalEdgeLight(
            finish, spec, size.width, size.height,
        )
        val outline = shape.createOutline(size, layoutDirection, this)
        val perimeter = aeroPerimeterPath(outline)
        val bottomEdge = aeroBottomEdgePath(outline)
        val hairline = 0.65.dp.toPx()
        onDrawWithContent {
            light?.let { drawRect(brush = it) }
            grainBrush?.let { drawRect(brush = it, alpha = grainOpacity) }
            inset?.let { drawRect(brush = it) }
            bottomShade?.let { drawRect(brush = it) }
            // The actual labels, artwork and interactive children are never tinted.
            drawContent()
            if (edgeLight != null && optical.enabled) {
                drawPath(
                    path = perimeter,
                    brush = edgeLight,
                    style = Stroke(width = hairline, cap = StrokeCap.Round, join = StrokeJoin.Round),
                )
                // Directional bounce is confined to the real bottom edge; rounded
                // corners remain owned by the perimeter path above.
                if (optical.bottomAlpha > 0f && bottomEdge != null) {
                    drawPath(
                        path = bottomEdge,
                        color = Color.White.copy(alpha = optical.bottomAlpha),
                        style = Stroke(width = 0.45.dp.toPx(), cap = StrokeCap.Round),
                    )
                }
            }
            if (finish == AeroFinish.Inset) {
                drawPath(
                    path = perimeter,
                    brush = Brush.verticalGradient(
                        0f to Color.Black.copy(alpha = spec.insetShadeAlpha),
                        0.58f to Color.Black.copy(alpha = spec.insetShadeAlpha * 0.18f),
                        1f to Color.Transparent,
                        startY = 0f,
                        endY = size.height.coerceAtLeast(1f),
                    ),
                    style = Stroke(width = 1.dp.toPx(), join = StrokeJoin.Round),
                )
                if (bottomEdge != null) {
                    drawPath(
                        path = bottomEdge,
                        color = Color.White.copy(alpha = 0.035f),
                        style = Stroke(width = 0.5.dp.toPx(), cap = StrokeCap.Round),
                    )
                }
            }
        }
    }
}
