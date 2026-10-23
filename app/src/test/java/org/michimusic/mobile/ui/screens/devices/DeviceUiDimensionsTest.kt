package org.michimusic.mobile.ui.screens.devices

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.michimusic.core.models.SyncConnectionState
import org.michimusic.core.models.UnifiedDevice
import org.michimusic.mobile.sync.DeviceActionType

class DeviceUiDimensionsTest {

    @Test
    fun fromUnifiedDevice_maps4DimensionsCorrectly() {
        val server = UnifiedDevice(
            id = "server-node-01",
            name = "Michi Studio Server",
            ip = "192.168.1.100",
            port = 8400,
            connectionState = SyncConnectionState.CONNECTED,
            isPaired = true,
            deviceType = "server",
            roles = listOf("music_server", "sync_host"),
            features = listOf("library", "sync"),
            isRevoked = false,
        )

        val uiItem = DeviceUiItem.fromUnifiedDevice(server, isConnecting = false, isDiscovered = true)

        // 1. Discovery dimension
        assertEquals(DeviceDiscoveryStatus.DISCOVERED, uiItem.discoveryStatus)

        // 2. Trust dimension
        assertEquals(DeviceTrustStatus.TRUSTED, uiItem.trustStatus)

        // 3. Connection dimension
        assertEquals(DeviceConnectionStatus.CONNECTED, uiItem.connectionStatus)

        // 4. Capabilities dimension
        assertTrue(uiItem.capabilities.contains("Biblioteca"))
        assertTrue(uiItem.capabilities.contains("Sincronización"))

        // Single primary CTA
        assertNotNull(uiItem.primaryAction)
        assertEquals(DeviceActionType.BROWSE_LIBRARY, uiItem.primaryAction?.type)
        assertEquals(true, uiItem.primaryAction?.isPrimary)
        assertTrue(uiItem.secondaryActions.none { it.isPrimary })
    }

    @Test
    fun fromUnifiedDevice_revokedDevice_mapsToRevokedTrust() {
        val revoked = UnifiedDevice(
            id = "revoked-01",
            name = "Rogue Node",
            ip = "192.168.1.105",
            port = 8400,
            connectionState = SyncConnectionState.DISCONNECTED,
            isPaired = true,
            deviceType = "desktop",
            isRevoked = true,
        )

        val uiItem = DeviceUiItem.fromUnifiedDevice(revoked, isConnecting = false, isDiscovered = true)

        assertEquals(DeviceTrustStatus.REVOKED, uiItem.trustStatus)
        assertEquals(DeviceConnectionStatus.DISCONNECTED, uiItem.connectionStatus)
        assertEquals(DeviceActionType.VIEW_DETAILS, uiItem.primaryAction?.type)
    }

    @Test
    fun fromUnifiedDevice_unpairedDiscoveredDevice_hasUnknownTrustAndConnectAction() {
        val discovered = UnifiedDevice(
            id = "discovered-01",
            name = "New Friend PC",
            ip = "192.168.1.120",
            port = 8400,
            connectionState = SyncConnectionState.DISCONNECTED,
            isPaired = false,
            deviceType = "desktop",
        )

        val uiItem = DeviceUiItem.fromUnifiedDevice(discovered, isConnecting = false, isDiscovered = true)

        assertEquals(DeviceTrustStatus.UNKNOWN, uiItem.trustStatus)
        assertEquals(DeviceConnectionStatus.DISCONNECTED, uiItem.connectionStatus)
        assertEquals(DeviceActionType.CONNECT, uiItem.primaryAction?.type)
        assertEquals("Vincular", uiItem.primaryAction?.label)
    }
}
