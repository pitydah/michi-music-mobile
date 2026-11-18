package org.michimusic.mobile.devices.aircat.port

import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.michimusic.link.PairedDevice
import org.michimusic.link.PairedDeviceRegistry

class PeerTrustPortTest {
    private val registry = mockk<PairedDeviceRegistry>()
    private val port = RegistryPeerTrustPort(registry)

    @Test fun unrecognizedDeviceFailsClosed() {
        every { registry.getDevice("unknown") } returns null
        assertEquals(PeerPairingStatus.MISSING, port.status("unknown"))
        assertFalse(port.matchesSessionToken("unknown", "presented"))
        assertEquals(PeerPairingStatus.MISSING, port.status(""))
        assertFalse(port.matchesSessionToken("", "presented"))
    }

    @Test fun revokedDeviceFailsClosedWithoutExposingToken() {
        every { registry.getDevice("revoked") } returns PairedDevice(
            deviceId = "revoked", deviceToken = "secret", isRevoked = true,
        )
        assertEquals(PeerPairingStatus.REVOKED, port.status("revoked"))
        assertFalse(port.matchesSessionToken("revoked", "secret"))
    }

    @Test fun pairedSnapshotIsNotConfusedWithVerifiedIdentity() {
        every { registry.getDevice("paired") } returns PairedDevice(
            deviceId = "paired", deviceToken = "secret",
        )
        assertEquals(PeerPairingStatus.PAIRED_UNVERIFIED, port.status("paired"))
        assertTrue(port.matchesSessionToken("paired", "secret"))
        assertFalse(port.matchesSessionToken("paired", "other"))
        assertFalse(port.matchesSessionToken("paired", ""))
    }
}
