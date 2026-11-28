package org.michimusic.mobile.devices.link

import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.michimusic.core.models.DeviceAuthorization
import org.michimusic.core.models.DeviceAvailability
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

    @Test fun canonicalIdentityComesFromTheRegistryNotFromTheRegistryId() {
        val registry = mockk<PairedDeviceRegistry>()
        every { registry.getAllDevices() } returns listOf(
            PairedDevice(deviceId = "registry-id", deviceName = "Micro", deviceToken = "secret",
                michiId = "canonical-michi", lastUrl = "https://10.0.0.2:8400"),
        )
        val device = LinkDeviceRosterProjector(registry).project(emptyList(), emptyMap()).single()
        assertEquals("registry-id", device.id)
        assertEquals("canonical-michi", device.canonicalMichiId)
        assertNotEquals(device.id, device.canonicalMichiId)
    }

    @Test fun legacyRegistryMichiIdIsTheCanonicalIdentity() {
        val registry = mockk<PairedDeviceRegistry>()
        every { registry.getAllDevices() } returns listOf(
            PairedDevice(deviceId = "legacy-id", deviceName = "Legacy", deviceToken = "secret",
                michiId = "legacy-michi", lastUrl = "https://10.0.0.2:8400"),
        )
        val device = LinkDeviceRosterProjector(registry).project(emptyList(), emptyMap()).single()
        assertEquals("legacy-id", device.id)
        assertEquals("legacy-michi", device.canonicalMichiId)
    }

    @Test fun remoteMichiIdWinsOverTheStoredLocalOne() {
        val registry = mockk<PairedDeviceRegistry>()
        every { registry.getAllDevices() } returns listOf(
            PairedDevice(deviceId = "registry-id", deviceName = "Micro", deviceToken = "secret",
                michiId = "local-michi", remoteMichiId = "remote-michi",
                lastUrl = "https://10.0.0.2:8400"),
        )
        val device = LinkDeviceRosterProjector(registry).project(emptyList(), emptyMap()).single()
        assertEquals("remote-michi", device.canonicalMichiId)
    }

    @Test fun registryWithoutVerifiedIdentityLeavesTheSourceUnanchored() {
        val registry = mockk<PairedDeviceRegistry>()
        every { registry.getAllDevices() } returns listOf(
            PairedDevice(deviceId = "registry-id", deviceName = "Micro", deviceToken = "secret",
                lastUrl = "https://10.0.0.2:8400"),
        )
        val roster = LinkDeviceRosterProjector(registry).project(emptyList(), emptyMap())
        assertNull(roster.first { it.isPaired }.canonicalMichiId)
    }


    @Test fun reachableLegacyPeerIsNotHomeAuthorized() {
        val registry = mockk<PairedDeviceRegistry>()
        every { registry.getAllDevices() } returns listOf(
            PairedDevice(deviceId = "micro", deviceName = "Micro", deviceToken = "secret",
                michiId = "pinned", lastUrl = "https://10.0.0.2:8400",
                featuresMap = mapOf("playback" to kotlinx.serialization.json.JsonPrimitive(true))),
        )
        val device = LinkDeviceRosterProjector(registry).project(
            emptyList(), mapOf("micro" to SyncConnectionState.CONNECTED),
        ).single()
        assertEquals(DeviceAvailability.REACHABLE, device.accessState.availability)
        assertTrue(device.accessState.identityVerified)
        assertFalse(device.accessState.homeAuthorized)
        assertTrue(device.accessState.authenticated)
        assertTrue(device.accessState.capabilitiesVerified)
        assertEquals(DeviceAuthorization.LEGACY_PAIRED, device.accessState.authorization)
    }

    @Test fun homeEnrolledWithoutSessionIsNotAuthenticated() {
        val registry = mockk<PairedDeviceRegistry>()
        every { registry.getAllDevices() } returns listOf(
            PairedDevice(deviceId = "micro", deviceName = "Micro", deviceToken = "",
                michiId = "pinned", lastUrl = "https://10.0.0.2:8400"),
        )
        val device = LinkDeviceRosterProjector(
            registry,
            homeAuthorized = { it == "pinned" },
            homeAuthenticated = { false },
        ).project(emptyList(), mapOf("micro" to SyncConnectionState.CONNECTED)).single()
        assertTrue(device.accessState.homeAuthorized)
        assertFalse(device.accessState.authenticated)
        assertEquals(DeviceAuthorization.HOME_ENROLLED, device.accessState.authorization)
    }

    @Test fun homeAuthenticatedRequiresTheAuthorityLease() {
        val registry = mockk<PairedDeviceRegistry>()
        every { registry.getAllDevices() } returns listOf(
            PairedDevice(deviceId = "micro", deviceName = "Micro", deviceToken = "",
                michiId = "pinned", lastUrl = "https://10.0.0.2:8400"),
        )
        val device = LinkDeviceRosterProjector(
            registry,
            homeAuthorized = { it == "pinned" },
            homeAuthenticated = { it == "pinned" },
        ).project(emptyList(), mapOf("micro" to SyncConnectionState.CONNECTED)).single()
        assertTrue(device.accessState.authenticated)
        assertEquals(DeviceAuthorization.HOME_AUTHENTICATED, device.accessState.authorization)
    }

    @Test fun verifiedDiscoveryIsIdentityOnlyNeverHomeOrCapabilities() {
        val registry = mockk<PairedDeviceRegistry>()
        every { registry.getAllDevices() } returns emptyList()
        val peer = LinkDeviceRosterProjector(registry).project(
            listOf(DiscoveredPeer(alias = "Peer", ip = "10.0.0.9", port = 8400,
                deviceId = "peer", michiId = "peer-michi", identityTrust = "VERIFIED")),
            emptyMap(),
        ).single()
        assertEquals(DeviceAvailability.DISCOVERED, peer.accessState.availability)
        assertTrue(peer.accessState.identityVerified)
        assertFalse(peer.accessState.homeAuthorized)
        assertFalse(peer.accessState.authenticated)
        assertFalse(peer.accessState.capabilitiesVerified)
        assertEquals(DeviceAuthorization.DISCOVERED_IDENTITY, peer.accessState.authorization)
    }

    @Test fun unsignedDiscoveryHasNoVerifiedIdentityOrAuthorization() {
        val registry = mockk<PairedDeviceRegistry>()
        every { registry.getAllDevices() } returns emptyList()
        val peer = LinkDeviceRosterProjector(registry).project(
            listOf(DiscoveredPeer(alias = "Peer", ip = "10.0.0.9", port = 8400,
                deviceId = "peer", identityTrust = "UNTRUSTED")),
            emptyMap(),
        ).single()
        assertFalse(peer.accessState.identityVerified)
        assertEquals(DeviceAuthorization.NONE, peer.accessState.authorization)
    }

    @Test fun revokedPairedPeerStaysRevokedEvenWhenConnected() {
        val registry = mockk<PairedDeviceRegistry>()
        every { registry.getAllDevices() } returns listOf(
            PairedDevice(deviceId = "micro", deviceName = "Micro", deviceToken = "secret",
                michiId = "pinned", lastUrl = "https://10.0.0.2:8400", isRevoked = true),
        )
        val device = LinkDeviceRosterProjector(registry).project(
            emptyList(), mapOf("micro" to SyncConnectionState.CONNECTED),
        ).single()
        assertEquals(DeviceAuthorization.REVOKED, device.accessState.authorization)
        assertFalse(device.accessState.authenticated)
    }

    @Test fun verifiedDiscoveryCannotInventACanonicalIdentity() {
        val registry = mockk<PairedDeviceRegistry>()
        every { registry.getAllDevices() } returns listOf(
            PairedDevice(deviceId = "registry-id", deviceName = "Micro", deviceToken = "secret",
                lastUrl = "https://10.0.0.2:8400"),
        )
        val roster = LinkDeviceRosterProjector(registry).project(
            peers = listOf(DiscoveredPeer(alias = "Micro", ip = "10.0.0.2", port = 8400,
                deviceId = "peer-id", michiId = "peer-michi", identityTrust = "VERIFIED")), emptyMap())
        assertNull(roster.first { it.isPaired }.canonicalMichiId)
    }

    @Test fun revokedRegistryKeepsItsCanonicalIdentityAndRevocationFlag() {
        val registry = mockk<PairedDeviceRegistry>()
        every { registry.getAllDevices() } returns listOf(
            PairedDevice(deviceId = "registry-id", deviceName = "Micro", deviceToken = "secret",
                michiId = "canonical-michi", lastUrl = "https://10.0.0.2:8400", isRevoked = true),
        )
        val device = LinkDeviceRosterProjector(registry).project(emptyList(), emptyMap()).single()
        assertTrue(device.isRevoked)
        assertEquals("canonical-michi", device.canonicalMichiId)
    }

    @Test fun duplicateNamesKeepDistinctCanonicalIdentities() {
        val registry = mockk<PairedDeviceRegistry>()
        every { registry.getAllDevices() } returns listOf(
            PairedDevice(deviceId = "id-a", deviceName = "Michi", deviceToken = "secret-a",
                michiId = "canonical-a", lastUrl = "https://10.0.0.2:8400"),
            PairedDevice(deviceId = "id-b", deviceName = "Michi", deviceToken = "secret-b",
                michiId = "canonical-b", lastUrl = "https://10.0.0.3:8400"),
        )
        val devices = LinkDeviceRosterProjector(registry).project(emptyList(), emptyMap())
        assertEquals(2, devices.size)
        assertNotEquals(devices[0].canonicalMichiId, devices[1].canonicalMichiId)
        assertEquals(setOf("canonical-a", "canonical-b"), devices.map { it.canonicalMichiId }.toSet())
    }

}
