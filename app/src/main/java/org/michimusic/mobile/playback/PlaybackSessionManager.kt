package org.michimusic.mobile.playback

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.michimusic.core.models.DiscoveredPeer
import org.michimusic.core.models.SyncConnectionState
import org.michimusic.core.models.Track
import org.michimusic.core.models.TrackSource
import org.michimusic.link.LinkClient
import org.michimusic.link.ConnectionManager
import org.michimusic.link.LinkDiscovery
import org.michimusic.link.PairedDeviceRegistry
import org.michimusic.link.dto.PlaybackStateDto
import org.michimusic.player.AudioController

class PlaybackSessionManager(
    private val audioController: AudioController? = null,
    private val connectionManager: ConnectionManager,
    private val linkDiscovery: LinkDiscovery,
    private val registry: PairedDeviceRegistry,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Main),
) {
    private val _sessionState = MutableStateFlow(PlaybackSessionState())
    val sessionState: StateFlow<PlaybackSessionState> = _sessionState.asStateFlow()

    private var remotePollingJob: Job? = null
    private var isRemoteSelected = false

    init {
        // Observe local AudioController state
        scope.launch {
            audioController?.state?.collect { localState ->
                if (!isRemoteSelected) {
                    _sessionState.value = _sessionState.value.copy(
                        activeEndpoint = PlaybackEndpoint.LocalPhone,
                        currentTrack = localState.currentTrack,
                        isPlaying = localState.isPlaying,
                        position = localState.position,
                        duration = localState.duration,
                        queue = localState.queue,
                        queueIndex = localState.queueIndex,
                        isRemoteActive = false,
                    )
                }
            }
        }

        // Observe available peers from ConnectionManager/Registry (active endpoints)
        scope.launch {
            connectionManager.connectionStates.collect { states ->
                updateAvailableEndpoints()
                val activeEndpoint = _sessionState.value.activeEndpoint
                if (!activeEndpoint.isLocal && states[activeEndpoint.id] != SyncConnectionState.CONNECTED) {
                    stopRemoteSync()
                    if (isRemoteSelected) {
                        selectLocalEndpoint()
                    }
                } else if (!activeEndpoint.isLocal) {
                    val peer = linkDiscovery.peers.value.values.find { it.deviceId == activeEndpoint.id }
                    if (peer != null) startRemoteSync(peer, activeEndpoint.id)
                }
            }
        }

        // Observe discovered peers in LinkDiscovery
        scope.launch {
            linkDiscovery.peers.collect {
                updateAvailableEndpoints()
            }
        }
    }

    private fun updateAvailableEndpoints() {
        val pairedDevices = registry.getAllDevices()
        val discoveredEndpoints = linkDiscovery.peers.value.values.map { peer ->
            val isConnected = connectionManager.connectionStates.value[peer.deviceId] == SyncConnectionState.CONNECTED
            mapPeerToEndpoint(peer, isConnected = isConnected)
        }
        val endpoints = mutableListOf(PlaybackEndpoint.LocalPhone)
        endpoints.addAll(discoveredEndpoints)
        
        // Add paired devices that might not be discovered via UDP right now
        pairedDevices.forEach { pd ->
            if (endpoints.none { it.id == pd.deviceId }) {
                val isConnected = connectionManager.connectionStates.value[pd.deviceId] == SyncConnectionState.CONNECTED
                val isReceiver = pd.roles.contains("audio_receiver") || pd.serviceType.contains("stream")
                val caps = if (isReceiver) {
                    setOf("PLAYBACK", "AUDIO_OUTPUT")
                } else if (pd.roles.contains("music_server")) {
                    setOf("PLAYBACK", "REMOTE_CONTROL", "LIBRARY")
                } else {
                    setOf("PLAYBACK", "REMOTE_CONTROL")
                }
                endpoints.add(PlaybackEndpoint(
                    id = pd.deviceId,
                    name = pd.deviceName,
                    type = if (isReceiver) EndpointType.STREAM_RECEIVER else EndpointType.SERVER,
                    isLocal = false,
                    isConnected = isConnected,
                    capabilities = caps
                ))
            }
        }

        _sessionState.value = _sessionState.value.copy(
            availableEndpoints = endpoints.distinctBy { it.id },
        )
    }

    private fun mapPeerToEndpoint(peer: DiscoveredPeer, isConnected: Boolean): PlaybackEndpoint {
        val type = when (peer.deviceType.lowercase()) {
            "server" -> EndpointType.SERVER
            "stream", "receiver" -> EndpointType.STREAM_RECEIVER
            "room" -> EndpointType.ROOM
            else -> EndpointType.DESKTOP_PLAYER
        }
        val caps = when (type) {
            EndpointType.STREAM_RECEIVER -> setOf("PLAYBACK", "AUDIO_OUTPUT")
            EndpointType.SERVER -> setOf("PLAYBACK", "REMOTE_CONTROL", "LIBRARY")
            EndpointType.ROOM -> setOf("PLAYBACK", "GROUP_OUTPUT")
            else -> setOf("PLAYBACK", "REMOTE_CONTROL")
        }
        return PlaybackEndpoint(
            id = peer.deviceId.ifEmpty { "${peer.ip}:${peer.port}" },
            name = peer.alias.ifEmpty { "Michi Node" },
            type = type,
            isLocal = false,
            isConnected = isConnected,
            capabilities = caps,
        )
    }

    private fun startRemoteSync(peer: DiscoveredPeer, deviceId: String) {
        remotePollingJob?.cancel()
        remotePollingJob = scope.launch(Dispatchers.IO) {
            val client = connectionManager.getClient(deviceId) ?: return@launch
            while (isActive && connectionManager.connectionStates.value[deviceId] == SyncConnectionState.CONNECTED) {
                client.getPlaybackState().onSuccess { stateDto ->
                    if (isRemoteSelected) {
                        client.getQueue().onSuccess { queueDto ->
                            withContext(Dispatchers.Main) {
                                applyRemoteState(peer, stateDto, queueDto, deviceId)
                            }
                        }.onFailure {
                            withContext(Dispatchers.Main) {
                                applyRemoteState(peer, stateDto, null, deviceId)
                            }
                        }
                    }
                }
                delay(3000)
            }
        }
    }

    private fun stopRemoteSync() {
        remotePollingJob?.cancel()
        remotePollingJob = null
    }

    private fun applyRemoteState(peer: DiscoveredPeer, dto: PlaybackStateDto, queueDto: org.michimusic.link.dto.QueueDto?, deviceId: String) {
        val endpoint = mapPeerToEndpoint(peer, isConnected = true).copy(id = deviceId)
        val remoteTrack = if (dto.effectiveTitle.isNotEmpty()) {
            Track(
                id = dto.trackId.ifEmpty { "remote_${dto.effectiveTitle}" },
                title = dto.effectiveTitle,
                artist = dto.effectiveArtist,
                album = dto.album,
                duration = dto.effectiveDuration,
                coverId = dto.effectiveCoverId,
                source = TrackSource.STREAMING,
            )
        } else null

        val baseUrl = connectionManager.getClient(deviceId)?.baseUrl ?: ""
        val qTracks = queueDto?.tracks?.map { qt ->
            Track(
                id = qt.trackId,
                title = qt.title,
                artist = qt.artist,
                album = qt.album,
                duration = qt.duration,
                filepath = if (baseUrl.isNotEmpty()) "$baseUrl/api/v1/stream/${qt.trackId}" else "",
                source = TrackSource.STREAMING,
            )
        } ?: emptyList()

        if (isRemoteSelected) {
            _sessionState.value = _sessionState.value.copy(
                activeEndpoint = endpoint,
                currentTrack = remoteTrack ?: _sessionState.value.currentTrack,
                isPlaying = dto.effectiveState == "playing",
                position = dto.effectivePosition,
                duration = dto.effectiveDuration,
                remoteVolume = dto.effectiveVolume,
                queue = if (qTracks.isNotEmpty()) qTracks else _sessionState.value.queue,
                queueIndex = queueDto?.currentIndex ?: _sessionState.value.queueIndex,
                isRemoteActive = true,
            )
        }
    }

    fun selectLocalEndpoint() {
        isRemoteSelected = false
        val local = audioController?.state?.value
        _sessionState.value = _sessionState.value.copy(
            activeEndpoint = PlaybackEndpoint.LocalPhone,
            currentTrack = local?.currentTrack,
            isPlaying = local?.isPlaying ?: false,
            position = local?.position ?: 0L,
            duration = local?.duration ?: 0L,
            queue = local?.queue ?: emptyList(),
            queueIndex = local?.queueIndex ?: -1,
            isRemoteActive = false,
        )
    }

    fun attachRemote(target: PlaybackEndpoint, onResult: (Boolean, String) -> Unit) {
        if (target.isLocal) {
            selectLocalEndpoint()
            onResult(true, "Conectado al reproductor local")
            return
        }

        val client = connectionManager.getClient(target.id)
        if (client == null) {
            onResult(false, "No hay conexión activa con ${target.name}")
            return
        }

        isRemoteSelected = true
        _sessionState.value = _sessionState.value.copy(
            activeEndpoint = target,
            isRemoteActive = true,
        )
        onResult(true, "Controlando ${target.name}")
    }

    fun handoffTo(target: PlaybackEndpoint, onResult: (Boolean, String) -> Unit) {
        if (target.isLocal) {
            // Handoff Remote -> Local
            val client = connectionManager.getClient(_sessionState.value.activeEndpoint.id)

            scope.launch {
                val q = _sessionState.value.queue
                val idx = _sessionState.value.queueIndex
                val pos = _sessionState.value.position
                
                isRemoteSelected = false
                
                if (q.isNotEmpty()) {
                    audioController?.playQueue(q, idx.coerceAtLeast(0))
                    if (pos > 0) audioController?.seekTo(pos)
                }
                
                // Pause remote only after starting local playback
                if (client != null && _sessionState.value.isRemoteActive) {
                    client.sendPlaybackCommand("pause")
                }
                
                selectLocalEndpoint()
                onResult(true, "Reproduciendo en este teléfono")
            }
        } else {
            // Handoff Local -> Remote
            val client = connectionManager.getClient(target.id)

            if (client == null) {
                onResult(false, "No hay conexión activa con ${target.name}")
                return
            }

            scope.launch {
                val q = _sessionState.value.queue
                val idx = _sessionState.value.queueIndex
                val pos = _sessionState.value.position
                
                if (q.isNotEmpty()) {
                    val req = org.michimusic.link.dto.QueueTransferRequest(
                        trackIds = q.map { it.id },
                        currentIndex = idx.coerceAtLeast(0),
                        positionMs = pos,
                        source = "local"
                    )
                    client.transferQueue(req).onSuccess {
                        audioController?.pause()
                        isRemoteSelected = true
                        _sessionState.value = _sessionState.value.copy(
                            activeEndpoint = target,
                            isRemoteActive = true,
                        )
                        onResult(true, "Reproduciendo en ${target.name}")
                    }.onFailure {
                        onResult(false, "Error al transferir cola a ${target.name}")
                    }
                } else {
                    client.sendPlaybackCommand("play").onSuccess {
                        audioController?.pause()
                        isRemoteSelected = true
                        _sessionState.value = _sessionState.value.copy(
                            activeEndpoint = target,
                            isRemoteActive = true,
                        )
                        onResult(true, "Reproduciendo en ${target.name}")
                    }.onFailure {
                        onResult(false, "Error al iniciar reproducción en ${target.name}")
                    }
                }
            }
        }
    }

    fun playPause() {
        if (_sessionState.value.isRemoteActive) {
            val client = connectionManager.getClient(_sessionState.value.activeEndpoint.id)
            val isPlaying = _sessionState.value.isPlaying
            scope.launch {
                client?.sendPlaybackCommand(if (isPlaying) "pause" else "play")
                _sessionState.value = _sessionState.value.copy(isPlaying = !isPlaying)
            }
        } else {
            if (audioController?.state?.value?.isPlaying == true) audioController.pause() else audioController?.play()
        }
    }

    fun skipNext() {
        if (_sessionState.value.isRemoteActive) {
            scope.launch { connectionManager.getClient(_sessionState.value.activeEndpoint.id)?.sendPlaybackCommand("next") }
        } else {
            audioController?.skipNext()
        }
    }

    fun skipPrevious() {
        if (_sessionState.value.isRemoteActive) {
            scope.launch { connectionManager.getClient(_sessionState.value.activeEndpoint.id)?.sendPlaybackCommand("previous") }
        } else {
            audioController?.skipPrevious()
        }
    }

    fun seekTo(positionMs: Long) {
        if (_sessionState.value.isRemoteActive) {
            scope.launch { connectionManager.getClient(_sessionState.value.activeEndpoint.id)?.sendSeek(positionMs) }
        } else {
            audioController?.seekTo(positionMs)
        }
    }
}
