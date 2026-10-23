package org.michimusic.mobile.ui.components.premium

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.michimusic.mobile.ui.theme.GlassBorderHigh
import org.michimusic.mobile.ui.theme.GlassBorderLow
import org.michimusic.mobile.ui.theme.GlassFillHigh
import org.michimusic.mobile.ui.theme.GlassFillLow
import org.michimusic.mobile.ui.theme.LocalVisualPreferences
import org.michimusic.mobile.ui.theme.MichiShapes
import org.michimusic.mobile.ui.theme.PureWhite
import org.michimusic.mobile.ui.theme.SurfaceDark
import org.michimusic.mobile.ui.theme.SurfaceObsidian

enum class MichiSurfaceLevel {
    Canvas,     // Deep obsidian background
    Content,    // Standard content containers
    Raised,     // Cards, list rows, elevated items
    Floating,   // Overlays, dialogs, bottom sheets, floating dock
}

/**
 * Standardized semantic surface component.
 *
 * Automatically adapts elevation, opacity and high-contrast borders based on
 * [LocalVisualPreferences] and the chosen [MichiSurfaceLevel].
 */
@Composable
fun MichiSurface(
    modifier: Modifier = Modifier,
    level: MichiSurfaceLevel = MichiSurfaceLevel.Raised,
    shape: Shape = MichiShapes.md,
    borderStroke: BorderStroke? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    val visualPrefs = LocalVisualPreferences.current
    val highContrast = visualPrefs.highContrast

    val backgroundColor = when (level) {
        MichiSurfaceLevel.Canvas -> if (highContrast) Color.Black else SurfaceObsidian
        MichiSurfaceLevel.Content -> if (highContrast) Color(0xFF101216) else SurfaceDark
        MichiSurfaceLevel.Raised -> if (highContrast) Color(0xFF181B22) else GlassFillLow
        MichiSurfaceLevel.Floating -> if (highContrast) Color(0xFF22252E) else GlassFillHigh
    }

    val effectiveBorder: BorderStroke? = borderStroke ?: when {
        highContrast -> BorderStroke(1.dp, PureWhite.copy(alpha = 0.85f))
        level == MichiSurfaceLevel.Floating -> BorderStroke(1.dp, GlassBorderHigh)
        level == MichiSurfaceLevel.Raised -> BorderStroke(1.dp, GlassBorderLow)
        else -> null
    }

    val elevation: Dp = when (level) {
        MichiSurfaceLevel.Floating -> 12.dp
        MichiSurfaceLevel.Raised -> 4.dp
        else -> 0.dp
    }

    Box(
        modifier = modifier
            .then(if (elevation > 0.dp && !highContrast) Modifier.shadow(elevation, shape) else Modifier)
            .clip(shape)
            .background(backgroundColor)
            .then(if (effectiveBorder != null) Modifier.border(effectiveBorder, shape) else Modifier),
        content = content,
    )
}
