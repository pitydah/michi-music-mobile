package org.michimusic.mobile.ui.components

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.Shader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
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
        // The same directional light is attenuated toward the right edge.
        // This is a draw-only finish: no extra layout, background capture or GPU blur.
        val edgeLight = if (spec.edgeAlpha > 0f) Brush.horizontalGradient(
            0f to Color.White.copy(alpha = spec.edgeAlpha),
            0.28f to Color.White.copy(alpha = spec.edgeAlpha * 0.68f),
            1f to Color.White.copy(alpha = spec.edgeAlpha * 0.12f),
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
        val topY = 0.325.dp.toPx()
        val bottomY = (size.height - 0.5.dp.toPx()).coerceAtLeast(0f)
        val hairline = 0.65.dp.toPx()
        onDrawWithContent {
            light?.let { drawRect(brush = it) }
            grainBrush?.let { drawRect(brush = it, alpha = grainOpacity) }
            inset?.let { drawRect(brush = it) }
            bottomShade?.let { drawRect(brush = it) }
            // The actual labels, artwork and interactive children are never tinted.
            drawContent()
            if (edgeLight != null && optical.enabled) {
                drawLine(
                    brush = edgeLight,
                    start = Offset(0f, topY), end = Offset(size.width, topY),
                    strokeWidth = hairline,
                )
                // Directional bounce is confined to the perimeter, never applied
                // as a transparency layer on text, buttons or album artwork.
                if (optical.bottomAlpha > 0f) drawLine(
                    color = Color.White.copy(alpha = optical.bottomAlpha),
                    start = Offset(0f, bottomY), end = Offset(size.width, bottomY),
                    strokeWidth = 0.45.dp.toPx(),
                )
                if (optical.leftAlpha > 0f) drawLine(
                    color = Color.White.copy(alpha = optical.leftAlpha),
                    start = Offset(0f, topY), end = Offset(0f, bottomY),
                    strokeWidth = 0.45.dp.toPx(),
                )
            }
            if (finish == AeroFinish.Inset) {
                drawLine(
                    color = Color.Black.copy(alpha = spec.insetShadeAlpha),
                    start = Offset(0f, topY), end = Offset(size.width, topY),
                    strokeWidth = 1.dp.toPx(),
                )
                drawLine(
                    color = Color.White.copy(alpha = 0.035f),
                    start = Offset(0f, bottomY), end = Offset(size.width, bottomY),
                    strokeWidth = 0.5.dp.toPx(),
                )
            }
        }
    }
}
