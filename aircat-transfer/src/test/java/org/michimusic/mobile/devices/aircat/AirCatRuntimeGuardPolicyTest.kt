package org.michimusic.mobile.devices.aircat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.michimusic.mobile.devices.aircat.port.PeerPairingStatus

class AirCatRuntimeGuardPolicyTest {
    private fun check(pairing: PeerPairingStatus = PeerPairingStatus.PAIRED_UNVERIFIED,
        token: Boolean=true, now:Long=10L, expiry:Long=20L,
        cancelled:Boolean=false, paused:Boolean=false) = AirCatRuntimeGuardPolicy.refuse(
        pairing, token, now, expiry, cancelled, paused)
    @Test fun pairedAndValidActiveSessionAllowed() { assertNull(check()) }
    @Test fun revokedPeerRejected() { assertEquals(AirCatRuntimeGuardPolicy.Refusal.REVOKED,
        check(pairing=PeerPairingStatus.REVOKED)) }
    @Test fun absentPeerRejected() { assertEquals(AirCatRuntimeGuardPolicy.Refusal.UNAUTHORIZED,
        check(pairing=PeerPairingStatus.MISSING)) }
    @Test fun mismatchedTokenRejected() { assertEquals(AirCatRuntimeGuardPolicy.Refusal.UNAUTHORIZED,
        check(token=false)) }
    @Test fun exactExpiryRejected() { assertEquals(AirCatRuntimeGuardPolicy.Refusal.EXPIRED,
        check(now=20L)) }
    @Test fun noExpiryLegacyAllowed() { assertNull(check(now=200L, expiry=0L)) }
    @Test fun cancellationWinsBeforeRevocation() { assertEquals(AirCatRuntimeGuardPolicy.Refusal.CANCELLED,
        check(cancelled=true, pairing=PeerPairingStatus.REVOKED)) }
    @Test fun pausedWinsBeforeExpiry() { assertEquals(AirCatRuntimeGuardPolicy.Refusal.PAUSED,
        check(paused=true, now=21L)) }
}
