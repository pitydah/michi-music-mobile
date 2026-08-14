package org.michimusic.link

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.michimusic.core.models.SyncConnectionState
import java.util.concurrent.ConcurrentHashMap

class ConnectionManager(private val registry: PairedDeviceRegistry) {

    private val _connectionStates = MutableStateFlow<Map<String, SyncConnectionState>>(emptyMap())
    val connectionStates: StateFlow<Map<String, SyncConnectionState>> = _connectionStates.asStateFlow()

    private val clients = ConcurrentHashMap<String, LinkClient>()

    fun getClient(deviceId: String): LinkClient? {
        clients[deviceId]?.let { return it }

        val device = registry.getDevice(deviceId) ?: return null
        
        // We need the URL to create a client. If lastUrl is available we use it.
        // LinkDiscovery will update this URL when discovered via UDP.
        if (device.lastUrl.isEmpty()) return null

        val client = LinkClient(
            baseUrl = device.lastUrl,
            deviceToken = device.deviceToken,
            sessionToken = device.deviceToken, // fallback
            clientDeviceId = "mobile_client" // Should probably come from a config
        )
        client.tokenRefreshSupported = device.tokenRefreshSupported
        clients[deviceId] = client
        
        updateState(deviceId, SyncConnectionState.CONNECTED)
        return client
    }
    
    fun getClientForUrl(url: String, deviceId: String, deviceToken: String, clientDeviceId: String): LinkClient {
        val existing = clients[deviceId]
        if (existing != null && existing.baseUrl == url && existing.deviceToken == deviceToken) {
            return existing
        }
        val client = LinkClient(baseUrl = url, deviceToken = deviceToken, sessionToken = deviceToken, clientDeviceId = clientDeviceId)
        clients[deviceId] = client
        updateState(deviceId, SyncConnectionState.CONNECTED)
        return client
    }

    fun updateState(deviceId: String, state: SyncConnectionState) {
        val map = _connectionStates.value.toMutableMap()
        map[deviceId] = state
        _connectionStates.value = map
    }

    fun disconnect(deviceId: String) {
        clients[deviceId]?.close()
        clients.remove(deviceId)
        updateState(deviceId, SyncConnectionState.DISCONNECTED)
    }

    fun disconnectAll() {
        clients.values.forEach { it.close() }
        clients.clear()
        _connectionStates.value = emptyMap()
    }
}
