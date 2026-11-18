package org.michimusic.mobile.devices.link

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.michimusic.link.LinkDiscovery

@OptIn(ExperimentalCoroutinesApi::class)
class LinkDiscoveryCoordinatorTest {
    @Test fun oneScanAndManualStopDoNotCreateAnAlwaysOnListener() = runTest {
        val discovery = mockk<LinkDiscovery>()
        coEvery { discovery.start() } coAnswers { awaitCancellation() }
        coEvery { discovery.stop(preservePeers = any()) } returns Unit
        var finished = 0
        val subject = LinkDiscoveryCoordinator(
            discovery, this, { finished++ }, StandardTestDispatcher(testScheduler)
        )
        assertTrue(subject.start())
        assertFalse(subject.start())
        runCurrent()
        coVerify(exactly = 1) { discovery.start() }
        assertTrue(subject.isActive.value)
        subject.stop(preserveResults = true)
        assertFalse(subject.isActive.value)
        runCurrent()
        // The scan's own finally and the stop path are independent idempotent guards, so
        // one stop may legitimately release twice. What matters is that the window ended.
        coVerify(atLeast = 1) { discovery.stop(preservePeers = true) }
        assertEquals(0, finished) // Explicit stop is not a completed scan window.
    }

    /**
     * A68-08: the Devices screen cancels its scope when the ViewModel dies. Teardown used
     * to be launched into that same scope, so the release job was dropped the moment it
     * was needed and the multicast socket stayed open with no owner.
     */
    @Test fun aDeadCallerScopeStillReleasesTheSocket() = runTest {
        val discovery = mockk<LinkDiscovery>()
        coEvery { discovery.start() } coAnswers { awaitCancellation() }
        coEvery { discovery.stop(preservePeers = any()) } returns Unit
        val deadScope = CoroutineScope(Job()).also { it.cancel() }
        val subject = LinkDiscoveryCoordinator(
            discovery, deadScope, { }, StandardTestDispatcher(testScheduler),
        )

        subject.start()
        subject.stop(preserveResults = false)
        advanceUntilIdle()

        coVerify(atLeast = 1) { discovery.stop(preservePeers = false) }
        assertFalse(subject.isActive.value)
    }

    @Test fun closingReleasesOnceAndShutsTheTeardownScopeDown() = runTest {
        val discovery = mockk<LinkDiscovery>()
        coEvery { discovery.start() } coAnswers { awaitCancellation() }
        coEvery { discovery.stop(preservePeers = any()) } returns Unit
        val subject = LinkDiscoveryCoordinator(
            discovery, this, { }, StandardTestDispatcher(testScheduler),
        )

        subject.start()
        runCurrent()
        subject.close()
        subject.close()
        advanceUntilIdle()

        coVerify(atLeast = 1) { discovery.stop(preservePeers = false) }
    }

    @Test fun immediateRestartWaitsUntilOldSocketIsClosed() = runTest {
        val discovery = mockk<LinkDiscovery>()
        val finishShutdown = CompletableDeferred<Unit>()
        coEvery { discovery.start() } coAnswers { awaitCancellation() }
        coEvery { discovery.stop(preservePeers = any()) } coAnswers { finishShutdown.await() }
        val subject = LinkDiscoveryCoordinator(
            discovery, this, { }, StandardTestDispatcher(testScheduler)
        )
        subject.start()
        runCurrent()
        subject.stop(preserveResults = true)
        assertTrue(subject.start())
        runCurrent()
        coVerify(exactly = 1) { discovery.start() }
        finishShutdown.complete(Unit)
        runCurrent()
        coVerify(exactly = 2) { discovery.start() }
        subject.stop()
        runCurrent()
    }

    /**
     * A69-04: cancelling the caller's scope while the scan is listening must still release
     * the multicast socket and lock. The scan's own finally does it, independent of who
     * cancelled whom.
     */
    @Test fun cancellingTheCallerScopeMidScanReleasesTheSocket() = runTest {
        val discovery = mockk<LinkDiscovery>()
        coEvery { discovery.start() } coAnswers { awaitCancellation() }
        coEvery { discovery.stop(preservePeers = any()) } returns Unit
        val callerScope = CoroutineScope(Job() + StandardTestDispatcher(testScheduler))
        val subject = LinkDiscoveryCoordinator(
            discovery, callerScope, { }, StandardTestDispatcher(testScheduler),
        )

        subject.start()
        runCurrent()
        coVerify(exactly = 1) { discovery.start() }

        callerScope.cancel()
        advanceUntilIdle()

        coVerify(atLeast = 1) { discovery.stop(preservePeers = false) }
        assertFalse(subject.isActive.value)
    }

    /**
     * A69-04: a scan cancelled before its dispatcher ever ran it completes only if
     * something completes it. Joining that job would wedge the teardown that exists to
     * release the socket, so it must be skipped and the release must still happen - and a
     * later window must still be startable.
     */
    @Test fun aScanCancelledBeforeItWasDispatchedStillReleasesAndDoesNotWedge() = runTest {
        val discovery = mockk<LinkDiscovery>()
        coEvery { discovery.start() } coAnswers { awaitCancellation() }
        coEvery { discovery.stop(preservePeers = any()) } returns Unit
        val subject = LinkDiscoveryCoordinator(
            discovery, this, { }, StandardTestDispatcher(testScheduler),
        )

        assertTrue(subject.start())
        subject.stop(preserveResults = false) // before runCurrent: never dispatched
        advanceUntilIdle()

        coVerify(atLeast = 1) { discovery.stop(preservePeers = false) }
        assertFalse(subject.isActive.value)
        assertTrue("a released window must not block the next one", subject.start())
        subject.stop()
        advanceUntilIdle()
    }

    @Test fun twoConsecutiveStopsAreIdempotentAndLeaveNoListener() = runTest {
        val discovery = mockk<LinkDiscovery>()
        coEvery { discovery.start() } coAnswers { awaitCancellation() }
        coEvery { discovery.stop(preservePeers = any()) } returns Unit
        val subject = LinkDiscoveryCoordinator(
            discovery, this, { }, StandardTestDispatcher(testScheduler),
        )

        subject.start()
        runCurrent()
        subject.stop(preserveResults = false)
        subject.stop(preserveResults = false)
        advanceUntilIdle()

        coVerify(atLeast = 1) { discovery.stop(preservePeers = false) }
        assertFalse(subject.isActive.value)
        assertTrue("two stops must not spend the next window", subject.start())
        subject.stop()
        advanceUntilIdle()
    }

    /**
     * A69-04 energy policy: the window ends by itself at 35 s. No listener may outlive it
     * and no polling may follow.
     */
    @Test fun theWindowClosesItselfAfterThirtyFiveSeconds() = runTest {
        val discovery = mockk<LinkDiscovery>()
        coEvery { discovery.start() } coAnswers { awaitCancellation() }
        coEvery { discovery.stop(preservePeers = any()) } returns Unit
        var finished = 0
        val subject = LinkDiscoveryCoordinator(
            discovery, this, { finished++ }, StandardTestDispatcher(testScheduler),
        )

        subject.start()
        runCurrent()
        advanceTimeBy(34_000)
        runCurrent()
        assertTrue("the window must still be open one second before the budget", subject.isActive.value)

        advanceTimeBy(1_000)
        advanceUntilIdle()

        assertFalse(subject.isActive.value)
        assertEquals("a self-closed window reports itself exactly once", 1, finished)
        coVerify(atLeast = 1) { discovery.stop(preservePeers = false) }
        assertTrue("no listener may survive the window", subject.start())
        subject.stop()
        advanceUntilIdle()
    }

    @Test fun startingAfterCloseIsRefused() = runTest {
        val discovery = mockk<LinkDiscovery>()
        coEvery { discovery.start() } coAnswers { awaitCancellation() }
        coEvery { discovery.stop(preservePeers = any()) } returns Unit
        val subject = LinkDiscoveryCoordinator(
            discovery, this, { }, StandardTestDispatcher(testScheduler),
        )

        subject.close()
        advanceUntilIdle()
        assertFalse("a closed coordinator must not open a window", subject.start())
        advanceUntilIdle()
        coVerify(exactly = 0) { discovery.start() }
    }

    @Test fun stoppingBeforeAnyScanStillReleasesAndReportsNoWindow() = runTest {
        val discovery = mockk<LinkDiscovery>()
        coEvery { discovery.start() } coAnswers { awaitCancellation() }
        coEvery { discovery.stop(preservePeers = any()) } returns Unit
        var finished = 0
        val subject = LinkDiscoveryCoordinator(
            discovery, this, { finished++ }, StandardTestDispatcher(testScheduler),
        )

        subject.stop(preserveResults = false)
        advanceUntilIdle()

        coVerify(atLeast = 1) { discovery.stop(preservePeers = false) }
        assertEquals("a stop that ended no window must not report one", 0, finished)
        assertFalse(subject.isActive.value)
    }
}
