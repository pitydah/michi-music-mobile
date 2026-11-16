package org.michimusic.mobile.devices.link

import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.michimusic.core.models.DiscoveredPeer
import org.michimusic.core.models.SyncConnectionState
import org.michimusic.link.PairedDevice
import org.michimusic.link.PairedDeviceRegistry

class LinkDeviceRosterProjectorTest {
    @Test fun reusedIpDoesNotImpersonateStoredMichiDeviceId() {
        val registry = mockk<PairedDeviceRegistry>()
        every { registry.getAllDevices() } returns listOf(
            PairedDevice(deviceId = "trusted", deviceName = "Micro", deviceToken = "secret",
                lastUrl = "http://10.0.0.2:8400", roles = listOf("sync_host")),
        )
        val roster = LinkDeviceRosterProjector(registry).project(
            peers = listOf(DiscoveredPeer(alias = "Other", ip = "10.0.0.2", port = 8400, deviceId = "spoof")),
            connectionStates = emptyMap(),
        )
        assertEquals(2, roster.size)
        assertEquals(SyncConnectionState.OFFLINE, roster.single { it.id == "trusted" }.connectionState)
        val unknown = roster.single { it.id == "spoof" }
        assertFalse(unknown.isPaired)
        assertTrue(unknown.roles.isEmpty())
        assertTrue(unknown.features.isEmpty())
    }

    @Test fun cachedPairedPeerRemainsVisibleWithoutDiscovery() {
        val registry = mockk<PairedDeviceRegistry>()
        every { registry.getAllDevices() } returns listOf(
            PairedDevice(deviceId = "micro", deviceName = "Micro", deviceToken = "secret",
                lastUrl = "http://192.168.1.7:8400"),
        )
        val result = LinkDeviceRosterProjector(registry).project(emptyList(), emptyMap())
        assertEquals(1, result.size)
        assertTrue(result.single().isPaired)
        assertEquals(SyncConnectionState.OFFLINE, result.single().connectionState)
    }
    @Test fun unsignedSpoofCannotChangePairedDeviceLabelOrIp() {
        val registry = mockk<PairedDeviceRegistry>()
        every { registry.getAllDevices() } returns listOf(
            PairedDevice(deviceId = "micro", deviceName = "Trusted Micro", deviceToken = "secret",
                michiId = "pinned-michi", lastUrl = "https://10.0.0.2:8400"),
        )
        val roster = LinkDeviceRosterProjector(registry).project(
            peers = listOf(DiscoveredPeer(alias = "Evil Micro", ip = "10.0.0.9", port = 8400,
                deviceId = "micro", identityTrust = "UNTRUSTED")),
            connectionStates = emptyMap(),
        )
        val trusted = roster.single { it.isPaired }
        assertEquals("Trusted Micro", trusted.name)
        assertEquals("10.0.0.2", trusted.ip)
        assertEquals(SyncConnectionState.OFFLINE, trusted.connectionState)
    }

    @Test fun signedDifferentMichiIdCannotRewritePreviouslyEnrolledIdentity() {
        val registry = mockk<PairedDeviceRegistry>()
        every { registry.getAllDevices() } returns listOf(
            PairedDevice(deviceId = "micro", deviceName = "Trusted Micro", deviceToken = "secret",
                michiId = "pinned-michi", lastUrl = "https://10.0.0.2:8400"),
        )
        val roster = LinkDeviceRosterProjector(registry).project(
            peers = listOf(DiscoveredPeer(alias = "Other Host", ip = "10.0.0.9", port = 8400,
                deviceId = "micro", michiId = "different-michi", identityTrust = "VERIFIED")),
            connectionStates = emptyMap(),
        )
        assertEquals("Trusted Micro", roster.single { it.isPaired }.name)
        assertEquals("10.0.0.2", roster.single { it.isPaired }.ip)
    }

    @Test fun matchingSignedMichiIdCannotReroutePinnedEndpointWithoutAuthenticatedConnection() {
        val registry = mockk<PairedDeviceRegistry>()
        every { registry.getAllDevices() } returns listOf(
            PairedDevice(deviceId = "micro", deviceName = "Trusted Micro", deviceToken = "secret",
                michiId = "pinned-michi", lastUrl = "https://10.0.0.2:8400", features = listOf("player")),
        )
        val roster = LinkDeviceRosterProjector(registry).project(
            peers = listOf(DiscoveredPeer(alias = "Renamed", ip = "10.0.0.8", port = 8400,
                deviceId = "micro", michiId = "pinned-michi", identityTrust = "VERIFIED")),
            connectionStates = emptyMap(),
        )
        assertEquals(1, roster.size)
        assertEquals("Renamed", roster.single().name)
        assertEquals("10.0.0.2", roster.single().ip)
        assertEquals(listOf("player"), roster.single().features)
        assertEquals(SyncConnectionState.DISCONNECTED, roster.single().connectionState)
    }

    @Test fun legacyPairedRegistryCannotAdoptUnsignedIpEvenWithSameDeviceId() {
        val registry = mockk<PairedDeviceRegistry>()
        every { registry.getAllDevices() } returns listOf(
            PairedDevice(deviceId = "micro", deviceName = "Cached", deviceToken = "secret",
                lastUrl = "http://192.168.1.2:8400"),
        )
        val roster = LinkDeviceRosterProjector(registry).project(
            peers = listOf(DiscoveredPeer(alias = "Spoof", ip = "192.168.1.3", port = 8400,
                deviceId = "micro", identityTrust = "UNTRUSTED")), emptyMap())
        assertEquals("Cached", roster.single { it.isPaired }.name)
        assertEquals("192.168.1.2", roster.single { it.isPaired }.ip)
    }

    @Test fun signedPinnedIdentityCanChangeLegacyDeviceIdWithoutLosingRoster() {
        val registry = mockk<PairedDeviceRegistry>()
        every { registry.getAllDevices() } returns listOf(
            PairedDevice(deviceId = "old-id", deviceName = "Cached", deviceToken = "secret",
                michiId = "pinned-michi", lastUrl = "https://10.0.0.2:8400"),
        )
        val roster = LinkDeviceRosterProjector(registry).project(
            peers = listOf(DiscoveredPeer(alias = "Migrated", ip = "10.0.0.3", port = 8400,
                deviceId = "new-id", michiId = "pinned-michi", identityTrust = "VERIFIED")), emptyMap())
        assertEquals(1, roster.size)
        assertEquals("Migrated", roster.single().name)
        assertEquals("10.0.0.2", roster.single().ip)
        assertEquals("old-id", roster.single().id)
    }

}
