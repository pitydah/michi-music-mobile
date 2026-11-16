package org.michimusic.mobile.devices.aircat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class AirCatTransferContinuityGateTest {
    private var now = 1000L
    private var cancelled = false
    private var paused = false
    private var revoked = false
    private var trustChecks = 0

    private fun gate(expiry: Long = 5000L) = AirCatTransferContinuityGate(
        nowMillis = { now },
        expiresAtMillis = expiry,
        isCancelled = { cancelled },
        isPaused = { paused },
        verifyTrust = {
            trustChecks++
            if (revoked) AirCatException.RevokedPeer("source") else null
        },
    )

    @Test fun verifiesHomeBeforeFirstByteAndZeroByteCachedReuse() {
        val guard = gate()
        guard.beforeWrite(0)
        guard.beforeWrite(1024)
        assertEquals(1, trustChecks)
    }

    @Test fun boundsExpensiveTrustLookupsByBytes() {
        val guard = gate()
        repeat(16) { guard.beforeWrite(65536) }
        assertEquals(1, trustChecks)
        guard.beforeWrite(1)
        assertEquals(2, trustChecks)
    }

    @Test fun reevaluatesTrustOnClockAdvance() {
        val guard = gate()
        guard.beforeWrite(4)
        now += 2000L
        revoked = true
        assertThrows(AirCatException.RevokedPeer::class.java) { guard.beforeWrite(4) }
        assertEquals(2, trustChecks)
    }

    @Test fun revocationStopsFurtherWritesAtBudgetBoundary() {
        val guard = gate()
        repeat(16) { guard.beforeWrite(65536) }
        revoked = true
        assertThrows(AirCatException.RevokedPeer::class.java) { guard.beforeWrite(1) }
    }

    @Test fun cancellationIsCheckedEveryChunkWithoutTrustLookup() {
        val guard = gate()
        guard.beforeWrite(1)
        cancelled = true
        assertThrows(AirCatException.ProtocolError::class.java) { guard.beforeWrite(1) }
        assertEquals(1, trustChecks)
    }

    @Test fun pauseIsCheckedEveryChunkWithoutTrustLookup() {
        val guard = gate()
        guard.beforeWrite(1)
        paused = true
        assertThrows(AirCatException.ProtocolError::class.java) { guard.beforeWrite(1) }
    }

    @Test fun expiryIsCheckedEveryChunk() {
        val guard = gate(expiry = 1005L)
        guard.beforeWrite(1)
        now = 1005L
        assertThrows(AirCatException.TransferExpired::class.java) { guard.beforeWrite(1) }
    }

    @Test fun clockRollbackForcesTrustRecheck() {
        val guard = gate()
        guard.beforeWrite(1)
        now = 999L
        guard.beforeWrite(1)
        assertEquals(2, trustChecks)
    }

    @Test fun beforeCommitRechecksHomeEvenBeforeByteAndClockThreshold() {
        val guard = gate()
        guard.beforeWrite(1)
        revoked = true
        assertThrows(AirCatException.RevokedPeer::class.java) { guard.beforeCommit() }
        assertEquals(2, trustChecks)
    }

    @Test fun beforeCommitRejectsPauseWithoutTrustLookup() {
        val guard = gate()
        guard.beforeWrite(1)
        paused = true
        assertThrows(AirCatException.ProtocolError::class.java) { guard.beforeCommit() }
        assertEquals(1, trustChecks)
    }

    @Test fun rejectedTrustDoesNotAdvanceReceipt() {
        revoked = true
        val guard = gate()
        assertThrows(AirCatException.RevokedPeer::class.java) { guard.beforeWrite(1) }
        assertEquals(1, trustChecks)
    }
}
