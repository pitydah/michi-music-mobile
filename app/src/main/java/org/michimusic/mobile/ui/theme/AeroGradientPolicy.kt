package org.michimusic.mobile.ui.theme

import androidx.compose.ui.graphics.Color

/** Semantic decorative variants owned by Aero, not additional surface systems. */
enum class AeroGradientRole { Inset, Raised, Hero, HeroToCanvas }

/**
 * Default stops preserve M48/M49/M60 appearance; high contrast removes decoration.
 * Actual cards must still use AeroSurface for outline/elevation/interaction semantics.
 */
fun aeroGradientStops(role: AeroGradientRole, highContrast: Boolean): List<Color> {
    if (highContrast) {
        val surfaceRole = when (role) {
            AeroGradientRole.Inset -> AeroSurfaceRole.Content
            AeroGradientRole.Raised, AeroGradientRole.Hero -> AeroSurfaceRole.Raised
            AeroGradientRole.HeroToCanvas -> AeroSurfaceRole.Canvas
        }
        return listOf(aeroMaterialStyle(surfaceRole, highContrast = true).fill)
    }
    return when (role) {
        AeroGradientRole.Inset -> listOf(Color(0xFF1E222B), Color(0xFF171A21))
        AeroGradientRole.Raised -> listOf(Color(0xFF272D39), Color(0xFF1E232C), AeroGraphite)
        AeroGradientRole.Hero -> listOf(Color(0xFF323C4A), Color(0xFF252E3A), AeroGraphite)
        AeroGradientRole.HeroToCanvas ->
            listOf(Color(0xFF323C4A), Color(0xFF252E3A), SurfaceObsidian)
    }
}
