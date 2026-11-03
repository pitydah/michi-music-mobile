package org.michimusic.mobile.sync

import androidx.work.NetworkType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * PERF-01: the periodic mirror must not run when nobody asked for it, and when it runs
 * it must wait for a usable device state instead of failing on a dead network.
 */
class PeriodicSyncPolicyTest {

    @Test
    fun `a disabled preference cancels the work instead of leaving it queued`() {
        assertEquals(
            PeriodicSyncDecision.Cancel,
            periodicSyncDecision(autoSyncEnabled = false, hasPairedDevice = true),
        )
    }

    @Test
    fun `a revoked or missing pairing cancels even when the preference is on`() {
        assertEquals(
            PeriodicSyncDecision.Cancel,
            periodicSyncDecision(autoSyncEnabled = true, hasPairedDevice = false),
        )
    }

    @Test
    fun `the mirror is scheduled only with both consent and a target`() {
        assertEquals(
            PeriodicSyncDecision.Schedule,
            periodicSyncDecision(autoSyncEnabled = true, hasPairedDevice = true),
        )
    }

    @Test
    fun `periodic work requires a network`() {
        assertEquals(NetworkType.CONNECTED, periodicSyncConstraints().requiredNetworkType)
    }

    @Test
    fun `periodic work stands down on a low battery and a full disk`() {
        val constraints = periodicSyncConstraints()
        assertEquals(true, constraints.requiresBatteryNotLow())
        assertEquals(true, constraints.requiresStorageNotLow())
    }

    @Test
    fun `periodic work does not demand an unmetered network by default`() {
        // Metered is still a working network; forcing unmetered would silently disable
        // the mirror for every user on mobile data.
        assertFalse(periodicSyncConstraints().requiredNetworkType == NetworkType.UNMETERED)
    }
}
