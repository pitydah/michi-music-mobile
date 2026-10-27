package org.michimusic.mobile.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Centralized, opaque graphite material vocabulary for Aero UI. */
data class AeroMaterialStyle(
    val fill: Color,
    val outline: Color,
    val borderWidth: Dp,
    val elevation: Dp,
)

fun aeroMaterialStyle(role: AeroSurfaceRole, highContrast: Boolean): AeroMaterialStyle {
    val rule = aeroMaterialRule(role, highContrast)
    val fill = if (highContrast) when (role) {
        AeroSurfaceRole.Canvas -> Color.Black
        AeroSurfaceRole.Content -> Color(0xFF0C0F14)
        AeroSurfaceRole.Raised -> Color(0xFF171B22)
        AeroSurfaceRole.Floating -> Color(0xFF222832)
    } else when (role) {
        AeroSurfaceRole.Canvas -> SurfaceObsidian
        AeroSurfaceRole.Content -> SurfaceContainerLow
        AeroSurfaceRole.Raised -> AeroGraphite
        AeroSurfaceRole.Floating -> AeroGraphiteRaised
    }
    return AeroMaterialStyle(
        fill = fill,
        outline = Color.White.copy(alpha = rule.borderOpacity),
        borderWidth = rule.borderWidthDp.dp,
        elevation = rule.elevationDp.dp,
    )
}
