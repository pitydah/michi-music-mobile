package org.michimusic.mobile.ui.components

/** Pure time presentation. Unknown or invalid media metadata is never rendered as 0:00. */
fun mediaDurationLabel(durationMillis: Long): String =
    if (durationMillis <= 0L) "—" else mediaClockFromSeconds(durationMillis / 1_000L)

/** Elapsed time may genuinely be zero, unlike an unknown duration. */
fun mediaElapsedLabel(seconds: Float): String =
    if (!seconds.isFinite() || seconds < 0f) "—:—" else mediaClockFromSeconds(seconds.toLong())

/** Unknown end times and untrusted numeric input have no invented countdown. */
fun mediaRemainingLabel(currentSeconds: Float, totalSeconds: Float): String {
    if (!currentSeconds.isFinite() || currentSeconds < 0f ||
        !totalSeconds.isFinite() || totalSeconds <= 0f) return "—:—"
    return "-" + mediaClockFromSeconds((totalSeconds - currentSeconds).coerceAtLeast(0f).toLong())
}

private fun mediaClockFromSeconds(seconds: Long): String {
    val hours = seconds / 3_600L
    val minutes = (seconds / 60L) % 60L
    val remainingSeconds = seconds % 60L
    return if (hours > 0L) "%d:%02d:%02d".format(hours, minutes, remainingSeconds)
    else "%d:%02d".format(seconds / 60L, remainingSeconds)
}
