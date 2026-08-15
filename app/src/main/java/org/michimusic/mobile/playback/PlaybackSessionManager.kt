package org.michimusic.mobile.playback

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
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

    private var eventClient: org.michimusic.link.EventClient? = null
    private var eventJob: Job? = null
    private var remotePollingJob: Job? = null
    private var receiverHeartbeatJob: Job? = null
    private var currentReceiverSequence = 1L
    private val rtpAudioSender = org.michimusic.link.rtp.RtpAudioSender()
    private var activeReceiverSessionId: String? = null
    private var activeReceiverSessionToken: String? = null
    private var activeReceiverEffective: org.michimusic.link.dto.ReceiverSessionEffectiveDto? = null
    private var isRemoteSelected = false
    private var formatObserverJob: Job? = null

    init {
        rtpAudioSender.onError = {
            scope.launch(Dispatchers.Main) {
                selectLocalEndpoint()
            }
        }

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
                        repeatMode = localState.repeatMode,
                        shuffleMode = if (localState.shuffleMode) 1 else 0,
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
        val discoveredPeers = linkDiscovery.peers.value.values
        val pairedDevices = registry.getAllDevices()

        val discoveredEndpoints = discoveredPeers.map { peer ->
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
                val isServer = pd.roles.contains("music_server") || pd.serviceType.contains("server")
                val isPlayer = pd.roles.contains("playback_host") || pd.serviceType.contains("player")
                val type = when {
                    isReceiver -> EndpointType.STREAM_RECEIVER
                    isServer -> EndpointType.SERVER
                    isPlayer -> EndpointType.DESKTOP_PLAYER
                    else -> EndpointType.UNKNOWN
                }
                val caps = when (type) {
                    EndpointType.STREAM_RECEIVER -> setOf("PLAYBACK", "AUDIO_OUTPUT")
                    EndpointType.SERVER -> setOf("PLAYBACK", "REMOTE_CONTROL", "LIBRARY")
                    EndpointType.DESKTOP_PLAYER -> setOf("PLAYBACK", "REMOTE_CONTROL")
                    else -> emptySet()
                }
                endpoints.add(PlaybackEndpoint(
                    id = pd.deviceId,
                    name = pd.deviceName,
                    type = type,
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
        val paired = registry.getDevice(peer.deviceId)
        val type = when {
            paired?.roles?.contains("audio_receiver") == true || peer.deviceType.lowercase() in listOf("stream", "receiver") -> EndpointType.STREAM_RECEIVER
            paired?.roles?.contains("music_server") == true || peer.deviceType.lowercase() == "server" -> EndpointType.SERVER
            paired?.roles?.contains("room") == true || peer.deviceType.lowercase() == "room" -> EndpointType.ROOM
            paired?.roles?.contains("playback_host") == true || peer.deviceType.lowercase() in listOf("player", "desktop_player", "desktop") -> EndpointType.DESKTOP_PLAYER
            else -> EndpointType.UNKNOWN
        }
        val caps = when (type) {
            EndpointType.STREAM_RECEIVER -> setOf("PLAYBACK", "AUDIO_OUTPUT")
            EndpointType.SERVER -> setOf("PLAYBACK", "REMOTE_CONTROL", "LIBRARY")
            EndpointType.ROOM -> setOf("PLAYBACK", "GROUP_OUTPUT")
            EndpointType.DESKTOP_PLAYER -> setOf("PLAYBACK", "REMOTE_CONTROL")
            EndpointType.LOCAL_PHONE -> setOf("PLAYBACK", "LOCAL_OUTPUT")
            EndpointType.UNKNOWN -> emptySet()
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
        stopRemoteSync()
        val client = connectionManager.getClient(deviceId) ?: return

        // 1. Events-first SSE sync
        val token = client.deviceToken.ifEmpty { client.sessionToken }
        val ec = client.createEventClient(token)
        eventClient = ec
        eventJob = scope.launch(Dispatchers.IO) {
            ec.events.collect { event ->
                if (!isRemoteSelected) return@collect
                when (event.type) {
                    "playback_state_changed" -> refreshRemotePlayback(client, peer, deviceId)
                    "queue_changed" -> refreshRemoteQueue(client, peer, deviceId)
                }
            }
        }
        ec.connect(scope)

        // 2. Initial fetch & Fallback polling (strictly fallback when SSE disconnected)
        remotePollingJob = scope.launch(Dispatchers.IO) {
            refreshRemotePlayback(client, peer, deviceId)
            while (isActive && connectionManager.connectionStates.value[deviceId] == SyncConnectionState.CONNECTED) {
                if (ec.connectionState.value == org.michimusic.link.EventConnectionState.CONNECTED) {
                    // Polling is completely OFF while SSE is connected. Wait until SSE state changes away from CONNECTED.
                    ec.connectionState.first { it != org.michimusic.link.EventConnectionState.CONNECTED }
                } else {
                    delay(3_000)
                    if (isRemoteSelected) {
                        refreshRemotePlayback(client, peer, deviceId)
                    }
                }
            }
        }
    }

    private suspend fun refreshRemotePlayback(client: LinkClient, peer: DiscoveredPeer, deviceId: String) {
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
    }

    private suspend fun refreshRemoteQueue(client: LinkClient, peer: DiscoveredPeer, deviceId: String) {
        client.getQueue().onSuccess { queueDto ->
            if (isRemoteSelected) {
                client.getPlaybackState().onSuccess { stateDto ->
                    withContext(Dispatchers.Main) {
                        applyRemoteState(peer, stateDto, queueDto, deviceId)
                    }
                }
            }
        }
    }

    private fun stopRemoteSync() {
        eventJob?.cancel()
        eventJob = null
        eventClient?.disconnect()
        eventClient = null
        remotePollingJob?.cancel()
        remotePollingJob = null
        receiverHeartbeatJob?.cancel()
        receiverHeartbeatJob = null
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
                repeatMode = when (dto.repeat) { "one" -> 1; "all" -> 2; else -> 0 },
                shuffleMode = if (dto.shuffle) 1 else 0,
                isRemoteActive = true,
            )
        }
    }

    fun selectLocalEndpoint(
        reason: StreamErrorReason? = null,
        message: String? = null
    ) {
        val wasRemote = isRemoteSelected
        isRemoteSelected = false
        stopRemoteSync()
        formatObserverJob?.cancel()
        formatObserverJob = null
        org.michimusic.player.PlayerDependencies.stopPcmStreaming()
        rtpAudioSender.stop()
        val activeEp = _sessionState.value.activeEndpoint
        if (activeEp.type == EndpointType.STREAM_RECEIVER || activeEp.capabilities.contains("AUDIO_OUTPUT")) {
            val client = connectionManager.getClient(activeEp.id)
            val sTok = activeReceiverSessionToken
            if (sTok != null) {
                scope.launch(Dispatchers.IO) {
                    runCatching { client?.deleteReceiverLiteSession(sTok) }
                }
            }
        }
        activeReceiverSessionId = null
        activeReceiverSessionToken = null
        activeReceiverEffective = null

        val local = audioController?.state?.value
        _sessionState.value = _sessionState.value.copy(
            activeEndpoint = PlaybackEndpoint.LocalPhone,
            currentTrack = local?.currentTrack,
            isPlaying = local?.isPlaying ?: false,
            position = local?.position ?: 0L,
            duration = local?.duration ?: 0L,
            queue = local?.queue ?: emptyList(),
            queueIndex = local?.queueIndex ?: -1,
            repeatMode = local?.repeatMode ?: 0,
            shuffleMode = if (local?.shuffleMode == true) 1 else 0,
            isRemoteActive = false,
            lastSessionError = reason,
            statusMessage = message ?: if (wasRemote) "Reproduciendo en este teléfono" else null
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
                onResult(true, "Reproducción transferida al teléfono")
            }
        } else if (target.type == EndpointType.STREAM_RECEIVER || target.capabilities.contains("AUDIO_OUTPUT")) {
            // Handoff Local -> Remote
            val prevEp = _sessionState.value.activeEndpoint
            if (prevEp.id != target.id && (prevEp.type == EndpointType.STREAM_RECEIVER || prevEp.capabilities.contains("AUDIO_OUTPUT"))) {
                val prevClient = connectionManager.getClient(prevEp.id)
                val prevTok = activeReceiverSessionToken
                scope.launch(Dispatchers.IO) {
                    prevClient?.deleteReceiverLiteSession(prevTok)
                }
                rtpAudioSender.stop()
                activeReceiverSessionId = null
                activeReceiverSessionToken = null
                activeReceiverEffective = null
                receiverHeartbeatJob?.cancel()
                formatObserverJob?.cancel()
                formatObserverJob = null
            }

            // Canonical ReceiverLite protocol for Michi Stream
            val client = connectionManager.getClient(target.id)
            if (client == null) {
                onResult(false, "No hay conexión activa con ${target.name}")
                return
            }

            scope.launch {
                val targetHost = client.baseUrl.substringAfter("://").substringBefore(":")
                val sInfo = runCatching { client.getServerInfo().getOrNull() }.getOrNull()
                
                val sourceFormat = org.michimusic.player.PlayerDependencies.getActivePcmFormat()
                if (sourceFormat == null) {
                    onResult(false, "Esperando formato de audio del reproductor local")
                    return@launch
                }

                val req = org.michimusic.link.audio.AudioProfileNegotiator.negotiate(
                    capabilities = sInfo?.audio,
                    sourceFormat = sourceFormat,
                    preferredVolume = _sessionState.value.remoteVolume
                )

                if (req == null) {
                    onResult(false, "Formato de audio (${sourceFormat.codec} ${sourceFormat.sampleRate}Hz/${sourceFormat.bitDepth}bit/${sourceFormat.channels}ch) no soportado por el receptor")
                    return@launch
                }

                client.createReceiverLiteSession(req).onSuccess { sessionResp ->
                    val eff = sessionResp.effective
                    // Strict verification: effective parameters must match real audio source format
                    if (eff.sampleRate != sourceFormat.sampleRate ||
                        eff.bitDepth != sourceFormat.bitDepth ||
                        eff.channels != sourceFormat.channels ||
                        !eff.codec.equals(sourceFormat.codec, ignoreCase = true)) {
                        scope.launch(Dispatchers.IO) {
                            client.deleteReceiverLiteSession(sessionResp.sessionToken)
                        }
                        onResult(false, "Incompatibilidad de formato: receptor requiere ${eff.codec} ${eff.sampleRate}Hz/${eff.bitDepth}bit")
                        return@onSuccess
                    }

                    // 1. Start and verify RTP sender before activating PCM tap
                    rtpAudioSender.start(
                        targetHost = targetHost,
                        effective = sessionResp.effective,
                        scope = scope
                    )

                    if (!rtpAudioSender.isActive) {
                        scope.launch(Dispatchers.IO) {
                            client.deleteReceiverLiteSession(sessionResp.sessionToken)
                        }
                        onResult(false, "No se pudo inicializar la transmisión RTP hacia $targetHost:${eff.streamPort}")
                        return@onSuccess
                    }

                    // 2. Only once RTP is active, start PCM tap
                    org.michimusic.player.PlayerDependencies.startPcmStreaming { pcmChunk ->
                        rtpAudioSender.sendPcmChunk(pcmChunk)
                    }

                    activeReceiverSessionId = sessionResp.sessionId
                    activeReceiverSessionToken = sessionResp.sessionToken
                    activeReceiverEffective = sessionResp.effective
                    currentReceiverSequence = 1L

                    // If queue is populated and not playing, start local playback pipeline so PCM flows
                    if (audioController?.state?.value?.isPlaying == false && _sessionState.value.queue.isNotEmpty()) {
                        audioController.play()
                    }

                    isRemoteSelected = true
                    _sessionState.value = _sessionState.value.copy(
                        activeEndpoint = target,
                        isRemoteActive = true,
                    )

                    receiverHeartbeatJob?.cancel()
                    val intervalMs = (sessionResp.leaseSeconds / 3).coerceAtLeast(1) * 1000L
                    receiverHeartbeatJob = scope.launch(Dispatchers.IO) {
                        while (isActive && isRemoteSelected && activeReceiverSessionId == sessionResp.sessionId) {
                            delay(intervalMs)
                            val hbResult = client.sendReceiverLiteHeartbeat(
                                sessionId = sessionResp.sessionId,
                                sequence = currentReceiverSequence++,
                                sessionToken = sessionResp.sessionToken
                            )
                            if (hbResult.isFailure) {
                                val err = hbResult.exceptionOrNull()
                                if (err is org.michimusic.link.errors.LinkException.Unauthorized ||
                                    err is org.michimusic.link.errors.LinkException.Revoked ||
                                    err is org.michimusic.link.errors.LinkException.SessionConflict ||
                                    err is org.michimusic.link.errors.LinkException.ServerError) {
                                    withContext(Dispatchers.Main) {
                                        selectLocalEndpoint()
                                    }
                                    break
                                }
                            }
                        }
                    }

                    // 3. Observe runtime PCM format changes during live session
                    formatObserverJob?.cancel()
                    formatObserverJob = scope.launch {
                        org.michimusic.player.PlayerDependencies.pcmFormatFlow.collect { newFormat ->
                            if (newFormat == null || !isRemoteSelected) return@collect
                            val currentEffective = activeReceiverEffective ?: return@collect
                            val matches = newFormat.sampleRate == currentEffective.sampleRate &&
                                newFormat.bitDepth == currentEffective.bitDepth &&
                                newFormat.channels == currentEffective.channels &&
                                newFormat.codec.equals(currentEffective.codec, ignoreCase = true)
                            if (!matches) {
                                handleDynamicReceiverFormatChange(target, client, targetHost, newFormat)
                            }
                        }
                    }

                    onResult(true, "Sesión de audio y transmisión RTP activa en ${target.name}")
                }.onFailure { err ->
                    // Honest UI: Do not report success on real failure
                    onResult(false, "Fallo al crear sesión ReceiverLite en ${target.name}: ${err.message}")
                }
            }
        } else {
            // Handoff Local -> Remote Playback Host (SERVER / DESKTOP_PLAYER)
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
                    // Resolve/map track IDs to server catalog via multi-criteria. Must abort if unmapped tracks exist!
                    var hasUnresolvableLocalTrack = false
                    val resolvedTrackIds = mutableListOf<String>()

                    for (track in q) {
                        if (track.source == TrackSource.STREAMING && track.id.isNotEmpty()) {
                            resolvedTrackIds.add(track.id)
                        } else {
                            val searchResult = client.search(track.title).getOrNull()
                            val match = searchResult?.firstOrNull { candidate ->
                                // 1. Match by content hash if available
                                val hashMatch = track.contentHash.isNotEmpty() &&
                                    candidate.contentHash.isNotEmpty() &&
                                    track.contentHash.equals(candidate.contentHash, ignoreCase = true)
                                if (hashMatch) return@firstOrNull true

                                // 2. Match by stable canonical content identity signature
                                val idMatch = track.stableContentIdentity.equals(candidate.stableContentIdentity, ignoreCase = true)
                                if (idMatch) return@firstOrNull true

                                // 3. Multi-criteria fallback (title + artist + duration within 3s)
                                val titleMatches = candidate.title.equals(track.title, ignoreCase = true)
                                val artistMatches = candidate.artist.isEmpty() || track.artist.isEmpty() ||
                                        candidate.artist.equals(track.artist, ignoreCase = true)
                                val durationMatches = candidate.duration <= 0 || track.duration <= 0 ||
                                        Math.abs(candidate.duration - track.duration) <= 3000
                                titleMatches && artistMatches && durationMatches
                            }
                            if (match != null && match.effectiveId.isNotEmpty()) {
                                resolvedTrackIds.add(match.effectiveId)
                            } else {
                                hasUnresolvableLocalTrack = true
                                break
                            }
                        }
                    }

                    if (hasUnresolvableLocalTrack) {
                        onResult(false, "No se pudo transferir: algunas pistas locales no existen en el servidor ${target.name}")
                        return@launch
                    }

                    val req = org.michimusic.link.dto.QueueTransferRequest(
                        trackIds = resolvedTrackIds,
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
        val activeEp = _sessionState.value.activeEndpoint
        if (_sessionState.value.isRemoteActive) {
            val client = connectionManager.getClient(activeEp.id)
            val isPlaying = _sessionState.value.isPlaying
            if (activeEp.type == EndpointType.STREAM_RECEIVER || activeEp.capabilities.contains("AUDIO_OUTPUT")) {
                val tok = activeReceiverSessionToken
                val willPause = isPlaying
                scope.launch {
                    client?.patchReceiverLiteSession(
                        org.michimusic.link.dto.ReceiverSessionPatchRequest(paused = willPause),
                        sessionToken = tok
                    )
                    if (willPause) {
                        org.michimusic.player.PlayerDependencies.pausePcmStreaming()
                        audioController?.pause()
                    } else {
                        org.michimusic.player.PlayerDependencies.resumePcmStreaming()
                        audioController?.play()
                    }
                    _sessionState.value = _sessionState.value.copy(isPlaying = !isPlaying)
                }
            } else {
                scope.launch {
                    client?.sendPlaybackCommand(if (isPlaying) "pause" else "play")
                    _sessionState.value = _sessionState.value.copy(isPlaying = !isPlaying)
                }
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

    fun skipToQueueIndex(index: Int) {
        if (_sessionState.value.isRemoteActive) {
            scope.launch {
                connectionManager.getClient(_sessionState.value.activeEndpoint.id)?.queueJump(index)
                _sessionState.value = _sessionState.value.copy(queueIndex = index)
            }
        } else {
            audioController?.skipToQueueIndex(index)
        }
    }

    fun removeFromQueue(index: Int) {
        if (_sessionState.value.isRemoteActive) {
            val currentQ = _sessionState.value.queue.toMutableList()
            if (index in currentQ.indices) {
                val item = currentQ[index]
                currentQ.removeAt(index)
                _sessionState.value = _sessionState.value.copy(queue = currentQ)
                scope.launch {
                    connectionManager.getClient(_sessionState.value.activeEndpoint.id)?.removeQueueItem(item.id)
                }
            }
        } else {
            audioController?.removeFromQueue(index)
        }
    }

    fun clearQueue() {
        if (_sessionState.value.isRemoteActive) {
            _sessionState.value = _sessionState.value.copy(queue = emptyList(), queueIndex = -1)
            scope.launch {
                connectionManager.getClient(_sessionState.value.activeEndpoint.id)?.sendPlaybackCommand("clear_queue")
            }
        } else {
            audioController?.clearQueue()
        }
    }

    fun setRepeatMode(mode: Int) {
        _sessionState.value = _sessionState.value.copy(repeatMode = mode)
        if (_sessionState.value.isRemoteActive) {
            val repeatStr = when (mode) { 1 -> "one"; 2 -> "all"; else -> "off" }
            scope.launch {
                connectionManager.getClient(_sessionState.value.activeEndpoint.id)?.setQueueRepeatMode(repeatStr)
            }
        } else {
            audioController?.setRepeatMode(mode)
        }
    }

    fun cycleRepeatMode() {
        val current = _sessionState.value.repeatMode
        val next = when (current) {
            0 -> 1 // one
            1 -> 2 // all
            else -> 0 // off
        }
        setRepeatMode(next)
    }

    fun setShuffleMode(mode: Int) {
        _sessionState.value = _sessionState.value.copy(shuffleMode = mode)
        if (_sessionState.value.isRemoteActive) {
            scope.launch {
                connectionManager.getClient(_sessionState.value.activeEndpoint.id)?.setQueueShuffle(mode == 1)
            }
        } else {
            audioController?.setShuffleMode(mode == 1)
        }
    }

    fun toggleShuffle() {
        val current = _sessionState.value.shuffleMode
        val next = if (current == 1) 0 else 1
        setShuffleMode(next)
    }

    private suspend fun handleDynamicReceiverFormatChange(
        target: PlaybackEndpoint,
        client: LinkClient,
        targetHost: String,
        newFormat: org.michimusic.core.models.PcmFormat
    ) {
        // 0. Temporarily pause playback clock so ExoPlayer doesn't advance and drop audio frames during ReceiverLite renegotiation
        val wasPlaying = audioController?.state?.value?.isPlaying == true
        if (wasPlaying) {
            audioController?.pause()
        }

        // 1. Temporarily pause PCM tap feeding
        org.michimusic.player.PlayerDependencies.pausePcmStreaming()

        // 2. Teardown previous ReceiverLite session and stop RTP sender
        val oldToken = activeReceiverSessionToken
        if (oldToken != null) {
            runCatching { client.deleteReceiverLiteSession(oldToken) }
        }
        rtpAudioSender.stop()
        activeReceiverSessionId = null
        activeReceiverSessionToken = null
        activeReceiverEffective = null
        receiverHeartbeatJob?.cancel()

        // 3. Negotiate new session with new PCM format
        val sInfo = client.getServerInfo().getOrNull()
        val newReq = org.michimusic.link.audio.AudioProfileNegotiator.negotiate(
            capabilities = sInfo?.audio,
            sourceFormat = newFormat,
            preferredVolume = _sessionState.value.remoteVolume,
        )

        if (newReq == null) {
            selectLocalEndpoint(
                reason = StreamErrorReason.FORMAT_UNSUPPORTED,
                message = "Esta pista no es compatible con Michi Stream; la reproducción continúa en este teléfono"
            )
            return
        }

        client.createReceiverLiteSession(newReq).onSuccess { sessionResp ->
            val eff = sessionResp.effective
            if (eff.sampleRate != newFormat.sampleRate ||
                eff.bitDepth != newFormat.bitDepth ||
                eff.channels != newFormat.channels ||
                !eff.codec.equals(newFormat.codec, ignoreCase = true)) {
                if (sessionResp.sessionToken.isNotEmpty()) {
                    runCatching { client.deleteReceiverLiteSession(sessionResp.sessionToken) }
                }
                selectLocalEndpoint(
                    reason = StreamErrorReason.FORMAT_UNSUPPORTED,
                    message = "Esta pista no es compatible con Michi Stream; la reproducción continúa en este teléfono"
                )
                return@onSuccess
            }

            activeReceiverSessionId = sessionResp.sessionId
            activeReceiverSessionToken = sessionResp.sessionToken
            activeReceiverEffective = sessionResp.effective
            currentReceiverSequence = 1L

            rtpAudioSender.start(
                targetHost = targetHost,
                effective = sessionResp.effective,
                scope = scope
            )

            if (rtpAudioSender.isActive) {
                org.michimusic.player.PlayerDependencies.resumePcmStreaming()

                // 4. Resume playback clock now that RTP transmission is running
                if (wasPlaying) {
                    audioController?.play()
                }

                val intervalMs = (sessionResp.leaseSeconds / 3).coerceAtLeast(1) * 1000L
                receiverHeartbeatJob = scope.launch(Dispatchers.IO) {
                    while (isActive && isRemoteSelected && activeReceiverSessionId == sessionResp.sessionId) {
                        delay(intervalMs)
                        val hbResult = client.sendReceiverLiteHeartbeat(
                            sessionId = sessionResp.sessionId,
                            sequence = currentReceiverSequence++,
                            sessionToken = sessionResp.sessionToken
                        )
                        if (hbResult.isFailure) {
                            withContext(Dispatchers.Main) {
                                selectLocalEndpoint(
                                    reason = StreamErrorReason.SESSION_EXPIRED,
                                    message = "Se perdió la sesión con Michi Stream; la reproducción continúa en este teléfono"
                                )
                            }
                            break
                        }
                    }
                }
            } else {
                selectLocalEndpoint(
                    reason = StreamErrorReason.RTP_START_FAILED,
                    message = "No se pudo iniciar la transmisión de audio; la reproducción continúa en este teléfono"
                )
            }
        }.onFailure {
            selectLocalEndpoint(
                reason = StreamErrorReason.RECEIVER_NEGOTIATION_FAILED,
                message = "No se pudo renegociar la sesión con Michi Stream; la reproducción continúa en este teléfono"
            )
        }
    }
}
