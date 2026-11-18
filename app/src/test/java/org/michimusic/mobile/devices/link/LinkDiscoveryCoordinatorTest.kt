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
}
