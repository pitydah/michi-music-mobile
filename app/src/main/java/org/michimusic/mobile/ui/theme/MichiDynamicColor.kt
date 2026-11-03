package org.michimusic.mobile.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Deterministic palette choice from a textual seed; does NOT sample album pixels.
 * The one-argument API remains for existing callers and always assumes Aero canvas.
 */
fun michiAccentFor(seed: String?): Color = michiAccentForOn(seed, SurfaceObsidian)

/** Contextual accent with an explicit surface, checked against the WCAG AA text threshold. */
fun michiAccentForOn(seed: String?, background: Color): Color {
    val palette = listOf(
        AccentCoral, AccentPink, AccentPurple, AccentBlue,
        Color(0xFFE3B769), Color(0xFF55D6B3),
    )
    val selected = if (seed.isNullOrBlank()) AccentCoral
        else palette[(seed.hashCode() and Int.MAX_VALUE) % palette.size]
    return aeroReadableInk(selected, background)
}
