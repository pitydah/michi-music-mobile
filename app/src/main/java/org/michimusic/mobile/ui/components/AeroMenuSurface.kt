package org.michimusic.mobile.ui.components

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * One specular line on the menu's own popup surface. Applying it to the popup
 * content, never the window root, keeps it attached during open/close and avoids
 * the historical displaced-rectangle artifact.
 */
internal fun Modifier.aeroMenuTopLight(highContrast: Boolean): Modifier {
    if (highContrast) return this
    return drawWithCache {
        val line = 0.65.dp.toPx()
        val light = Brush.horizontalGradient(
            0f to Color.Transparent,
            0.12f to Color.White.copy(alpha = 0.20f),
            0.50f to Color.White.copy(alpha = 0.08f),
            0.88f to Color.White.copy(alpha = 0.20f),
            1f to Color.Transparent,
        )
        onDrawWithContent {
            drawContent()
            drawRect(brush = light, size = Size(size.width, line))
        }
    }
}
