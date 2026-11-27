package org.michimusic.mobile.artist

import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test

/** JVM-only deterministic tests. Does not execute Android HTTP or Room. */
class ArtistMusicBrainzSharedRateGateTest {
    @Test fun firstAttemptIsImmediateAndSecondRespectsElevenHundredMs() = runBlocking {
        val now = AtomicLong(1)
        val waits = mutableListOf<Long>()
        val gate = ArtistMusicBrainzRateGate(now::get, { ms -> waits += ms; now.addAndGet(ms) })
        gate.awaitAttempt()
        assertTrue(waits.isEmpty())
        gate.awaitAttempt()
        assertEquals(listOf(1_100L), waits)
        assertEquals(1_101L, now.get())
    }

    @Test fun eachRetryNeedsAnIndependentPermit() = runBlocking {
        val now = AtomicLong(1)
        var permits = 0
        val gate = ArtistMusicBrainzRateGate(now::get, { now.addAndGet(it) })
        repeat(3) { gate.awaitAttempt(); permits++ }
        assertEquals(3, permits)
        assertEquals(2_201L, now.get())
    }

    @Test fun parallelCallersCannotTakeTheSameTurn() = runBlocking {
        val now = AtomicLong(1)
        val observed = mutableListOf<Long>()
        val gate = ArtistMusicBrainzRateGate(now::get, { now.addAndGet(it) })
        (1..9).map { async { gate.awaitAttempt(); synchronized(observed) { observed += now.get() } } }.awaitAll()
        assertEquals(9, observed.distinct().size)
        assertEquals(8_801L, observed.maxOrNull())
        assertEquals(1L, observed.minOrNull())
    }

    @Test fun independentInstancesDoNotClaimSharedGlobalEnforcement() = runBlocking {
        val now = AtomicLong(1)
        val a = ArtistMusicBrainzRateGate(now::get, { now.addAndGet(it) })
        val b = ArtistMusicBrainzRateGate(now::get, { now.addAndGet(it) })
        a.awaitAttempt(); b.awaitAttempt()
        // Only the process singleton, not two arbitrary gate objects, shares a clock.
        assertEquals(1L, now.get())
    }

    @Test fun earlyWakeupMustFailClosed() = runBlocking {
        val gate = ArtistMusicBrainzRateGate({ 100L }, { /* broken clock or delay */ })
        gate.awaitAttempt()
        var rejected = false
        try { gate.awaitAttempt() } catch (_: IllegalStateException) { rejected = true }
        assertTrue(rejected)
    }

    @Test fun clockRegressionMustFailClosed() = runBlocking {
        val now = AtomicLong(2000)
        val gate = ArtistMusicBrainzRateGate(now::get, { now.addAndGet(it) })
        gate.awaitAttempt()
        now.set(1999)
        var rejected = false
        try { gate.awaitAttempt() } catch (_: IllegalStateException) { rejected = true }
        assertTrue(rejected)
    }

    @Test fun monotonicClockNearOverflowIsRejected() = runBlocking {
        val gate = ArtistMusicBrainzRateGate({ Long.MAX_VALUE }, { })
        var rejected = false
        try { gate.awaitAttempt() } catch (_: IllegalStateException) { rejected = true }
        assertTrue(rejected)
    }

    @Test fun cancelledWaitDoesNotConsumeAnotherPermit() = runBlocking {
        val now = AtomicLong(1)
        var cancelOnce = true
        val gate = ArtistMusicBrainzRateGate(now::get, { ms ->
            if (cancelOnce) { cancelOnce = false; throw CancellationException("cancel wait") }
            now.addAndGet(ms)
        })
        gate.awaitAttempt()
        var cancelled = false
        try { gate.awaitAttempt() } catch (_: CancellationException) { cancelled = true }
        assertTrue(cancelled)
        gate.awaitAttempt()
        assertEquals(1_101L, now.get())
    }

    @Test fun retryAfterSaturatesHugeHeaderWithoutOverflow() {
        assertEquals(0L, boundedProviderRetryAfterMs(null))
        assertEquals(0L, boundedProviderRetryAfterMs("Mon, 21 Nov 2026 10:00:00 GMT"))
        assertEquals(0L, boundedProviderRetryAfterMs("-1"))
        assertEquals(2_000L, boundedProviderRetryAfterMs("2"))
        assertEquals(5_000L, boundedProviderRetryAfterMs(Long.MAX_VALUE.toString()))
        assertEquals(5_000L, boundedProviderRetryAfterMs("7"))
    }

    @Test fun invalidRateContractsRejected() {
        var count = 0
        for (ms in listOf(0L, -1L, 60_001L)) {
            try { ArtistMusicBrainzRateGate({ 1L }, { }, ms) }
            catch (_: IllegalArgumentException) { count++ }
        }
        assertEquals(3, count)
    }
}
