package org.michimusic.mobile.playback

/**
 * Cadence for the fallback poll that keeps a remote host's state fresh when SSE is not
 * connected.
 *
 * The old loop asked the host every three seconds no matter what: while the music was
 * paused, while the app was idle, and even after repeated failures, which is the worst
 * moment to keep asking. A flat cadence also hides real trouble behind constant traffic.
 */
object RemotePollingPolicy {

    /** While something is actually playing, a three second beat is what makes the bar move. */
    const val PLAYING_INTERVAL_MS: Long = 3_000L

    /** Nothing changes by itself in pause, so do not pay the radio bill for it. */
    const val PAUSED_INTERVAL_MS: Long = 12_000L

    /** Ceiling for the backoff: past half a minute the fallback is not a fallback anymore. */
    const val MAX_INTERVAL_MS: Long = 30_000L

    /** 3 s -> 6 s -> 12 s -> 24 s, then the ceiling. */
    const val MAX_BACKOFF_STEPS: Int = 3

    /**
     * Delay before the next fallback poll, or `null` when SSE is connected and the poll
     * must not run at all.
     *
     * @param isPlaying the remote host is playing, so position keeps moving.
     * @param consecutiveFailures failed polls in a row; reset to zero on any success.
     * @param sseConnected the event stream took over, so polling is unnecessary.
     * @param jitter fraction in `0.0..0.2` added on top, so several clients do not
     *   retry in lockstep after a host restart.
     */
    fun nextDelayMs(
        isPlaying: Boolean,
        consecutiveFailures: Int,
        sseConnected: Boolean,
        jitter: Double = 0.0,
    ): Long? {
        if (sseConnected) return null
        val base = if (isPlaying) PLAYING_INTERVAL_MS else PAUSED_INTERVAL_MS
        val steps = consecutiveFailures.coerceIn(0, MAX_BACKOFF_STEPS)
        val backoff = base * (1L shl steps)
        val capped = backoff.coerceAtMost(MAX_INTERVAL_MS)
        val extra = (capped * jitter.coerceIn(0.0, 0.2)).toLong()
        return (capped + extra).coerceAtMost(MAX_INTERVAL_MS)
    }
}
