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

/** Optical finishes are a detail of the existing four Aero surface roles, not a second theme. */
enum class AeroFinish { Plain, Satin, Anodized, Smoked, Inset }

/** Immutable, testable optical recipe; paints do not own playback or UI state. */
data class AeroFinishSpec(
    val topLightAlpha: Float,
    val grainAlpha: Float,
    val edgeAlpha: Float,
    val insetShadeAlpha: Float,
)

/** Invalid combinations and high contrast are always rendered as undecorated Plain. */
fun aeroResolvedFinish(role: AeroSurfaceRole, requested: AeroFinish, highContrast: Boolean): AeroFinish {
    if (highContrast) return AeroFinish.Plain
    return when (role) {
        AeroSurfaceRole.Canvas -> AeroFinish.Plain
        AeroSurfaceRole.Content -> when (requested) {
            AeroFinish.Plain, AeroFinish.Satin, AeroFinish.Inset -> requested
            else -> AeroFinish.Plain
        }
        AeroSurfaceRole.Raised -> when (requested) {
            AeroFinish.Plain, AeroFinish.Satin, AeroFinish.Anodized -> requested
            else -> AeroFinish.Plain
        }
        AeroSurfaceRole.Floating -> when (requested) {
            AeroFinish.Plain, AeroFinish.Smoked -> requested
            else -> AeroFinish.Plain
        }
    }
}

fun aeroFinishSpec(finish: AeroFinish, highContrast: Boolean): AeroFinishSpec {
    if (highContrast) return AeroFinishSpec(0f, 0f, 0f, 0f)
    return when (finish) {
        AeroFinish.Plain -> AeroFinishSpec(0f, 0f, 0f, 0f)
        // All alphas remain within the existing <= 0.12 finish-policy tests.
        // Increased separation is optical, not an elevation or layout change.
        AeroFinish.Satin -> AeroFinishSpec(0.025f, 0.016f, 0.055f, 0f)
        AeroFinish.Anodized -> AeroFinishSpec(0.042f, 0.022f, 0.102f, 0f)
        AeroFinish.Smoked -> AeroFinishSpec(0.048f, 0.012f, 0.116f, 0f)
        AeroFinish.Inset -> AeroFinishSpec(0f, 0f, 0f, 0.120f)
    }
}
