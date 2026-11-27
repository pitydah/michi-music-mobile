package org.michimusic.data.artist

/**
 * D26/S4-G3: a pure, fail-closed backoff calculation; NOT a scheduler.
 * Future dispatch must use a durable app-wide quota and recheck provider
 * terms. A per-instance timer is not proof of app-wide rate compliance.
 */
object ArtistBulkProviderRetryPolicy {
    const val MAX_ATTEMPTS = 3
    const val MUSICBRAINZ_MIN_INTERVAL_MS = 1_100L
    private const val MAX_RETRY_DELAY_MS = 3_600_000L
    private const val BASE_RETRY_DELAY_MS = 30_000L

    /** Seconds-only Retry-After. HTTP-date and malformed values require a later strict parser. */
    fun retryAfterDelayMs(header: String?): Long? {
        if (header == null) return null
        if (header.length !in 1..8 || !header.all(Char::isDigit)) return null
        val seconds = header.toLongOrNull() ?: return null
        if (seconds > MAX_RETRY_DELAY_MS / 1000L) return null // manual review, never auto-spin
        return seconds * 1000L
    }

    /** A null return means no automatic retry, not "retry immediately". */
    fun nextRetryAt(
        nowEpochMs: Long,
        attempt: Int,
        lastRequestAtEpochMs: Long?,
        retryAfterHeader: String?,
    ): Long? {
        if (nowEpochMs <= 0L || attempt !in 1 until MAX_ATTEMPTS ||
            (lastRequestAtEpochMs != null && (lastRequestAtEpochMs <= 0 ||
                lastRequestAtEpochMs > nowEpochMs))
        ) return null
        if (retryAfterHeader != null && retryAfterDelayMs(retryAfterHeader) == null) return null
        val exponential = (BASE_RETRY_DELAY_MS shl (attempt - 1)).coerceAtMost(MAX_RETRY_DELAY_MS)
        val requestDelay = retryAfterDelayMs(retryAfterHeader) ?: 0L
        val minimumFromLast = lastRequestAtEpochMs?.let {
            safeAdd(it, MUSICBRAINZ_MIN_INTERVAL_MS)
        } ?: nowEpochMs
        val earliest = safeAdd(nowEpochMs, maxOf(exponential, requestDelay)) ?: return null
        return maxOf(earliest, minimumFromLast)
    }

    private fun safeAdd(base: Long, delta: Long): Long? =
        if (base < 0 || delta < 0 || base > Long.MAX_VALUE - delta) null else base + delta
}
