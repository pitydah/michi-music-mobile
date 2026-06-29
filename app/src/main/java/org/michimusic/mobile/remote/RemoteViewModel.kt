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
import org.michimusic.link.LinkClient
import org.michimusic.link.LinkSession
import org.michimusic.link.dto.PlaybackStateDto
import org.michimusic.link.dto.QueueDto
import org.michimusic.link.errors.LinkException

enum class RemoteSourceMode {
    LOCAL,
    REMOTE,
}

data class RemoteUiState(
    val mode: RemoteSourceMode = RemoteSourceMode.LOCAL,
    val playerState: PlaybackStateDto = PlaybackStateDto(),
    val queue: QueueDto = QueueDto(),
    val connected: Boolean = false,
    val sourceName: String = "Reproductor local",
    val error: String? = null,
)

class RemoteViewModel(
    private val session: LinkSession,
) : ViewModel() {

    private val _uiState = MutableStateFlow(RemoteUiState())
    val uiState: StateFlow<RemoteUiState> = _uiState.asStateFlow()

    private var client: LinkClient? = null
    private var pollingJob: Job? = null

    fun connectIfNeeded() {
        if (_uiState.value.connected) return
        val peer = session.connectedPeer.value ?: return
        val linkClient = session.linkClient ?: return

        client = linkClient
        _uiState.value = _uiState.value.copy(
            mode = RemoteSourceMode.REMOTE,
            connected = true,
            sourceName = peer.alias,
        )
        startPolling(linkClient)
    }

    fun disconnect() {
        pollingJob?.cancel()
        pollingJob = null
        client = null
        _uiState.value = RemoteUiState()
    }

    private fun startPolling(client: LinkClient) {
        pollingJob?.cancel()
        pollingJob = viewModelScope.launch {
            while (isActive && _uiState.value.connected) {
                client.getPlaybackState().onSuccess { state ->
                    _uiState.value = _uiState.value.copy(
                        playerState = state,
                        error = null,
                    )
                }.onFailure { e ->
                    val msg = e.message ?: ""
                    if (msg.contains("401") || msg.contains("Unauthorized") || e is LinkException.Unauthorized) {
                        _uiState.value = _uiState.value.copy(
                            error = "Sesión expirada. Reconecta desde Sync.",
                            connected = false,
                        )
                        pollingJob?.cancel()
                        return@launch
                    }
                    _uiState.value = _uiState.value.copy(error = "Error: ${e.message}")
                }

                client.getQueue().onSuccess { queue ->
                    _uiState.value = _uiState.value.copy(queue = queue)
                }.onFailure { /* cola no siempre disponible */ }

                delay(2000)
            }
        }
    }

    fun clearError() { _uiState.value = _uiState.value.copy(error = null) }

    fun retry() {
        client?.let { startPolling(it) } ?: connectIfNeeded()
    }

    fun sendCommand(command: String, value: String = "") {
        viewModelScope.launch {
            client?.sendPlaybackCommand(command, value)?.onFailure { e ->
                _uiState.value = _uiState.value.copy(error = "Error: ${e.message}")
            }
        }
    }

    fun play() { sendCommand("play") }
    fun pause() { sendCommand("pause") }
    fun togglePlayPause() {
        val state = _uiState.value.playerState.state
        if (state == "playing") pause() else play()
    }
    fun next() { sendCommand("next") }
    fun previous() { sendCommand("previous") }
    fun stop() { sendCommand("stop") }
    fun seek(positionMs: Long) { sendCommand("seek", positionMs.toString()) }
    fun setVolume(volume: Int) {
        _uiState.value = _uiState.value.copy(
            playerState = _uiState.value.playerState.copy(volume = volume)
        )
        sendCommand("set_volume", volume.toString())
    }
    fun mute() { sendCommand("mute") }
    fun unmute() { sendCommand("unmute") }
    fun queueJump(index: Int) {
        viewModelScope.launch {
            client?.queueJump(index)?.onFailure { e ->
                _uiState.value = _uiState.value.copy(error = "Error: ${e.message}")
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        pollingJob?.cancel()
        client?.close()
    }
}
