package org.michimusic.mobile.ui.screens.devices

import org.junit.Assert.*
import org.junit.Test
import org.michimusic.core.models.SyncConnectionState
import org.michimusic.core.models.UnifiedDevice

class DeviceUiModelsTest {
    private fun device(state: SyncConnectionState) = UnifiedDevice(
        id = "known-node", name = "Michi Server", ip = "192.168.1.20", port = 8400,
        connectionState = state, isPaired = true, deviceType = "server",
    )
    @Test fun `paired node not discovered is offline rather than available`() {
        val ui = DeviceUiItem.fromUnifiedDevice(device(SyncConnectionState.DISCONNECTED), isDiscovered = false)
        assertEquals(DeviceConnectionStatus.OFFLINE, ui.connectionStatus)
        assertEquals(DeviceTrustStatus.TRUSTED, ui.trustStatus)
    }
    @Test fun `active session remains connected without a discovery advertisement`() {
        val ui = DeviceUiItem.fromUnifiedDevice(device(SyncConnectionState.CONNECTED), isDiscovered = false)
        assertEquals(DeviceConnectionStatus.CONNECTED, ui.connectionStatus)
    }
    @Test fun `connecting session is not mislabeled offline`() {
        val ui = DeviceUiItem.fromUnifiedDevice(device(SyncConnectionState.CONNECTING), isDiscovered = false)
        assertEquals(DeviceConnectionStatus.CONNECTING, ui.connectionStatus)
    }
    @Test fun `live discovery can show a disconnected but available node`() {
        val ui = DeviceUiItem.fromUnifiedDevice(device(SyncConnectionState.DISCONNECTED), isDiscovered = true)
        assertEquals(DeviceConnectionStatus.DISCONNECTED, ui.connectionStatus)
    }
}
