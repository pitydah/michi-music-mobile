package org.michimusic.sync

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.michimusic.core.models.DiscoveredPeer
import org.michimusic.core.models.PairStartResponse
import org.michimusic.core.models.PairConfirmResponse
import org.michimusic.core.models.RegisterResponse
import org.michimusic.core.models.SyncConnectionState

class SyncSession {
    private val _connectionState = MutableStateFlow(SyncConnectionState.DISCONNECTED)
    val connectionState: StateFlow<SyncConnectionState> = _connectionState.asStateFlow()

    private val _connectedPeer = MutableStateFlow<DiscoveredPeer?>(null)
    val connectedPeer: StateFlow<DiscoveredPeer?> = _connectedPeer.asStateFlow()

    private val _registration = MutableStateFlow<RegisterResponse?>(null)
    val registration: StateFlow<RegisterResponse?> = _registration.asStateFlow()

    private val _pairStartResponse = MutableStateFlow<PairStartResponse?>(null)
    val pairStartResponse: StateFlow<PairStartResponse?> = _pairStartResponse.asStateFlow()

    private val _pairConfirmResponse = MutableStateFlow<PairConfirmResponse?>(null)
    val pairConfirmResponse: StateFlow<PairConfirmResponse?> = _pairConfirmResponse.asStateFlow()

    private var _syncClient: MichiSyncClient? = null
    val syncClient: MichiSyncClient? get() = _syncClient

    fun updateState(state: SyncConnectionState) {
        _connectionState.value = state
    }

    fun onConnected(peer: DiscoveredPeer, client: MichiSyncClient) {
        _connectedPeer.value = peer
        _syncClient = client
        _connectionState.value = SyncConnectionState.CONNECTED
    }

    fun onPairingStarted(response: PairStartResponse) {
        _pairStartResponse.value = response
        _connectionState.value = SyncConnectionState.PAIRING
    }

    fun onPaired(response: PairConfirmResponse) {
        _pairConfirmResponse.value = response
        _connectionState.value = SyncConnectionState.PAIRED
    }

    fun onRegistered(response: RegisterResponse) {
        _registration.value = response
    }

    fun disconnect() {
        _syncClient?.close()
        _syncClient = null
        _connectedPeer.value = null
        _registration.value = null
        _pairStartResponse.value = null
        _pairConfirmResponse.value = null
        _connectionState.value = SyncConnectionState.DISCONNECTED
    }
}
