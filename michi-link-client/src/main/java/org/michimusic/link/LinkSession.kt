package org.michimusic.link

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.michimusic.core.models.DiscoveredPeer
import org.michimusic.core.models.SyncConnectionState
import org.michimusic.link.dto.PairConfirmResponseDto
import org.michimusic.link.dto.PairStartResponseDto

class LinkSession {
    private val _connectionState = MutableStateFlow(SyncConnectionState.DISCONNECTED)
    val connectionState: StateFlow<SyncConnectionState> = _connectionState.asStateFlow()

    private val _connectedPeer = MutableStateFlow<DiscoveredPeer?>(null)
    val connectedPeer: StateFlow<DiscoveredPeer?> = _connectedPeer.asStateFlow()

    private val _pairStartResponse = MutableStateFlow<PairStartResponseDto?>(null)
    val pairStartResponse: StateFlow<PairStartResponseDto?> = _pairStartResponse.asStateFlow()

    private val _pairConfirmResponse = MutableStateFlow<PairConfirmResponseDto?>(null)
    val pairConfirmResponse: StateFlow<PairConfirmResponseDto?> = _pairConfirmResponse.asStateFlow()

    private var _linkClient: LinkClient? = null
    val linkClient: LinkClient? get() = _linkClient

    fun updateState(state: SyncConnectionState) {
        _connectionState.value = state
    }

    fun onConnected(peer: DiscoveredPeer, client: LinkClient) {
        _connectedPeer.value = peer
        _linkClient = client
        _connectionState.value = SyncConnectionState.CONNECTED
    }

    fun onPairingStarted(response: PairStartResponseDto) {
        _pairStartResponse.value = response
        _connectionState.value = SyncConnectionState.PAIRING
    }

    fun onPaired(response: PairConfirmResponseDto) {
        _pairConfirmResponse.value = response
        _connectionState.value = SyncConnectionState.PAIRED
    }

    fun disconnect() {
        _linkClient?.close()
        _linkClient = null
        _connectedPeer.value = null
        _pairStartResponse.value = null
        _pairConfirmResponse.value = null
        _connectionState.value = SyncConnectionState.DISCONNECTED
    }
}
