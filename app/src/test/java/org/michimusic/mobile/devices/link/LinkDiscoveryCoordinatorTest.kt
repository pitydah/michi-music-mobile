package org.michimusic.mobile.devices.link

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
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
        val subject = LinkDiscoveryCoordinator(discovery, this) { finished++ }
        assertTrue(subject.start())
        assertFalse(subject.start())
        runCurrent()
        coVerify(exactly = 1) { discovery.start() }
        assertTrue(subject.isActive.value)
        subject.stop(preserveResults = true)
        assertFalse(subject.isActive.value)
        runCurrent()
        coVerify(exactly = 1) { discovery.stop(preservePeers = true) }
        assertEquals(0, finished) // Explicit stop is not a completed scan window.
    }

    @Test fun immediateRestartWaitsUntilOldSocketIsClosed() = runTest {
        val discovery = mockk<LinkDiscovery>()
        val finishShutdown = CompletableDeferred<Unit>()
        coEvery { discovery.start() } coAnswers { awaitCancellation() }
        coEvery { discovery.stop(preservePeers = any()) } coAnswers { finishShutdown.await() }
        val subject = LinkDiscoveryCoordinator(discovery, this) { }
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
