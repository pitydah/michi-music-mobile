package org.michimusic.mobile.ui.screens

/**
 * UI5: visual floor for the Audio/DSP shortcuts, not a fixed height. Compose's
 * heightIn lets two-line titles, translations and larger fonts grow naturally.
 * The clickable AeroSurface remains at least 48dp in every configuration.
 */
internal fun aeroSettingsAudioQuickActionMinHeightDp(fontScale: Float): Int = when {
    !fontScale.isFinite() || fontScale <= 0f -> 72 // conservative invalid-input fallback
    fontScale >= 1.7f -> 88
    fontScale >= 1.3f -> 72
    else -> 64
}
