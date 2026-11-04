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
        val topY = 0.325.dp.toPx()
        val bottomY = (size.height - 0.5.dp.toPx()).coerceAtLeast(0f)
        val hairline = 0.65.dp.toPx()
        onDrawWithContent {
            light?.let { drawRect(brush = it) }
            grainBrush?.let { drawRect(brush = it, alpha = grainOpacity) }
            inset?.let { drawRect(brush = it) }
            // The actual labels, artwork and interactive children are never tinted.
            drawContent()
            if (spec.edgeAlpha > 0f) {
                drawLine(
                    color = Color.White.copy(alpha = spec.edgeAlpha),
                    start = Offset(0f, topY), end = Offset(size.width, topY),
                    strokeWidth = hairline,
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
