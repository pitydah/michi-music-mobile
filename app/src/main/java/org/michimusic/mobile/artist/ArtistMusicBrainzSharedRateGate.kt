package org.michimusic.mobile.artist

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * D29: one process-wide admission clock for EVERY MusicBrainz attempt, including retries.
 *
 * This is a transport rate limiter for existing manual requests, not a bulk dispatch
 * capability or an alternative to the durable NO_IO quota in Artist Enrichment Room.
 * Each process keeps one singleton below; after process death the gate starts empty.
 * This intentionally does not claim cross-process enforcement. Keep bulk disabled
 * until it has an independently certified durable dispatch lease.
 */
internal class ArtistMusicBrainzRateGate(
    private val elapsedRealtimeMs: () -> Long,
    private val suspendForMs: suspend (Long) -> Unit,
    private val intervalMs: Long = 1_100L,
) {
    init { require(intervalMs > 0L && intervalMs <= 60_000L) }
    private val mutex = Mutex()
    private var nextEligibleElapsedMs = 0L
    private var lastAdmittedElapsedMs = -1L

    /** A successful return admits ONE attempt; callers must not reuse it for retries. */
    suspend fun awaitAttempt() = mutex.withLock {
        val started = elapsedRealtimeMs()
        check(started >= 0L && started >= lastAdmittedElapsedMs) { "Invalid or regressing monotonic clock" }
        val wait = (nextEligibleElapsedMs - started).coerceAtLeast(0L)
        if (wait > 0L) suspendForMs(wait)
        val observed = elapsedRealtimeMs()
        // A fake or regressing clock must not silently admit rapid requests.
        check(observed >= started && observed >= nextEligibleElapsedMs) {
            "Monotonic time moved backward or a delay ended prematurely"
        }
        check(observed <= Long.MAX_VALUE - intervalMs) { "Clock overflow" }
        nextEligibleElapsedMs = observed + intervalMs
        lastAdmittedElapsedMs = observed
    }
}

/** Shared among all ArtistEnrichmentRepository instances in this Android process. */
internal object ArtistMusicBrainzSharedRateGate {
    @Volatile
    private var gate = ArtistMusicBrainzRateGate(
        // System.nanoTime is monotonic and, unlike Robolectric's simulated
        // SystemClock, advances with a real coroutine delay. Rate limiting only
        // needs ordering, not deep-sleep accounting.
        elapsedRealtimeMs = { System.nanoTime() / 1_000_000L },
        suspendForMs = { delay(it) },
    )
    suspend fun awaitAttempt() = gate.awaitAttempt()

    /**
     * Test isolation only: Robolectric gives each test a fresh SystemClock while the
     * process singleton survives, which looks like clock regression to the real gate.
     * No production path calls this.
     */
    internal fun resetForTests() {
        gate = ArtistMusicBrainzRateGate(
            elapsedRealtimeMs = { System.nanoTime() / 1_000_000L },
            suspendForMs = { delay(it) },
        )
    }
}

/** Numeric Retry-After: saturate BEFORE multiplication, never overflow to a negative wait. */
internal fun boundedProviderRetryAfterMs(raw: String?, maxDelayMs: Long = 5_000L): Long {
    require(maxDelayMs >= 0L)
    val seconds = raw?.trim()?.toLongOrNull() ?: return 0L
    if (seconds <= 0L || maxDelayMs == 0L) return 0L
    val safeSeconds = maxDelayMs / 1_000L
    if (seconds > safeSeconds) return maxDelayMs
    return (seconds * 1_000L).coerceAtMost(maxDelayMs)
}
