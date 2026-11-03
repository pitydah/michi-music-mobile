package org.michimusic.mobile.sync

import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.OverlappingFileLockException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * AIR-02: `SyncViewModel.acquireAirCatLease()` relies on a FileLock for cross-process
 * exclusion, and a FileLock is only meaningful across processes. The probe provider
 * takes the lock from another process on the same descriptor, so the guarantee is
 * observed instead of assumed.
 */
@RunWith(AndroidJUnit4::class)
class AirCatCrossProcessLockTest {

    private val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
    private val testContext = InstrumentationRegistry.getInstrumentation().context
    private val probeUri = Uri.parse("content://${testContext.packageName}.aircatlockprobe")
    private val lockFile: File get() = File(targetContext.filesDir, LOCK_FILE_NAME)

    @Test
    fun aSecondProcessCannotTakeALockTheTestProcessHolds() {
        RandomAccessFile(lockFile, "rw").channel.use { channel ->
            val held = channel.tryLock()
            assertNotNull("the test process must be able to take the lock", held)
            assertFalse(
                "another process must not take a held lock",
                probe(AirCatLockProbeProvider.METHOD_ACQUIRE, descriptor()),
            )

            held!!.release()
            assertTrue(
                "the helper process must take the lock once it is free",
                probe(AirCatLockProbeProvider.METHOD_ACQUIRE, descriptor()),
            )
            probe(AirCatLockProbeProvider.METHOD_RELEASE)
        }
    }

    @Test
    fun theSameProcessCannotTakeTheSameLockTwice() {
        RandomAccessFile(lockFile, "rw").channel.use { channel ->
            channel.tryLock().use { first ->
                assertNotNull(first)
                val second = runCatching { channel.tryLock() }
                assertTrue(
                    "a second FileLock in one process must fail instead of pretending exclusivity",
                    second.exceptionOrNull() is OverlappingFileLockException,
                )
            }
        }
    }

    @Test
    fun theLockIsReleasedWhenTheOwnerDropsItsChannel() = runBlocking {
        val descriptor = descriptor()
        assertTrue(
            "the helper process must hold the lock first",
            probe(AirCatLockProbeProvider.METHOD_ACQUIRE, descriptor),
        )
        runCatching { descriptor.close() }

        // The helper closes its channel while still holding the lock. Linux drops the advisory
        // lock when the last descriptor closes, which is the same guarantee that protects a
        // crashed AirCat owner. Killing the process is not observable here: an androidTest
        // provider runs inside the instrumented process, so killing it aborts the whole run.
        probe(AirCatLockProbeProvider.METHOD_CLOSE)

        val reacquired = withTimeout(15_000) {
            var acquired = false
            while (!acquired) {
                val channel = RandomAccessFile(lockFile, "rw").channel
                val lock = runCatching { channel.tryLock() }.getOrNull()
                if (lock != null) {
                    lock.release()
                    channel.close()
                    acquired = true
                } else {
                    channel.close()
                    delay(150)
                }
            }
            acquired
        }

        assertTrue("a dropped channel must not keep the AirCat lock forever", reacquired)
    }

    private fun descriptor(): ParcelFileDescriptor = ParcelFileDescriptor.open(
        lockFile,
        ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_CREATE,
    )

    private fun probe(method: String, descriptor: ParcelFileDescriptor? = null): Boolean {
        val extras = Bundle().apply {
            if (descriptor != null) putParcelable(AirCatLockProbeProvider.KEY_DESCRIPTOR, descriptor)
        }
        val bundle: Bundle = testContext.contentResolver.call(probeUri, method, null, extras) ?: Bundle()
        return bundle.getBoolean(AirCatLockProbeProvider.KEY_ACQUIRED, false)
    }

    private companion object {
        const val LOCK_FILE_NAME = "michi_aircat_transfer.lock"
    }
}
