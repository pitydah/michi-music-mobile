package org.michimusic.mobile.sync

import android.content.Context
import android.os.PowerManager
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowPowerManager

/**
 * PERF-02: the worker used to release its wake lock from about twenty separate branches.
 * Any path that missed one held a PARTIAL_WAKE_LOCK for the full thirty minute timeout.
 * The lease is now owned by a single `try/finally`, and these tests prove the lock is
 * released on the exit paths that need no network.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = android.app.Application::class)
class SyncWorkerWakeLockTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        ShadowPowerManager.clearWakeLocks()
    }

    private fun latestSyncLease(): PowerManager.WakeLock? =
        // Tag is not part of the public API; the test clears the registry beforehand, so
        // the latest lock is the one this worker just created.
        ShadowPowerManager.getLatestWakeLock()

    @Test
    fun `a worker without a target releases its wake lock`() = runBlocking {
        val worker = TestListenableWorkerBuilder<SyncWorker>(context).build()

        val result = worker.doWork()

        assertTrue("no baseUrl must fail fast", result is ListenableWorker.Result.Failure)
        val lease = latestSyncLease()
        assertNotNull("the worker must have taken a lease for the run", lease)
        assertFalse("the lease must be released on early exit", lease!!.isHeld)
    }

    @Test
    fun `an unpaired device releases its wake lock`() = runBlocking {
        val worker = TestListenableWorkerBuilder<SyncWorker>(context)
            .setInputData(
                workDataOf(
                    "baseUrl" to "http://127.0.0.1:53318",
                    "deviceId" to "co.michi.absent",
                ),
            )
            .build()

        val result = worker.doWork()

        assertTrue("an unpaired device must fail", result is ListenableWorker.Result.Failure)
        val lease = latestSyncLease()
        assertNotNull(lease)
        assertFalse("the lease must be released when credentials are missing", lease!!.isHeld)
    }

    @Test
    fun `repeated runs never leave a lease held`() = runBlocking {
        repeat(3) {
            val worker = TestListenableWorkerBuilder<SyncWorker>(context).build()
            worker.doWork()
        }

        val lease = latestSyncLease()
        assertNotNull(lease)
        assertFalse("a finished run must not keep the CPU awake", lease!!.isHeld)
    }
}
