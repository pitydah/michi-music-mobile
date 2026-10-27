package org.michimusic.mobile.sync

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.michimusic.core.models.UnifiedDevice
import org.michimusic.core.models.SyncConnectionState

class DeviceActionResolverTest {

    @Test
    fun disconnectedPeer_returnsConnectActionOnly() {
        val device = UnifiedDevice(
            id = "1",
            name = "Micro Server",
            ip = "192.168.1.100",
            port = 8400,
            deviceType = "server",
            connectionState = SyncConnectionState.DISCONNECTED,
            isPaired = false,
            roles = emptyList()
        )

        val actions = DeviceActionResolver.resolveActions(
            device = device,
            isConnecting = false,
        )

        assertEquals(1, actions.size)
        assertEquals(DeviceActionType.CONNECT, actions.first().type)
        assertEquals("Vincular", actions.first().label)
        assertTrue(actions.first().isPrimary)
    }

    @Test
    fun connectingPeer_returnsConnectingState() {
        val device = UnifiedDevice(
            id = "1",
            name = "PC",
            ip = "192.168.1.50",
            port = 8400,
            deviceType = "desktop",
            connectionState = SyncConnectionState.CONNECTING,
            isPaired = false,
            roles = emptyList()
        )

        val actions = DeviceActionResolver.resolveActions(
            device = device,
            isConnecting = true,
        )

        assertEquals(1, actions.size)
        assertEquals("Conectando...", actions.first().label)
    }

    @Test
    fun connectedServer_returnsLibraryAndSyncActions() {
        val device = UnifiedDevice(
            id = "1",
            name = "Michi Server",
            ip = "192.168.1.100",
            port = 8400,
            deviceType = "server",
            connectionState = SyncConnectionState.CONNECTED,
            isPaired = true,
            roles = listOf("music_server", "sync_host")
        )

        val actions = DeviceActionResolver.resolveActions(
            device = device,
            isConnecting = false,
        )

        val types = actions.map { it.type }
        assertTrue(types.contains(DeviceActionType.BROWSE_LIBRARY))
        assertTrue(types.contains(DeviceActionType.SYNC_LIBRARY))
        assertTrue(types.contains(DeviceActionType.DISCONNECT))
    }

    @Test
    fun connectedDesktopPlayer_returnsControlAndContinueActions() {
        val device = UnifiedDevice(
            id = "1",
            name = "Desktop Player",
            ip = "192.168.1.55",
            port = 8400,
            deviceType = "desktop",
            connectionState = SyncConnectionState.CONNECTED,
            isPaired = true,
            roles = listOf("playback_host")
        )

        val actions = DeviceActionResolver.resolveActions(
            device = device,
            isConnecting = false,
        )

        val types = actions.map { it.type }
        assertTrue(types.contains(DeviceActionType.CONTROL_PLAYBACK))
        assertTrue(types.contains(DeviceActionType.CONTINUE_PLAYBACK_HERE))
        assertTrue(types.contains(DeviceActionType.DISCONNECT))
    }

    @Test
    fun connectedStreamReceiver_returnsPlayOnDeviceAction() {
        val device = UnifiedDevice(
            id = "1",
            name = "Living Room",
            ip = "192.168.1.60",
            port = 8400,
            deviceType = "stream",
            connectionState = SyncConnectionState.CONNECTED,
            isPaired = true,
            roles = listOf("audio_receiver")
        )

        val actions = DeviceActionResolver.resolveActions(
            device = device,
            isConnecting = false,
        )

        val types = actions.map { it.type }
        assertTrue(types.contains(DeviceActionType.PLAY_ON_DEVICE))
        assertTrue(types.contains(DeviceActionType.DISCONNECT))
    }

    @Test
    fun exactlyOnePrimaryAction_isGuaranteedForAllCombinations() {
        // Disconnected unpaired
        val unpaired = UnifiedDevice(
            id = "1", name = "Test", ip = "1.2.3.4", port = 8400, deviceType = "server",
            connectionState = SyncConnectionState.DISCONNECTED, isPaired = false
        )
        assertEquals(1, DeviceActionResolver.resolveActions(unpaired, false).count { it.isPrimary })

        // Disconnected paired
        val paired = unpaired.copy(isPaired = true)
        assertEquals(1, DeviceActionResolver.resolveActions(paired, false).count { it.isPrimary })

        // Revoked
        val revoked = paired.copy(isRevoked = true)
        val revokedActions = DeviceActionResolver.resolveActions(revoked, false)
        assertEquals(1, revokedActions.count { it.isPrimary })
        assertEquals(DeviceActionType.VIEW_DETAILS, revokedActions.first { it.isPrimary }.type)

        // Connected multi-role server (Library + Sync + Playback)
        val multiServer = paired.copy(
            connectionState = SyncConnectionState.CONNECTED,
            roles = listOf("music_server", "sync_host", "playback_host")
        )
        val multiActions = DeviceActionResolver.resolveActions(multiServer, false)
        assertEquals(1, multiActions.count { it.isPrimary })
        // Precedence: LibraryHost wins
        assertEquals(DeviceActionType.BROWSE_LIBRARY, multiActions.first { it.isPrimary }.type)

        // Connected desktop player (Playback only)
        val player = paired.copy(
            connectionState = SyncConnectionState.CONNECTED,
            roles = listOf("playback_host")
        )
        val playerActions = DeviceActionResolver.resolveActions(player, false)
        assertEquals(1, playerActions.count { it.isPrimary })
        assertEquals(DeviceActionType.CONTROL_PLAYBACK, playerActions.first { it.isPrimary }.type)

        // Connected stream receiver (Audio receiver only)
        val receiver = paired.copy(
            connectionState = SyncConnectionState.CONNECTED,
            roles = listOf("audio_receiver")
        )
        val receiverActions = DeviceActionResolver.resolveActions(receiver, false)
        assertEquals(1, receiverActions.count { it.isPrimary })
        assertEquals(DeviceActionType.PLAY_ON_DEVICE, receiverActions.first { it.isPrimary }.type)
    }

    private fun device(features: List<String> = emptyList()) = UnifiedDevice(
        id = "node-a", name = "Node", ip = "192.168.1.2", port = 8400,
        connectionState = SyncConnectionState.CONNECTED, isPaired = true,
        deviceType = "server", roles = listOf("sync_host"), features = features,
    )

    // M29 regression tests: capability honesty and Stream provisioning.
    @Test fun genericSyncHostDoesNotAutomaticallyClaimAirCat() {
        val actions = DeviceActionResolver.resolveActions(device(), isConnecting = false)
        assertFalse(actions.any { it.type == DeviceActionType.AIRCAT_TRANSFER })
    }
    @Test fun explicitlyAdvertisedAirCatIsAvailableWithinDevices() {
        val actions = DeviceActionResolver.resolveActions(device(listOf("devices.aircat")), isConnecting = false)
        assertTrue(actions.any { it.type == DeviceActionType.AIRCAT_TRANSFER })
        assertEquals(1, actions.count { it.isPrimary })
    }
    @Test fun unrelatedCapabilitiesAreNotMisclassifiedAsAirCat() {
        assertFalse(DeviceActionResolver.supportsAirCat(device(listOf("aircatapult", "streaming"))))
        assertTrue(DeviceActionResolver.supportsAirCat(device(listOf("AirCat-Transfer"))))
    }
}
