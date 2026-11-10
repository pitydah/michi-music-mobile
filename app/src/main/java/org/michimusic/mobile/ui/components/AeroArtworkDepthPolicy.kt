package org.michimusic.mobile.ui.components

/**
 * Semantic depth for real album artwork. Standard is the safe default for dense
 * rows; only focal surfaces opt into contact elevation and a brighter lip.
 */
enum class AeroArtworkEmphasis { Standard, Featured, Hero }

internal data class AeroArtworkDepth(
    val shadowElevationDp: Int,
    val innerLightAlpha: Float,
    val innerShadeAlpha: Float,
)

internal fun aeroArtworkDepth(
    emphasis: AeroArtworkEmphasis,
    highContrast: Boolean,
): AeroArtworkDepth = if (highContrast) {
    // Accessibility wins: no decorative shadow or directional lip.
    AeroArtworkDepth(0, 0f, 0f)
} else when (emphasis) {
    AeroArtworkEmphasis.Standard -> AeroArtworkDepth(0, 0.10f, 0.14f)
    AeroArtworkEmphasis.Featured -> AeroArtworkDepth(3, 0.18f, 0.24f)
    AeroArtworkEmphasis.Hero -> AeroArtworkDepth(7, 0.24f, 0.32f)
}
