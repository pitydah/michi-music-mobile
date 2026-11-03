package org.michimusic.mobile.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Compatibility aliases for the historical M48/M49/M60 gradients.
 * New screens must call aeroGradientStops(role, highContrast) instead.
 * These aliases intentionally preserve pixel colors until consumers migrate.
 */
object MichiSurfaces {
    @Deprecated("Use aeroGradientStops(AeroGradientRole.Inset, highContrast)")
    val base: List<Color> = aeroGradientStops(AeroGradientRole.Inset, false)

    @Deprecated("Use aeroGradientStops(AeroGradientRole.Inset, highContrast).first()")
    val baseSolid: Color = aeroGradientStops(AeroGradientRole.Inset, false).first()

    @Deprecated("Use aeroGradientStops(AeroGradientRole.Raised, highContrast)")
    val raised: List<Color> = aeroGradientStops(AeroGradientRole.Raised, false)

    @Deprecated("Use aeroGradientStops(AeroGradientRole.Hero, highContrast)")
    val hero: List<Color> = aeroGradientStops(AeroGradientRole.Hero, false)

    @Deprecated("Use aeroGradientStops(AeroGradientRole.HeroToCanvas, highContrast)")
    val heroFade: List<Color> = aeroGradientStops(AeroGradientRole.HeroToCanvas, false)
}
