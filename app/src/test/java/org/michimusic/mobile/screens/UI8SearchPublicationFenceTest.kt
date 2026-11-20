package org.michimusic.mobile.screens

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tests the SAME monitor class used by SearchViewModel on its IO and UI threads. */
class UI8SearchPublicationFenceTest {
    @Test fun rootRevocationCannotInterleaveBetweenValidationAndFlowPublication() {
        val fence = SearchPublicationFence()
        val generation = AtomicLong(0)
        val shown = AtomicReference("initial")
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val attemptedRevocation = CountDownLatch(1)
        val workerFailure = AtomicReference<Throwable?>(null)

        val oldSearch = thread {
            try {
                fence.write {
                    if (generation.get() == 0L) {
                        entered.countDown()
                        check(release.await(5, TimeUnit.SECONDS))
                        shown.set("old-results")
                    }
                }
            } catch (failure: Throwable) {
                workerFailure.set(failure)
            }
        }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        val newRoot = thread {
            attemptedRevocation.countDown()
            fence.write {
                generation.incrementAndGet()
                shown.set("empty-new-root")
            }
        }
        assertTrue(attemptedRevocation.await(5, TimeUnit.SECONDS))
        release.countDown()
        oldSearch.join(5_000)
        newRoot.join(5_000)
        assertTrue(!oldSearch.isAlive && !newRoot.isAlive)
        assertEquals(null, workerFailure.get())
        assertEquals(1L, generation.get())
        assertEquals("empty-new-root", shown.get())
    }

    @Test fun lateOldQueryCannotRepublishAfterNewGeneration() {
        val fence = SearchPublicationFence()
        val generation = AtomicLong(1)
        val shown = AtomicReference("old")
        fence.write { generation.incrementAndGet(); shown.set("new-empty") }
        fence.write {
            if (generation.get() == 1L) shown.set("late-old")
        }
        assertEquals("new-empty", shown.get())
    }

    @Test fun writesFromMultipleThreadsDoNotLoseUpdates() {
        val fence = SearchPublicationFence()
        val count = AtomicInteger(0)
        val workers = (1..4).map { thread {
            repeat(1_000) { fence.write { count.set(count.get() + 1) } }
        } }
        workers.forEach { it.join(5_000); assertTrue(!it.isAlive) }
        assertEquals(4_000, count.get())
    }
}
