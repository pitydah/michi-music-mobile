package org.michimusic.mobile.devices.aircat

import android.content.Context
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class AirCatLeaseCoordinatorTest {
    @Test fun processGatePreventsConcurrentIndependentOwners() {
        val dir = Files.createTempDirectory("aircat-lease-gate").toFile()
        val context = mockk<Context>()
        every { context.filesDir } returns dir
        val first = AirCatLeaseCoordinator(context)
        val second = AirCatLeaseCoordinator(context)
        try {
            assertTrue(first.tryEnter())
            assertFalse(second.tryEnter())
        } finally {
            first.leave()
            dir.deleteRecursively()
        }
        assertTrue(second.tryEnter())
        second.leave()
    }

    @Test fun fileLeaseDoesNotOverlapAndCanBeReacquired() = runBlocking {
        val dir = Files.createTempDirectory("aircat-lease-file").toFile()
        val context = mockk<Context>()
        every { context.filesDir } returns dir
        val first = AirCatLeaseCoordinator(context)
        val second = AirCatLeaseCoordinator(context)
        try {
            val one = first.acquireFileLease()
            assertNotNull(one)
            try {
                assertNull(second.acquireFileLease())
            } finally {
                one!!.second.release()
                one.first.close()
            }
            val reopened = second.acquireFileLease()
            assertNotNull(reopened)
            reopened!!.second.release()
            reopened.first.close()
        } finally { dir.deleteRecursively() }
    }

    @Test fun anotherInstanceCannotReleaseGateItDoesNotOwn() {
        val dir = Files.createTempDirectory("aircat-lease-owner").toFile()
        val context = mockk<Context>()
        every { context.filesDir } returns dir
        val owner = AirCatLeaseCoordinator(context)
        val nonOwner = AirCatLeaseCoordinator(context)
        try {
            assertTrue(owner.tryEnter())
            nonOwner.leave()
            assertFalse(nonOwner.tryEnter())
            owner.leave()
            assertTrue(nonOwner.tryEnter())
            nonOwner.leave()
        } finally {
            dir.deleteRecursively()
        }
    }
}
