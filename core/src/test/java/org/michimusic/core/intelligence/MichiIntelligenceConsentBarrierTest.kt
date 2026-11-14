package org.michimusic.core.intelligence

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MichiIntelligenceConsentBarrierTest {
    @Test fun consentRevisionPublishesWithoutTimersOrPolling() {
        val barrier = MichiIntelligenceConsentBarrier
        val previous = barrier.changes.value
        barrier.acceptedExplicitConsent()
        val allowed = barrier.changes.value
        assertTrue(allowed > previous)
        assertTrue(barrier.permits(allowed))
        barrier.revoke()
        assertTrue(barrier.changes.value > allowed)
        assertFalse(barrier.permits(allowed))
    }
    @Test fun revokedQueuedEventCannotReturnAfterReEnable() {
        val barrier = MichiIntelligenceConsentBarrier
        barrier.acceptedExplicitConsent()
        val old = barrier.epoch()
        assertTrue(barrier.permits(old))
        barrier.revoke()
        assertFalse(barrier.permits(old))
        assertFalse(barrier.permits(barrier.epoch()))
        barrier.acceptedExplicitConsent()
        assertNotEquals(old, barrier.epoch())
        assertFalse(barrier.permits(old))
        assertTrue(barrier.permits(barrier.epoch()))
    }

    @Test fun twoRapidRevocationsAlwaysInvalidateOldSnapshots() {
        val barrier = MichiIntelligenceConsentBarrier
        barrier.acceptedExplicitConsent()
        val old = barrier.epoch()
        barrier.revoke()
        barrier.revoke()
        assertFalse(barrier.permits(old))
        assertFalse(barrier.permits(barrier.epoch()))
    }
    @Test fun concurrentConsentChangesLeaveRevisionAndFenceCoherent() {
        val barrier = MichiIntelligenceConsentBarrier
        val start = java.util.concurrent.CountDownLatch(1)
        val finish = java.util.concurrent.CountDownLatch(2)
        val faults = java.util.concurrent.atomic.AtomicInteger(0)
        repeat(2) { worker ->
            kotlin.concurrent.thread(start = true) {
                try {
                    start.await()
                    repeat(1000) {
                        if ((it + worker) % 2 == 0) barrier.revoke()
                        else barrier.acceptedExplicitConsent()
                    }
                } catch (_: Exception) { faults.incrementAndGet() }
                finally { finish.countDown() }
            }
        }
        start.countDown()
        assertTrue(finish.await(10, java.util.concurrent.TimeUnit.SECONDS))
        assertTrue(faults.get() == 0)
        // If flow emission can reorder behind a newer generation, this diverges.
        assertTrue(barrier.changes.value == barrier.epoch())
        barrier.revoke()
        assertFalse(barrier.permits(barrier.epoch()))
    }
    @Test fun delayedEnableCannotUndoAConcurrentOptOut() {
        val barrier = MichiIntelligenceConsentBarrier
        val requestEpoch = barrier.epoch()
        barrier.revoke()
        assertFalse(barrier.acceptIfUnchanged(requestEpoch))
        assertFalse(barrier.permits(barrier.epoch()))
        val freshEpoch = barrier.epoch()
        assertTrue(barrier.acceptIfUnchanged(freshEpoch))
        assertTrue(barrier.permits(barrier.epoch()))
    }
}
