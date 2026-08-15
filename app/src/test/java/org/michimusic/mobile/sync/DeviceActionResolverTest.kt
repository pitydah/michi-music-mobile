package org.michimusic.mobile.sync

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
            port = 53318,
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
            port = 53318,
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
            port = 53318,
            deviceType = "server",
            connectionState = SyncConnectionState.CONNECTED,
            isPaired = true,
            roles = listOf("server")
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
            port = 53318,
            deviceType = "desktop",
            connectionState = SyncConnectionState.CONNECTED,
            isPaired = true,
            roles = listOf("player")
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
            port = 53318,
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
}
