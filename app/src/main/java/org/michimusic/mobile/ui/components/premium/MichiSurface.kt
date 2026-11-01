package org.michimusic.mobile.ui.components.premium

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.michimusic.mobile.ui.theme.AeroSurfaceRole
import org.michimusic.mobile.ui.theme.aeroMaterialStyle
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
    val material = aeroMaterialStyle(
        role = when (level) {
            MichiSurfaceLevel.Canvas -> AeroSurfaceRole.Canvas
            MichiSurfaceLevel.Content -> AeroSurfaceRole.Content
            MichiSurfaceLevel.Raised -> AeroSurfaceRole.Raised
            MichiSurfaceLevel.Floating -> AeroSurfaceRole.Floating
        },
        highContrast = LocalVisualPreferences.current.highContrast,
    )
    val effectiveBorder = borderStroke ?: if (material.borderWidth > 0.dp) {
        BorderStroke(material.borderWidth, material.outline)
    } else null

    Box(
        modifier = modifier
            .then(if (material.elevation > 0.dp) Modifier.shadow(material.elevation, shape) else Modifier)
            .clip(shape)
            .background(material.fill)
            .then(if (effectiveBorder != null) Modifier.border(effectiveBorder, shape) else Modifier),
        content = content,
    )
}

/**
 * Standardized floating modal surface component for dialogs and sheets.
 * Directly integrates with semantic [AeroSurfaceRole.Floating] and [LocalVisualPreferences].
 */
@Composable
fun AeroFloatingSurface(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(20.dp),
    content: @Composable () -> Unit,
) {
    val material = aeroMaterialStyle(AeroSurfaceRole.Floating, LocalVisualPreferences.current.highContrast)
    Surface(
        shape = shape,
        color = material.fill,
        border = if (material.borderWidth > 0.dp) BorderStroke(material.borderWidth, material.outline) else null,
        shadowElevation = material.elevation,
        modifier = modifier,
        content = content,
    )
}
