package org.michimusic.mobile.ui.components

/** Presentation-only math for a seek bar. Null means duration is unknown or invalid. */
fun seekProgressFraction(position: Float, duration: Float): Float? {
    if (!duration.isFinite() || duration <= 0f || !position.isFinite()) return null
    return (position / duration).coerceIn(0f, 1f)
}

/** A seek must never be issued without a valid duration or fraction. */
fun seekPositionForFraction(fraction: Float, duration: Float): Float? {
    if (!duration.isFinite() || duration <= 0f || !fraction.isFinite()) return null
    return (fraction.coerceIn(0f, 1f) * duration).takeIf { it.isFinite() }
}
