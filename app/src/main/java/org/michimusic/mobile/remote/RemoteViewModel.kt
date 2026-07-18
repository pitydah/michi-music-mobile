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
import org.michimusic.link.EventClient
import org.michimusic.link.LinkClient
import org.michimusic.link.LinkSession
import org.michimusic.link.dto.PlaybackStateDto
import org.michimusic.link.dto.QueueDto
import org.michimusic.link.errors.LinkException

enum class RemoteSourceMode {
    LOCAL,
    REMOTE,
}

enum class RemoteConnectionState {
    DISCONNECTED,
    CONNECTED,
    UNAUTHORIZED,
    FORBIDDEN,
    OFFLINE,
    INCOMPATIBLE,
    ENDPOINT_MISSING,
    CONTRACT_PARTIAL,
}

data class RemoteUiState(
    val mode: RemoteSourceMode = RemoteSourceMode.LOCAL,
    val connState: RemoteConnectionState = RemoteConnectionState.DISCONNECTED,
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
    private var eventClient: EventClient? = null
    private var pollingJob: Job? = null
    private var eventJob: Job? = null

    fun connectIfNeeded() {
        if (_uiState.value.connected) return
        val peer = session.connectedPeer.value ?: return
        val linkClient = session.linkClient ?: return
        val token = linkClient.deviceToken.ifEmpty { linkClient.sessionToken }

        client = linkClient
        _uiState.value = _uiState.value.copy(
            mode = RemoteSourceMode.REMOTE,
            connState = RemoteConnectionState.CONNECTED,
            connected = true,
            sourceName = peer.alias,
        )

        eventClient = EventClient(linkClient.baseUrl, token, linkClient.clientDeviceId).also { ec ->
            eventJob = viewModelScope.launch {
                ec.events.collect { event ->
                    when (event.type) {
                        "playback_state_changed" -> refreshState()
                        "queue_changed" -> refreshQueue()
                    }
                }
            }
            ec.connect(viewModelScope)
        }

        refreshState()
        startPollingFallback(linkClient)
    }

    fun disconnect() {
        eventClient?.disconnect()
        eventClient = null
        eventJob?.cancel()
        eventJob = null
        pollingJob?.cancel()
        pollingJob = null
        client = null
        _uiState.value = RemoteUiState()
    }

    private fun startPollingFallback(client: LinkClient) {
        pollingJob?.cancel()
        pollingJob = viewModelScope.launch {
            delay(5_000)
            while (isActive && _uiState.value.connected) {
                refreshState()
                refreshQueue()
                delay(5_000)
            }
        }
    }

    private suspend fun refreshState() {
        client?.getPlaybackState()?.onSuccess { state ->
            _uiState.value = _uiState.value.copy(
                playerState = state,
                connState = RemoteConnectionState.CONNECTED,
                error = null,
            )
        }?.onFailure { e ->
            when (e) {
                is LinkException.Unauthorized -> {
                    _uiState.value = _uiState.value.copy(
                        connState = RemoteConnectionState.UNAUTHORIZED,
                        error = "Sesión expirada. Reconecta desde Sync.",
                        connected = false,
                    )
                    pollingJob?.cancel(); return
                }
                is LinkException.Revoked -> {
                    _uiState.value = _uiState.value.copy(
                        connState = RemoteConnectionState.FORBIDDEN,
                        error = "Acceso denegado por el servidor.",
                    )
                    pollingJob?.cancel(); return
                }
                is LinkException.Incompatible -> {
                    _uiState.value = _uiState.value.copy(
                        connState = RemoteConnectionState.INCOMPATIBLE,
                        error = "Versión incompatible del servidor.",
                    )
                    pollingJob?.cancel(); return
                }
                is LinkException.NotImplemented -> {
                    _uiState.value = _uiState.value.copy(
                        connState = RemoteConnectionState.ENDPOINT_MISSING,
                        error = "Endpoint no disponible en este servidor.",
                    )
                }
                else -> {
                    val msg = e.message ?: ""
                    if (msg.contains("timeout") || msg.contains("Network") || msg.contains("Unreachable")) {
                        _uiState.value = _uiState.value.copy(
                            connState = RemoteConnectionState.OFFLINE,
                            error = "Servidor fuera de línea.",
                            connected = false,
                        )
                        pollingJob?.cancel(); return
                    }
                    _uiState.value = _uiState.value.copy(error = "Error: ${e.message}")
                }
            }
        }
    }

    private suspend fun refreshQueue() {
        client?.getQueue()?.onSuccess { queue ->
            _uiState.value = _uiState.value.copy(queue = queue)
        }
    }

    fun clearError() { _uiState.value = _uiState.value.copy(error = null) }

    fun retry() {
        _uiState.value = _uiState.value.copy(connState = RemoteConnectionState.CONNECTED, connected = true)
        client?.let { startPollingFallback(it) } ?: connectIfNeeded()
    }

    private fun sendCommand(command: String, value: String = "") {
        viewModelScope.launch {
            client?.sendPlaybackCommand(command, value)?.onFailure { e ->
                handleCmdError(e)
            }
        }
    }

    private fun handleCmdError(e: Throwable) {
        when (e) {
            is LinkException.Unauthorized -> {
                _uiState.value = _uiState.value.copy(
                    connState = RemoteConnectionState.UNAUTHORIZED,
                    error = "Sesión expirada.",
                    connected = false,
                )
                pollingJob?.cancel()
            }
            is LinkException.Revoked -> {
                _uiState.value = _uiState.value.copy(
                    connState = RemoteConnectionState.FORBIDDEN,
                    error = "Acceso denegado.",
                )
                pollingJob?.cancel()
            }
            is LinkException.NotImplemented -> {
                _uiState.value = _uiState.value.copy(
                    connState = RemoteConnectionState.ENDPOINT_MISSING,
                    error = "Comando no soportado por este servidor.",
                )
            }
            else -> {
                _uiState.value = _uiState.value.copy(error = "Error: ${e.message}")
            }
        }
    }

    fun play() { sendCommand("play") }
    fun pause() { sendCommand("pause") }
    fun togglePlayPause() {
        val ps = _uiState.value.playerState
        if (ps.effectiveState == "playing") pause() else play()
    }
    fun next() { sendCommand("next") }
    fun previous() { sendCommand("previous") }
    fun stop() { sendCommand("stop") }
    fun seek(positionMs: Long) {
        viewModelScope.launch {
            client?.sendSeek(positionMs)?.onFailure { e -> handleCmdError(e) }
        }
    }
    fun setVolume(volume: Int) {
        _uiState.value = _uiState.value.copy(
            playerState = _uiState.value.playerState.copy(volume = volume.coerceIn(0, 100))
        )
        viewModelScope.launch {
            client?.sendSetVolume(volume.coerceIn(0, 100))?.onFailure { e -> handleCmdError(e) }
        }
    }
    fun mute() { sendCommand("mute") }
    fun unmute() { sendCommand("unmute") }
    fun queueJump(index: Int) {
        viewModelScope.launch {
            client?.queueJump(index)?.onFailure { e -> handleCmdError(e) }
        }
    }

    override fun onCleared() {
        super.onCleared()
        eventClient?.disconnect()
        pollingJob?.cancel()
        client?.close()
    }
}
