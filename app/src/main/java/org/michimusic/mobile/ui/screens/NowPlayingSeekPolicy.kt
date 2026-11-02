package org.michimusic.mobile.ui.screens

/** Never dispatch seeks from absent/unknown-duration media or NaN UI input. */
internal fun safeNowPlayingSeekMillis(
    requestedSeconds: Float,
    durationSeconds: Float,
    hasTrack: Boolean,
): Long? {
    if (!hasTrack || !requestedSeconds.isFinite() ||
        !durationSeconds.isFinite() || durationSeconds <= 0f) return null
    val durationMillis = durationSeconds.toDouble() * 1000.0
    // Float.MAX_VALUE is finite but cannot represent a Media3 Long millisecond position.
    if (!durationMillis.isFinite() || durationMillis >= Long.MAX_VALUE.toDouble()) return null
    return (requestedSeconds.coerceIn(0f, durationSeconds).toDouble() * 1000.0).toLong()
}
