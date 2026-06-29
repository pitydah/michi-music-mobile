package org.michimusic.mobile.remote

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.michimusic.remote.RemoteApiClient
import org.michimusic.remote.RemotePlayerState
import org.michimusic.sync.SyncSession

class RemoteViewModel(
    private val session: SyncSession,
) : ViewModel() {

    private val _playerState = MutableStateFlow(RemotePlayerState())
    val playerState: StateFlow<RemotePlayerState> = _playerState.asStateFlow()

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private var client: RemoteApiClient? = null
    private var pollingJob: Job? = null

    fun connectIfNeeded() {
        if (_connected.value) return
        val peer = session.connectedPeer.value ?: return
        val reg = session.registration.value ?: return
        connect(peer.ip, reg.sessionToken)
    }

    fun connect(peerIp: String, token: String) {
        client?.close()
        val c = RemoteApiClient(
            baseUrl = "http://$peerIp:8124",
            bearerToken = token,
        )
        client = c
        _connected.value = true
        _error.value = null
        startPolling(c)
    }

    fun disconnect() {
        pollingJob?.cancel()
        pollingJob = null
        client?.close()
        client = null
        _connected.value = false
        _playerState.value = RemotePlayerState()
    }

    private fun startPolling(client: RemoteApiClient) {
        pollingJob?.cancel()
        pollingJob = viewModelScope.launch {
            while (isActive && _connected.value) {
                val result = client.fetchStatus()
                result.onSuccess { state ->
                    _playerState.value = state
                    _error.value = null
                }.onFailure { e ->
                    val msg = e.message ?: ""
                    if (msg.contains("401") || msg.contains("Unauthorized")) {
                        _error.value = "Sesión expirada. Reconecta desde Sync."
                        _connected.value = false
                        pollingJob?.cancel()
                        return@launch
                    }
                    _error.value = "Error al obtener estado: ${e.message}"
                }
                delay(2000)
            }
        }
    }

    fun clearError() { _error.value = null }

    fun retry() {
        client?.let {
            startPolling(it)
        } ?: connectIfNeeded()
    }

    fun play() { execute { client?.play() } }
    fun pause() { execute { client?.pause() } }
    fun togglePlayPause() {
        val state = _playerState.value.state
        if (state == "playing") pause() else play()
    }
    fun next() { execute { client?.next() } }
    fun previous() { execute { client?.previous() } }
    fun setVolume(volume: Int) {
        _playerState.value = _playerState.value.copy(volume = volume)
        viewModelScope.launch { client?.setVolume(volume) }
    }

    private fun execute(action: suspend () -> kotlin.Result<String>?) {
        viewModelScope.launch {
            val result = action()
            if (result == null) return@launch
            result.onFailure { e ->
                val msg = e.message ?: ""
                if (msg.contains("401") || msg.contains("Unauthorized")) {
                    _error.value = "Sesión expirada. Reconecta desde Sync."
                    _connected.value = false
                    pollingJob?.cancel()
                } else {
                    _error.value = "Error: ${e.message}"
                }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        pollingJob?.cancel()
        client?.close()
    }
}
