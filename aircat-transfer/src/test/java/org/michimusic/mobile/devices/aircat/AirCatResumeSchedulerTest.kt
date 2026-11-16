package org.michimusic.mobile.devices.aircat

import android.content.Context
import io.mockk.mockk
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = android.app.Application::class)
class AirCatResumeSchedulerTest {
    private fun lease(): AirCatLeaseCoordinator =
        AirCatLeaseCoordinator(org.robolectric.RuntimeEnvironment.getApplication())

    @Test fun secondResumeDoesNotStartConcurrentJob() = runBlocking {
        val scheduler = AirCatResumeScheduler(this, lease())
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val count = AtomicInteger()
        assertTrue(scheduler.request("t", null, { true }, {}, {}, { throw it }) {
            count.incrementAndGet(); started.complete(Unit); release.await()
        })
        withTimeout(5_000) { started.await() }
        assertFalse(scheduler.request("t", null, { true }, {}, {}, { throw it }) {
            count.incrementAndGet()
        })
        release.complete(Unit)
        withTimeout(5_000) { while (scheduler.isScheduled("t")) delay(10) }
        assertEquals(1, count.get())
    }

    @Test fun busyLeaseNeverClaimsTransferStarted() = runBlocking {
        val held = lease()
        assertTrue(held.tryEnter())
        val scheduler = AirCatResumeScheduler(this, lease())
        val busy = CompletableDeferred<Unit>()
        val started = AtomicInteger()
        try {
            assertTrue(scheduler.request("other", null, { true }, { busy.complete(Unit) },
                { started.incrementAndGet() }, { throw it }) { started.incrementAndGet() })
            withTimeout(5_000) { busy.await() }
            assertEquals(0, started.get())
        } finally { held.leave() }
    }

    @Test fun cancellationOfPendingResumeDoesNotOpenTransfer() = runBlocking {
        val unfinished = CompletableDeferred<Unit>()
        val previous = Job()
        val scheduler = AirCatResumeScheduler(this, lease())
        val count = AtomicInteger()
        assertTrue(scheduler.request("waiting", previous, { true }, {}, {}, { throw it }) {
            count.incrementAndGet()
        })
        scheduler.cancel("waiting")
        previous.complete()
        withTimeout(5_000) { while (scheduler.isScheduled("waiting")) delay(10) }
        assertEquals(0, count.get())
    }

    @Test fun leaseIsReleasedWhenWorkThrows() = runBlocking {
        val scheduler = AirCatResumeScheduler(this, lease())
        val failed = CompletableDeferred<Unit>()
        assertTrue(scheduler.request("crashes", null, { true }, {}, {},
            { failed.complete(Unit) }) { error("expected") })
        withTimeout(5_000) { failed.await() }
        withTimeout(5_000) { while (scheduler.isScheduled("crashes")) delay(10) }
        val available = lease()
        assertTrue(available.tryEnter())
        try {
            val outcome = available.runExclusive { 4 }
            // This call is intentionally blocked because tryEnter above owns the gate.
            assertTrue(outcome is AirCatLeaseRun.Busy)
        } finally { available.leave() }
        assertTrue(lease().runExclusive { 4 } is AirCatLeaseRun.Completed)
    }

    @Test fun cancelKeepsReservationUntilNonCancellableCleanupFinishes() = runBlocking {
        val scheduler = AirCatResumeScheduler(this, lease())
        val entered = CompletableDeferred<Unit>()
        val cleanupStarted = CompletableDeferred<Unit>()
        val letCleanupFinish = CompletableDeferred<Unit>()
        assertTrue(scheduler.request("held", null, { true }, {}, {}, { throw it }) {
            try {
                entered.complete(Unit)
                awaitCancellation()
            } finally {
                withContext(NonCancellable) {
                    cleanupStarted.complete(Unit)
                    letCleanupFinish.await()
                }
            }
        })
        withTimeout(5_000) { entered.await() }
        val cancelling = scheduler.cancel("held")
        assertNotNull(cancelling)
        withTimeout(5_000) { cleanupStarted.await() }
        assertTrue(scheduler.isScheduled("held"))
        assertFalse(scheduler.request("held", null, { true }, {}, {}, { throw it }) {
            error("Duplicate must never run")
        })
        letCleanupFinish.complete(Unit)
        withTimeout(5_000) { cancelling!!.join() }
        withTimeout(5_000) { while (scheduler.isScheduled("held")) delay(10) }
        assertNull(scheduler.pendingJob("held"))
    }

    @Test fun cancelledScopeDoesNotLeaveStuckLazyReservation() = runBlocking {
        val parent = Job().also { it.cancel() }
        val scope = CoroutineScope(parent + Dispatchers.Unconfined)
        val scheduler = AirCatResumeScheduler(scope, lease())
        assertFalse(scheduler.request("aborted", null, { true }, {}, {}, { throw it }) {
            error("Cancelled scope must not initiate transfer")
        })
        assertFalse(scheduler.isScheduled("aborted"))
    }

    @Test fun settledCallbackRunsAfterCompletionAndOnlyOnce() = runBlocking {
        val scheduler = AirCatResumeScheduler(this, lease())
        val entered = CompletableDeferred<Unit>()
        val cleanup = CompletableDeferred<Unit>()
        val letCleanupFinish = CompletableDeferred<Unit>()
        val callbacks = AtomicInteger()
        assertTrue(scheduler.request("settled", null, { true }, {}, {}, { throw it },
            onSettled = {
                assertFalse(scheduler.isScheduled("settled"))
                callbacks.incrementAndGet()
                cleanup.complete(Unit)
            }) {
            try {
                entered.complete(Unit)
                awaitCancellation()
            } finally {
                withContext(NonCancellable) { letCleanupFinish.await() }
            }
        })
        withTimeout(5_000) { entered.await() }
        scheduler.cancel("settled")
        assertTrue(scheduler.isScheduled("settled"))
        letCleanupFinish.complete(Unit)
        withTimeout(5_000) { cleanup.await() }
        assertEquals(1, callbacks.get())
    }

    @Test fun settledCallbackRunsAlsoAfterSuccessfulResume() = runBlocking {
        val scheduler = AirCatResumeScheduler(this, lease())
        val closed = CompletableDeferred<Unit>()
        val callbacks = AtomicInteger()
        assertTrue(scheduler.request("ok", null, { true }, {}, {}, { throw it },
            onSettled = {
                assertFalse(scheduler.isScheduled("ok"))
                callbacks.incrementAndGet()
                closed.complete(Unit)
            }) { /* successful transfer */ })
        withTimeout(5_000) { closed.await() }
        assertEquals(1, callbacks.get())
    }
}
