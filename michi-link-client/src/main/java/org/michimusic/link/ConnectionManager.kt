package org.michimusic.link

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.michimusic.core.models.SyncConnectionState
import org.michimusic.link.identity.MichiIdentity
import java.util.concurrent.ConcurrentHashMap

open class ConnectionManager(
    private val registry: PairedDeviceRegistry,
    private val identity: MichiIdentity
) {

    private val _connectionStates = MutableStateFlow<Map<String, SyncConnectionState>>(emptyMap())
    val connectionStates: StateFlow<Map<String, SyncConnectionState>> = _connectionStates.asStateFlow()

    private val clients = ConcurrentHashMap<String, LinkClient>()
    private val scope = CoroutineScope(Dispatchers.IO)

    open fun getClient(deviceId: String): LinkClient? {
        return clients[deviceId]
    }

    fun connect(deviceId: String) {
        val device = registry.getDevice(deviceId) ?: return
        if (device.lastUrl.isEmpty()) {
            updateState(deviceId, SyncConnectionState.OFFLINE)
            return
        }

        updateState(deviceId, SyncConnectionState.CONNECTING)

        val client = clients[deviceId] ?: LinkClient(
            baseUrl = device.lastUrl,
            deviceToken = device.deviceToken,
            sessionToken = device.deviceToken,
            clientDeviceId = identity.michiId
        ).also { 
            it.tokenRefreshSupported = device.tokenRefreshSupported
            clients[deviceId] = it 
        }

        scope.launch {
            val result = client.getServerInfo()
            if (result.isSuccess) {
                val info = result.getOrNull()
                // Validate pinned identity on reconnect
                if (info != null) {
                    val pinnedMichiId = device.michiId
                    val pinnedServerId = device.serverId
                    val remoteMichiId = info.michiId.orEmpty()
                    val remoteServerId = info.effectiveServerId
                    if (pinnedMichiId.isNotEmpty() && remoteMichiId.isNotEmpty() && pinnedMichiId != remoteMichiId) {
                        updateState(deviceId, SyncConnectionState.UNAUTHORIZED)
                        return@launch
                    }
                    if (pinnedServerId.isNotEmpty() && remoteServerId.isNotEmpty() && pinnedServerId != remoteServerId) {
                        updateState(deviceId, SyncConnectionState.UNAUTHORIZED)
                        return@launch
                    }
                    // Update fresh metadata while preserving pinned tokens
                    val updated = device.copy(
                        michiId = if (device.michiId.isEmpty()) remoteMichiId else device.michiId,
                        serverId = if (device.serverId.isEmpty()) remoteServerId else device.serverId,
                        features = info.effectiveFeatures.ifEmpty { device.features },
                        roles = info.roles.ifEmpty { device.roles },
                    )
                    registry.saveDevice(updated)
                }
                updateState(deviceId, SyncConnectionState.CONNECTED)
            } else {
                val errorMsg = result.exceptionOrNull()?.message ?: ""
                val state = when {
                    errorMsg.contains("401") || errorMsg.contains("403") -> SyncConnectionState.UNAUTHORIZED
                    errorMsg.contains("Connect") || errorMsg.contains("Timeout") || errorMsg.contains("UnknownHost") -> SyncConnectionState.OFFLINE
                    else -> SyncConnectionState.ERROR
                }
                updateState(deviceId, state)
            }
        }
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

    fun disconnectDevice(deviceId: String) {
        disconnect(deviceId)
    }

    fun disconnectAll() {
        clients.values.forEach { it.close() }
        clients.clear()
        _connectionStates.value = emptyMap()
    }
}
