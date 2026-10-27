package org.michimusic.mobile.ui.components

/** Pure presentation rules: UI decisions never imply a playback or link capability. */
fun aeroDockHeightDp(fontScale: Float): Int = when {
    !fontScale.isFinite() -> 72
    fontScale > 1.5f -> 88
    fontScale > 1.2f -> 76
    else -> 64
}

/** Return a representable, bounded slider fraction even for corrupted player state. */
fun aeroSafeFraction(value: Float): Float = value.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0f

/** No GPU-heavy artwork blur or animated decoration when accessibility preferences forbid it. */
fun aeroArtworkBackdropAllowed(hasArtwork: Boolean, highContrast: Boolean, reducedMotion: Boolean): Boolean =
    hasArtwork && !highContrast && !reducedMotion

/** A side-cover tap selects instantly when reduced motion is enabled. */
fun aeroCoverFlowShouldAnimate(reducedMotion: Boolean): Boolean = !reducedMotion
