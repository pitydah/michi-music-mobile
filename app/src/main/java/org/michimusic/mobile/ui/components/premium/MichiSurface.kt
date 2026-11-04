package org.michimusic.mobile.ui.components.premium

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import org.michimusic.mobile.ui.components.AeroSurface
import org.michimusic.mobile.ui.theme.AeroSurfaceRole
import org.michimusic.mobile.ui.theme.AeroFinish
import org.michimusic.mobile.ui.theme.aeroMaterialStyle
import org.michimusic.mobile.ui.theme.LocalVisualPreferences
import org.michimusic.mobile.ui.theme.MichiShapes

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
    AeroSurface(
        role = when (level) {
            MichiSurfaceLevel.Canvas -> AeroSurfaceRole.Canvas
            MichiSurfaceLevel.Content -> AeroSurfaceRole.Content
            MichiSurfaceLevel.Raised -> AeroSurfaceRole.Raised
            MichiSurfaceLevel.Floating -> AeroSurfaceRole.Floating
        },
        modifier = modifier,
        shape = shape,
        borderStroke = borderStroke,
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
    AeroSurface(
        role = AeroSurfaceRole.Floating,
        finish = AeroFinish.Smoked,
        shape = shape,
        modifier = modifier,
    ) { content() }
}
