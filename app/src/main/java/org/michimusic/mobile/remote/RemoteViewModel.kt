package org.michimusic.mobile.remote

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.michimusic.core.models.SyncConnectionState
import org.michimusic.core.models.Track
import org.michimusic.core.models.TrackSource
import org.michimusic.link.EventClient
import org.michimusic.link.LinkClient
import org.michimusic.link.ConnectionManager
import org.michimusic.mobile.playback.PlaybackSessionManager
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
    private val sessionManager: PlaybackSessionManager,
    private val connectionManager: ConnectionManager,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {

    private val _uiState = MutableStateFlow(RemoteUiState())
    val uiState: StateFlow<RemoteUiState> = _uiState.asStateFlow()

    init {
        // Observe authoritative PlaybackSessionManager state
        viewModelScope.launch {
            sessionManager.sessionState.collect { sState ->
                val ep = sState.activeEndpoint
                if (ep.isLocal) {
                    _uiState.value = RemoteUiState(
                        mode = RemoteSourceMode.LOCAL,
                        connState = RemoteConnectionState.DISCONNECTED,
                        connected = false,
                        sourceName = "Reproductor local",
                    )
                } else {
                    val connState = when (connectionManager.connectionStates.value[ep.id]) {
                        SyncConnectionState.CONNECTED -> RemoteConnectionState.CONNECTED
                        SyncConnectionState.UNAUTHORIZED -> RemoteConnectionState.UNAUTHORIZED
                        SyncConnectionState.OFFLINE -> RemoteConnectionState.OFFLINE
                        else -> RemoteConnectionState.CONNECTED
                    }
                    _uiState.value = _uiState.value.copy(
                        mode = RemoteSourceMode.REMOTE,
                        connState = connState,
                        connected = true,
                        sourceName = ep.name,
                        playerState = PlaybackStateDto(
                            state = if (sState.isPlaying) "playing" else "paused",
                            positionMs = sState.position,
                            durationMs = sState.duration,
                            volume = sState.remoteVolume,
                            trackId = sState.currentTrack?.id ?: "",
                            title = sState.currentTrack?.title ?: "",
                            artist = sState.currentTrack?.artist ?: "",
                            album = sState.currentTrack?.album ?: "",
                            repeat = when (sState.repeatMode) { 1 -> "one"; 2 -> "all"; else -> "off" },
                            shuffle = sState.shuffleMode == 1,
                        ),
                        queue = QueueDto(
                            tracks = sState.queue.map { t ->
                                org.michimusic.link.dto.QueueTrackDto(
                                    trackId = t.id,
                                    title = t.title,
                                    artist = t.artist,
                                    album = t.album,
                                    duration = t.duration,
                                    coverId = t.coverId ?: "",
                                )
                            },
                            currentIndex = sState.queueIndex,
                        ),
                        error = null,
                    )
                }
            }
        }
    }

    fun connectIfNeeded() {
        // No-op: PlaybackSessionManager manages endpoint attachment and lifecycle
    }

    fun disconnect() {
        sessionManager.selectLocalEndpoint()
    }

    fun clearError() { _uiState.value = _uiState.value.copy(error = null) }

    fun retry() {
        val activeEndpoint = sessionManager.sessionState.value.activeEndpoint
        if (!activeEndpoint.isLocal) {
            connectionManager.connect(activeEndpoint.id)
        }
    }

    fun play() { sessionManager.playPause() }
    fun pause() { sessionManager.playPause() }
    fun togglePlayPause() { sessionManager.playPause() }
    fun next() { sessionManager.skipNext() }
    fun previous() { sessionManager.skipPrevious() }
    fun stop() { sessionManager.playPause() }
    fun seek(positionMs: Long) { sessionManager.seekTo(positionMs) }
    
    fun setVolume(volume: Int) {
        val activeEndpoint = sessionManager.sessionState.value.activeEndpoint
        if (!activeEndpoint.isLocal) {
            viewModelScope.launch(ioDispatcher) {
                connectionManager.getClient(activeEndpoint.id)?.sendSetVolume(volume.coerceIn(0, 100))
            }
        }
    }

    fun mute() { setVolume(0) }
    fun unmute() { setVolume(70) }

    fun handoffToLocal(onResult: (Boolean, String) -> Unit) {
        sessionManager.handoffTo(org.michimusic.mobile.playback.PlaybackEndpoint.LocalPhone, onResult)
    }

    fun queueJump(index: Int) {
        sessionManager.skipToQueueIndex(index)
    }
}
