package org.michimusic.mobile.ui.theme

/**
 * UI5 touch treatment: modifies only the already drawn optical recipe; no new
 * layers, animated frames, elevation, shadow bounds or changes to touch geometry.
 */
internal fun aeroInteractiveFinishSpec(
    finish: AeroFinish,
    base: AeroFinishSpec,
    pressed: Boolean,
    highContrast: Boolean,
): AeroFinishSpec {
    if (!pressed || highContrast || finish == AeroFinish.Plain || finish == AeroFinish.Inset) return base
    return base.copy(
        topLightAlpha = (base.topLightAlpha + 0.012f).coerceAtMost(0.12f),
        edgeAlpha = (base.edgeAlpha + 0.006f).coerceAtMost(0.12f),
    )
}

/** Focus is an inset paint-only boundary; no hit-target or layout displacement. */
internal fun aeroFocusStrokeWidthDp(focused: Boolean, clickable: Boolean, highContrast: Boolean): Float =
    if (!focused || !clickable) 0f else if (highContrast) 2f else 1.2f
