package org.michimusic.mobile.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.michimusic.core.models.DiscoveredPeer
import org.michimusic.core.models.SyncConnectionState

class DeviceActionResolverTest {

    @Test
    fun disconnectedPeer_returnsConnectActionOnly() {
        val peer = DiscoveredPeer(
            alias = "Micro Server",
            ip = "192.168.1.100",
            port = 53318,
            deviceType = "server",
        )

        val actions = DeviceActionResolver.resolveActions(
            peer = peer,
            connectionState = SyncConnectionState.DISCONNECTED,
            isPeerConnected = false,
            isConnecting = false,
        )

        assertEquals(1, actions.size)
        assertEquals(DeviceActionType.CONNECT, actions.first().type)
        assertEquals("Conectar", actions.first().label)
        assertTrue(actions.first().isPrimary)
    }

    @Test
    fun connectingPeer_returnsConnectingState() {
        val peer = DiscoveredPeer(
            alias = "PC",
            ip = "192.168.1.50",
            port = 53318,
            deviceType = "desktop",
        )

        val actions = DeviceActionResolver.resolveActions(
            peer = peer,
            connectionState = SyncConnectionState.CONNECTING,
            isPeerConnected = false,
            isConnecting = true,
        )

        assertEquals(1, actions.size)
        assertEquals("Conectando...", actions.first().label)
    }

    @Test
    fun connectedServer_returnsLibraryAndSyncActions() {
        val peer = DiscoveredPeer(
            alias = "Michi Server",
            ip = "192.168.1.100",
            port = 53318,
            deviceType = "server",
        )

        val actions = DeviceActionResolver.resolveActions(
            peer = peer,
            connectionState = SyncConnectionState.PAIRED,
            isPeerConnected = true,
            isConnecting = false,
        )

        val types = actions.map { it.type }
        assertTrue(types.contains(DeviceActionType.BROWSE_LIBRARY))
        assertTrue(types.contains(DeviceActionType.SYNC_LIBRARY))
        assertTrue(types.contains(DeviceActionType.DISCONNECT))
    }

    @Test
    fun connectedDesktopPlayer_returnsControlAndContinueActions() {
        val peer = DiscoveredPeer(
            alias = "Desktop Player",
            ip = "192.168.1.55",
            port = 53318,
            deviceType = "desktop",
        )

        val actions = DeviceActionResolver.resolveActions(
            peer = peer,
            connectionState = SyncConnectionState.CONNECTED,
            isPeerConnected = true,
            isConnecting = false,
        )

        val types = actions.map { it.type }
        assertTrue(types.contains(DeviceActionType.CONTROL_PLAYBACK))
        assertTrue(types.contains(DeviceActionType.CONTINUE_PLAYBACK_HERE))
        assertTrue(types.contains(DeviceActionType.DISCONNECT))
    }

    @Test
    fun connectedStreamReceiver_returnsPlayOnDeviceAction() {
        val peer = DiscoveredPeer(
            alias = "Living Room",
            ip = "192.168.1.60",
            port = 53318,
            deviceType = "stream",
        )

        val actions = DeviceActionResolver.resolveActions(
            peer = peer,
            connectionState = SyncConnectionState.CONNECTED,
            isPeerConnected = true,
            isConnecting = false,
        )

        val types = actions.map { it.type }
        assertTrue(types.contains(DeviceActionType.PLAY_ON_DEVICE))
        assertTrue(types.contains(DeviceActionType.DISCONNECT))
    }
}
