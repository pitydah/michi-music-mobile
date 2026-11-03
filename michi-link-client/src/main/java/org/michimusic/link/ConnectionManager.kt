package org.michimusic.link

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.michimusic.core.models.SyncConnectionState
import org.michimusic.link.identity.MichiIdentity
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

open class ConnectionManager(
    private val registry: PairedDeviceRegistry,
    private val identity: MichiIdentity,
    coroutineContext: kotlin.coroutines.CoroutineContext = Dispatchers.IO
) {

    private val _connectionStates = MutableStateFlow<Map<String, SyncConnectionState>>(emptyMap())
    val connectionStates: StateFlow<Map<String, SyncConnectionState>> = _connectionStates.asStateFlow()

    protected val clients = ConcurrentHashMap<String, LinkClient>()
    private val scope = CoroutineScope(coroutineContext)
    private val epochs = ConcurrentHashMap<String, AtomicLong>()
    private val connectionJobs = ConcurrentHashMap<String, Job>()
    private fun nextEpoch(id: String) = epochs.computeIfAbsent(id) { AtomicLong() }.incrementAndGet()
    private fun current(id: String, epoch: Long) = epochs[id]?.get() == epoch

    open fun getClient(deviceId: String): LinkClient? {
        return clients[deviceId]
    }

    fun connect(deviceId: String) {
        val epoch = nextEpoch(deviceId)
        connectionJobs.remove(deviceId)?.cancel()
        val device = registry.getDevice(deviceId) ?: return
        if (device.lastUrl.isEmpty()) {
            updateState(deviceId, SyncConnectionState.OFFLINE)
            return
        }

        updateState(deviceId, SyncConnectionState.CONNECTING)

        val existing = clients[deviceId]
        val client = if (existing != null &&
            existing.baseUrl == LinkClientConfig.normalizeBaseUrl(device.lastUrl) &&
            existing.deviceToken == device.deviceToken &&
            existing.pairedClientDeviceId == device.pairedClientDeviceId) existing else {
            existing?.close()
            LinkClient(
                baseUrl = device.lastUrl,
                deviceToken = device.deviceToken,
                sessionToken = device.deviceToken,
                clientDeviceId = device.pairedClientDeviceId.ifEmpty { identity.michiId },
                pairedClientDeviceId = device.pairedClientDeviceId,
            ).also {
                it.tokenRefreshSupported = device.tokenRefreshSupported
                clients[deviceId] = it
            }
        }

        connectionJobs[deviceId] = scope.launch {
            val result = client.getServerInfo()
            if (!isActive || !current(deviceId, epoch)) return@launch
            if (result.isSuccess) {
                val info = result.getOrNull()
                // Validate pinned identity on reconnect
                if (info != null) {
                    val pinnedMichiId = device.michiId
                    val pinnedServerId = device.serverId
                    val pinnedPublicKey = device.publicKey
                    val remoteMichiId = info.michiId.orEmpty()
                    val remoteServerId = info.effectiveServerId
                    val remotePublicKey = info.publicKey.orEmpty()

                    // If device was paired with canonical identity, omitting it on reconnect is a violation
                    if (pinnedMichiId.isNotEmpty() && remoteMichiId.isEmpty()) {
                        updateState(deviceId, SyncConnectionState.UNAUTHORIZED)
                        return@launch
                    }
                    if (pinnedMichiId.isNotEmpty() && pinnedMichiId != remoteMichiId) {
                        updateState(deviceId, SyncConnectionState.UNAUTHORIZED)
                        return@launch
                    }
                    if (pinnedServerId.isNotEmpty() && remoteServerId.isNotEmpty() && pinnedServerId != remoteServerId) {
                        updateState(deviceId, SyncConnectionState.UNAUTHORIZED)
                        return@launch
                    }
                    if (pinnedPublicKey.isNotEmpty() && remotePublicKey.isNotEmpty() && pinnedPublicKey != remotePublicKey) {
                        updateState(deviceId, SyncConnectionState.UNAUTHORIZED)
                        return@launch
                    }
                    if (remoteMichiId.isNotEmpty() && remotePublicKey.isNotEmpty()) {
                        if (!identity.verifyServerIdentity(remoteMichiId, remotePublicKey)) {
                            updateState(deviceId, SyncConnectionState.UNAUTHORIZED)
                            return@launch
                        }
                    }

                    // Update fresh metadata while preserving pinned tokens
                    val updated = device.copy(
                        michiId = if (device.michiId.isEmpty()) remoteMichiId else device.michiId,
                        serverId = if (device.serverId.isEmpty()) remoteServerId else device.serverId,
                        publicKey = if (device.publicKey.isEmpty()) remotePublicKey else device.publicKey,
                        identityScheme = info.identityScheme ?: device.identityScheme.ifEmpty { "ed25519-blake3-v1" },
                        features = info.effectiveFeatures.ifEmpty { device.features },
                        roles = info.roles.ifEmpty { device.roles },
                    )
                    if (!isActive || !current(deviceId, epoch)) return@launch
                    registry.saveDevice(updated)
                }
                if (!isActive || !current(deviceId, epoch)) return@launch
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
        _connectionStates.update { it + (deviceId to state) }
    }

    fun disconnect(deviceId: String) {
        nextEpoch(deviceId)
        connectionJobs.remove(deviceId)?.cancel()
        clients.remove(deviceId)?.close()
        updateState(deviceId, SyncConnectionState.DISCONNECTED)
    }

    fun disconnectDevice(deviceId: String) {
        disconnect(deviceId)
    }

    fun disconnectAll() {
        epochs.values.forEach { it.incrementAndGet() }
        connectionJobs.values.forEach { it.cancel() }
        connectionJobs.clear()
        clients.values.forEach { it.close() }
        clients.clear()
        _connectionStates.value = emptyMap()
    }
}
