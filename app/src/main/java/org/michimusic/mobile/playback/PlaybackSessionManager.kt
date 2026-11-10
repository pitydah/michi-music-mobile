package org.michimusic.mobile.playback

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
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
import org.michimusic.link.PairedDevice
import org.michimusic.link.PairedDeviceRegistry
import org.michimusic.link.dto.PlaybackStateDto
import org.michimusic.player.AudioController

/** Endpoint-scoped status, never inferred from the active phone queue. */
data class RemoteEndpointPreview(
    val endpointId: String,
    val title: String?,
    val state: String,
    val fetchedAtMs: Long,
)

class PlaybackSessionManager(
    private val audioController: AudioController? = null,
    private val connectionManager: ConnectionManager,
    private val linkDiscovery: LinkDiscovery,
    private val registry: PairedDeviceRegistry,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Main),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val _sessionState = MutableStateFlow(PlaybackSessionState())
    val sessionState: StateFlow<PlaybackSessionState> = _sessionState.asStateFlow()

    /**
     * Last snapshot of paired devices handed over by the registry flow.
     *
     * Kept here so [updateAvailableEndpoints] stays a pure in-memory computation: it is
     * reached from three collectors on the main dispatcher, and reading the encrypted
     * preferences file there is exactly what StrictMode flagged at every cold start.
     */
    @Volatile
    private var pairedDevicesSnapshot: List<PairedDevice> = emptyList()

    /** Endless observation job; owned explicitly so tests do not inherit it. */
    private var deviceObservation: Job? = null

    private val _controlPreviews = MutableStateFlow<Map<String, RemoteEndpointPreview>>(emptyMap())
    val controlPreviews: StateFlow<Map<String, RemoteEndpointPreview>> = _controlPreviews.asStateFlow()
    private val controlPreviewEpoch = java.util.concurrent.atomic.AtomicLong(0L)

    /** Explicit refresh on opening Michi Control. Bounded, not repeated by recomposition. */
    fun refreshControlPreviews() {
        val epoch = controlPreviewEpoch.incrementAndGet()
        _controlPreviews.value = emptyMap()
        _sessionState.value.availableEndpoints.asSequence()
            .filter { !it.isLocal && it.isConnected &&
                it.type != EndpointType.STREAM_RECEIVER &&
                "AUDIO_OUTPUT" !in it.capabilities && "REMOTE_CONTROL" in it.capabilities }
            .distinctBy { it.id }.take(8).forEach { endpoint ->
                val client = connectionManager.getClient(endpoint.id) ?: return@forEach
                scope.launch(ioDispatcher) {
                    val state = runCatching { client.getPlaybackState() }.getOrNull()?.getOrNull()
                    withContext(Dispatchers.Main) {
                        if (epoch != controlPreviewEpoch.get()) return@withContext
                        if (_sessionState.value.availableEndpoints.none {
                            it.id == endpoint.id && it.isConnected }) return@withContext
                        val preview = RemoteEndpointPreview(
                            endpointId = endpoint.id,
                            title = state?.effectiveTitle?.takeIf { it.isNotBlank() },
                            state = state?.effectiveState ?: "unavailable",
                            fetchedAtMs = if (state != null) System.currentTimeMillis() else 0L,
                        )
                        _controlPreviews.value = _controlPreviews.value + (endpoint.id to preview)
                    }
                }
            }
    }

    private val _pendingQueueConfirmation = MutableStateFlow<RemoteQueuePreflightDecision.ConfirmationRequired?>(null)
    val pendingQueueConfirmation: StateFlow<RemoteQueuePreflightDecision.ConfirmationRequired?> = _pendingQueueConfirmation.asStateFlow()

    // K50-02: Authoritative ticket registry and single-use policy managed by session manager
    private val _issuedTickets = java.util.concurrent.ConcurrentHashMap<String, RemoteQueueAuthorizationTicket>()

    @androidx.annotation.VisibleForTesting
    internal fun registerTicketForTesting(ticket: RemoteQueueAuthorizationTicket) {
        _issuedTickets[ticket.ticketId] = ticket
    }

    private val _sessionEpoch = java.util.concurrent.atomic.AtomicLong(1L)
    val currentSessionEpoch: Long get() = _sessionEpoch.get()

    /**
     * Remote scrub events are coalesced into at most one HTTP command per
     * [REMOTE_SEEK_COALESCE_MS]. The channel is conflated, so only the latest
     * position survives and the final release position is always sent.
     */
    private data class RemoteSeekRequest(
        val endpointId: String,
        val epoch: Long,
        val positionMs: Long,
    )

    private val remoteSeekRequests = Channel<RemoteSeekRequest>(Channel.CONFLATED)

    private companion object {
        const val REMOTE_SEEK_COALESCE_MS = 120L
    }

    fun nextSessionEpoch(): Long {
        _issuedTickets.clear()
        return _sessionEpoch.incrementAndGet()
    }

    private val _activeQueuePlaybackOp = java.util.concurrent.atomic.AtomicReference<String?>(null)

    private fun isQueuePlaybackCurrent(opId: String, opEpoch: Long, targetEndpointId: String): Boolean {
        return _activeQueuePlaybackOp.get() == opId &&
            _sessionEpoch.get() == opEpoch &&
            _sessionState.value.activeEndpoint.id == targetEndpointId
    }

    private var eventClient: org.michimusic.link.EventClient? = null
    private var eventJob: Job? = null
    private var remotePollingJob: Job? = null
    private var receiverHeartbeatJob: Job? = null
    private var currentReceiverSequence = 1L
    private val rtpAudioSender = org.michimusic.link.rtp.RtpAudioSender()
    internal var activeReceiverSessionId: String? = null
    internal var activeReceiverSessionToken: String? = null
    internal var activeReceiverEffective: org.michimusic.link.dto.ReceiverSessionEffectiveDto? = null
    private var isRemoteSelected = false
    private var formatObserverJob: Job? = null

    init {
        scope.launch {
            sessionState.collect { state ->
                org.michimusic.player.PlayerDependencies.publishOutputAuthority(
                    state.activeEndpoint.id, state.activeEndpoint.isLocal && !state.isRemoteActive
                )
            }
        }
        scope.launch {
            for (request in remoteSeekRequests) {
                delay(REMOTE_SEEK_COALESCE_MS)
                if (!isRemotePlaybackHost) continue
                if (request.epoch != _sessionEpoch.get() ||
                    _sessionState.value.activeEndpoint.id != request.endpointId
                ) continue
                connectionManager.getClient(request.endpointId)?.sendSeek(request.positionMs)
            }
        }
        rtpAudioSender.onError = { ex ->
            val (reason, intent) = when {
                ex is IllegalStateException && ex.message?.contains("Sustained RTP buffer overflow", ignoreCase = true) == true ->
                    StreamErrorReason.RTP_BUFFER_OVERFLOW to PlaybackTransitionIntent.RTP_FAILURE
                ex is java.net.SocketException || ex is java.io.IOException ->
                    StreamErrorReason.NETWORK_LOST to PlaybackTransitionIntent.NETWORK_LOST
                else ->
                    StreamErrorReason.RTP_START_FAILED to PlaybackTransitionIntent.RTP_FAILURE
            }
            val msg = when (reason) {
                StreamErrorReason.RTP_BUFFER_OVERFLOW ->
                    "Sobrecarga en la transmisión de audio hacia Michi Stream; reproducción pausada por seguridad"
                StreamErrorReason.NETWORK_LOST ->
                    "Se perdió la conexión con Michi Stream; reproducción pausada por seguridad"
                else ->
                    "Error en la transmisión hacia Michi Stream; reproducción pausada por seguridad"
            }
            scope.launch(Dispatchers.Main) {
                selectLocalEndpoint(intent = intent, reason = reason, message = msg, resumePlayback = false)
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
                        if (activeEndpoint.type == EndpointType.STREAM_RECEIVER ||
                            "AUDIO_OUTPUT" in activeEndpoint.capabilities) {
                            selectLocalEndpoint() // terminates receiver RTP safely
                        } else {
                            attachLocalControl() // controller-only fallback: do not pause local audio
                        }
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

    /**
     * Starts tracking paired devices. The registry does no work on its own, so collecting
     * is what triggers its single disk read; every later emission comes from a write or
     * from the preference listener, so a revoked device never survives in the endpoint
     * list. This is explicit instead of happening in `init` because the collection is
     * endless: a manager created by a test would otherwise keep dispatching into the test
     * dispatcher after the test ended.
     */
    fun startDeviceObservation() {
        if (deviceObservation?.isActive == true) return
        deviceObservation = scope.launch {
            try {
                registry.observeDevices()
                    .flowOn(ioDispatcher)
                    .collect { snapshot ->
                        pairedDevicesSnapshot = snapshot
                        updateAvailableEndpoints()
                    }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // A registry whose storage is unavailable must not take the session
                // manager down with it: the endpoint list just stays peer-only.
            }
        }
    }

    /** Stops tracking paired devices. Safe to call when observation was never started. */
    fun stopDeviceObservation() {
        deviceObservation?.cancel()
        deviceObservation = null
    }

    private fun updateAvailableEndpoints() {
        val discoveredPeers = linkDiscovery.peers.value.values
        // Snapshot only: EncryptedSharedPreferences must never be read from the main
        // thread (StrictMode flagged it at every cold start). Until the first emission
        // lands the list is empty, which can only hide an endpoint, never authorize one.
        val pairedDevices = pairedDevicesSnapshot

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
                val caps = org.michimusic.link.capability.CapabilityResolver.resolve(
                    roles = pd.roles,
                    features = pd.features
                )
                val type = when {
                    caps.isAudioReceiver -> EndpointType.STREAM_RECEIVER
                    caps.isMusicServer -> EndpointType.SERVER
                    caps.isPlaybackHost -> EndpointType.DESKTOP_PLAYER
                    else -> EndpointType.UNKNOWN
                }
                val capSet = mutableSetOf<String>()
                if (caps.isPlaybackHost || caps.isAudioReceiver || caps.isMusicServer) capSet.add("PLAYBACK")
                if (caps.isAudioReceiver) capSet.add("AUDIO_OUTPUT")
                if (caps.isPlaybackHost || caps.isMusicServer) capSet.add("REMOTE_CONTROL")
                if (caps.isLibraryHost) capSet.add("LIBRARY")

                endpoints.add(PlaybackEndpoint(
                    id = pd.deviceId,
                    name = pd.deviceName,
                    type = type,
                    isLocal = false,
                    isConnected = isConnected,
                    capabilities = capSet
                ))
            }
        }

        _sessionState.value = _sessionState.value.copy(
            availableEndpoints = endpoints.distinctBy { it.id },
        )
    }

    private fun mapPeerToEndpoint(peer: DiscoveredPeer, isConnected: Boolean): PlaybackEndpoint {
        val paired = registry.getDevice(peer.deviceId)
        val caps = if (paired != null) {
            org.michimusic.link.capability.CapabilityResolver.resolve(
                roles = paired.roles,
                features = paired.features
            )
        } else {
            // Unpaired discovered peer without verified ServerInfo is strictly UNKNOWN
            org.michimusic.link.capability.ResolvedCapabilities.UNKNOWN
        }

        val type = when {
            caps.isAudioReceiver -> EndpointType.STREAM_RECEIVER
            caps.isMusicServer -> EndpointType.SERVER
            caps.isPlaybackHost -> EndpointType.DESKTOP_PLAYER
            else -> EndpointType.UNKNOWN
        }
        val capSet = mutableSetOf<String>()
        if (caps.isPlaybackHost || caps.isAudioReceiver || caps.isMusicServer) capSet.add("PLAYBACK")
        if (caps.isAudioReceiver) capSet.add("AUDIO_OUTPUT")
        if (caps.isPlaybackHost || caps.isMusicServer) capSet.add("REMOTE_CONTROL")
        if (caps.isLibraryHost) capSet.add("LIBRARY")

        return PlaybackEndpoint(
            id = peer.deviceId.ifEmpty { "${peer.ip}:${peer.port}" },
            name = peer.alias.ifEmpty { "Michi Node" },
            type = type,
            isLocal = false,
            isConnected = isConnected,
            capabilities = capSet,
        )
    }

    private fun startRemoteSync(peer: DiscoveredPeer?, deviceId: String) {
        stopRemoteSync()
        val client = connectionManager.getClient(deviceId) ?: return
        if (peer == null) {
            // A paired host without live mDNS still supports an initial read.
            // Do not install an endless polling job tied to a nonexistent peer.
            scope.launch(ioDispatcher) {
                runCatching { refreshRemotePlayback(client, null, deviceId) }
            }
            return
        }

        // 1. Events-first SSE sync
        val token = client.deviceToken.ifEmpty { client.sessionToken }
        val ec = client.createEventClient(token)
        eventClient = ec
        eventJob = scope.launch(ioDispatcher) {
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
        remotePollingJob = scope.launch(ioDispatcher) {
            var consecutiveFailures = 0
            if (refreshRemotePlayback(client, peer, deviceId)) consecutiveFailures = 0 else consecutiveFailures++
            while (isActive && connectionManager.connectionStates.value[deviceId] == SyncConnectionState.CONNECTED) {
                if (ec.connectionState.value == org.michimusic.link.EventConnectionState.CONNECTED) {
                    // SSE owns the stream: reset the backoff and wait until it drops.
                    consecutiveFailures = 0
                    ec.connectionState.first { it != org.michimusic.link.EventConnectionState.CONNECTED }
                } else {
                    // Cadence follows playback and health instead of a flat three seconds,
                    // so a paused session and a host that keeps failing cost far less.
                    val delayMs = RemotePollingPolicy.nextDelayMs(
                        isPlaying = _sessionState.value.isPlaying,
                        consecutiveFailures = consecutiveFailures,
                        sseConnected = false,
                        jitter = kotlin.random.Random.nextDouble() * 0.2,
                    ) ?: RemotePollingPolicy.PLAYING_INTERVAL_MS
                    delay(delayMs)
                    if (isRemoteSelected) {
                        if (refreshRemotePlayback(client, peer, deviceId)) consecutiveFailures = 0
                        else consecutiveFailures++
                    }
                }
            }
        }
    }

    /** Returns true when the read completed, false when the host could not be reached. */
    private suspend fun refreshRemotePlayback(client: LinkClient, peer: DiscoveredPeer?, deviceId: String): Boolean {
        val opEpoch = _sessionEpoch.get()
        val stateDto = client.getPlaybackState().getOrNull() ?: return false
        if (!isRemoteSelected || opEpoch != _sessionEpoch.get() || _sessionState.value.activeEndpoint.id != deviceId) {
            // A stale read is not a network failure; it was already replaced.
            return true
        }
        val queueDto = client.getQueue().getOrNull()
        if (opEpoch != _sessionEpoch.get() || _sessionState.value.activeEndpoint.id != deviceId) return true
        withContext(Dispatchers.Main) {
            applyRemoteState(peer, stateDto, queueDto, deviceId)
        }
        return true
    }

    private suspend fun refreshRemoteQueue(client: LinkClient, peer: DiscoveredPeer?, deviceId: String) {
        val opEpoch = _sessionEpoch.get()
        client.getQueue().onSuccess { queueDto ->
            if (isRemoteSelected && opEpoch == _sessionEpoch.get() && _sessionState.value.activeEndpoint.id == deviceId) {
                client.getPlaybackState().onSuccess { stateDto ->
                    if (opEpoch == _sessionEpoch.get() && _sessionState.value.activeEndpoint.id == deviceId) {
                        withContext(Dispatchers.Main) {
                            applyRemoteState(peer, stateDto, queueDto, deviceId)
                        }
                    }
                }
            }
        }
    }

    private fun stopRemoteSync() {
        _pendingQueueConfirmation.value = null
        eventJob?.cancel()
        eventJob = null
        eventClient?.disconnect()
        eventClient = null
        remotePollingJob?.cancel()
        remotePollingJob = null
        receiverHeartbeatJob?.cancel()
        receiverHeartbeatJob = null
    }

    private fun applyRemoteState(peer: DiscoveredPeer?, dto: PlaybackStateDto, queueDto: org.michimusic.link.dto.QueueDto?, deviceId: String) {
        // Never apply an old host's response after a controller switch.
        if (!isRemoteSelected || _sessionState.value.activeEndpoint.id != deviceId) return
        val endpoint = peer?.let { mapPeerToEndpoint(it, isConnected = true).copy(id = deviceId) }
            ?: _sessionState.value.activeEndpoint
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
                // attachRemote already cleared the previous host's track, and the guard
                // above pins this read to the host we are controlling. A status payload
                // without a title therefore cannot borrow another host's song, but it
                // must not erase the track this same host just confirmed either.
                currentTrack = remoteTrack ?: _sessionState.value.currentTrack,
                isPlaying = dto.effectiveState == "playing",
                position = dto.effectivePosition,
                duration = dto.effectiveDuration,
                remoteVolume = dto.effectiveVolume,
                // An explicitly empty remote queue must clear the previous host's queue.
                queue = if (queueDto != null) qTracks else _sessionState.value.queue,
                queueIndex = queueDto?.currentIndex ?: _sessionState.value.queueIndex,
                repeatMode = when (dto.repeat) { "one" -> 1; "all" -> 2; else -> 0 },
                shuffleMode = if (dto.shuffle) 1 else 0,
                isRemoteActive = true,
            )
        }
    }

    internal val isRemotePlaybackHost: Boolean
        get() {
            val state = _sessionState.value
            val ep = state.activeEndpoint
            return state.isRemoteActive &&
                !ep.isLocal &&
                ep.type != EndpointType.STREAM_RECEIVER &&
                !ep.capabilities.contains("AUDIO_OUTPUT") &&
                (ep.type in setOf(EndpointType.SERVER, EndpointType.DESKTOP_PLAYER) || ep.capabilities.contains("REMOTE_CONTROL"))
        }

    internal val isStreamReceiver: Boolean
        get() {
            val state = _sessionState.value
            val ep = state.activeEndpoint
            return state.isRemoteActive &&
                !ep.isLocal &&
                (ep.type == EndpointType.STREAM_RECEIVER || ep.capabilities.contains("AUDIO_OUTPUT"))
        }

    fun selectLocalEndpoint(
        intent: PlaybackTransitionIntent = PlaybackTransitionIntent.USER_SWITCH_OUTPUT,
        reason: StreamErrorReason? = null,
        message: String? = null,
        resumePlayback: Boolean = false
    ) {
        nextSessionEpoch()
        _activeQueuePlaybackOp.set(null)
        _pendingQueueConfirmation.value = null
        val wasRemote = isRemoteSelected
        isRemoteSelected = false
        stopRemoteSync()
        formatObserverJob?.cancel()
        formatObserverJob = null

        // P0 Acoustic Protection: Explicitly pause local controller BEFORE releasing
        // local output mute in RtpPcmAudioTap when not intentionally resuming local playback (K46D-27 / Codex).
        if (!resumePlayback) {
            audioController?.pause()
        }
        org.michimusic.player.PlayerDependencies.stopPcmStreaming()
        val finalMetrics = rtpAudioSender.getMetrics()
        rtpAudioSender.stop()
        val activeEp = _sessionState.value.activeEndpoint
        if (activeEp.type == EndpointType.STREAM_RECEIVER || activeEp.capabilities.contains("AUDIO_OUTPUT")) {
            val client = connectionManager.getClient(activeEp.id)
            val sTok = activeReceiverSessionToken
            if (sTok != null) {
                scope.launch(ioDispatcher) {
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
            isPlaying = if (resumePlayback) true else false,
            position = local?.position ?: 0L,
            duration = local?.duration ?: 0L,
            queue = local?.queue ?: emptyList(),
            queueIndex = local?.queueIndex ?: -1,
            repeatMode = local?.repeatMode ?: 0,
            shuffleMode = if (local?.shuffleMode == true) 1 else 0,
            isRemoteActive = false,
            lastSessionError = reason,
            lastTransitionIntent = intent,
            rtpMetrics = finalMetrics,
            statusMessage = message ?: if (wasRemote) "Reproduciendo en este teléfono" else null
        )

        if (resumePlayback) {
            audioController?.play()
        }
    }

    /** Switch UI authority back without transferring a queue or pausing the local sink. */
    fun attachLocalControl() {
        val old = _sessionState.value.activeEndpoint
        if (old.isLocal) return
        if (old.type == EndpointType.STREAM_RECEIVER || "AUDIO_OUTPUT" in old.capabilities) {
            selectLocalEndpoint() // receiver RTP requires the full safe transition
            return
        }
        nextSessionEpoch()
        _activeQueuePlaybackOp.set(null)
        _pendingQueueConfirmation.value = null
        stopRemoteSync()
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
            repeatMode = local?.repeatMode ?: 0,
            shuffleMode = if (local?.shuffleMode == true) 1 else 0,
            isRemoteActive = false,
            statusMessage = null,
            lastSessionError = null,
        )
    }

    fun attachRemote(target: PlaybackEndpoint, onResult: (Boolean, String) -> Unit) {
        if (target.isLocal) {
            val current = _sessionState.value.activeEndpoint
            if (!current.isLocal && (current.type == EndpointType.STREAM_RECEIVER ||
                "AUDIO_OUTPUT" in current.capabilities)) {
                onResult(false, "Detén primero la sesión Michi Stream desde Salida de audio")
                return
            }
            attachLocalControl()
            onResult(true, "Controlando este teléfono")
            return
        }
        // Preserve existing callers that already resolved an authenticated LinkClient:
        // the UI filters capability/connection; the service forbids receiver-only attaches.
        if (target.type == EndpointType.STREAM_RECEIVER ||
            "AUDIO_OUTPUT" in target.capabilities) {
            onResult(false, "Este dispositivo no admite control remoto de reproducción")
            return
        }
        if (_sessionState.value.activeEndpoint.id == target.id && isRemoteSelected) {
            onResult(true, "Ya estás controlando ${target.name}")
            return
        }
        val currentEndpoint = _sessionState.value.activeEndpoint
        if (isRemoteSelected && (currentEndpoint.type == EndpointType.STREAM_RECEIVER ||
            "AUDIO_OUTPUT" in currentEndpoint.capabilities)) {
            onResult(false, "Primero detén la salida Michi Stream desde Salida de audio")
            return
        }

        val client = connectionManager.getClient(target.id)
        if (client == null) {
            onResult(false, "No hay conexión activa con ${target.name}")
            return
        }

        nextSessionEpoch()
        _activeQueuePlaybackOp.set(null)
        _pendingQueueConfirmation.value = null
        org.michimusic.player.PlayerDependencies.publishOutputAuthority(target.id, false)
        stopRemoteSync() // Never leave the previous host's SSE/poll active.
        isRemoteSelected = true
        _sessionState.value = _sessionState.value.copy(
            activeEndpoint = target,
            isRemoteActive = true,
            currentTrack = null,
            queue = emptyList(),
            queueIndex = 0,
            isPlaying = false,
            position = 0L,
            duration = 0L,
        )
        // Connected paired hosts work even when multicast discovery is currently silent.
        startRemoteSync(linkDiscovery.peers.value.values.firstOrNull { it.deviceId == target.id }, target.id)
        onResult(true, "Controlando ${target.name}")
    }

    fun handoffTo(target: PlaybackEndpoint, onResult: (Boolean, String) -> Unit) {
        nextSessionEpoch()
        _activeQueuePlaybackOp.set(null)
        if (target.isLocal) {
            // Handoff Remote -> Local (K46D-32)
            val prevEndpoint = _sessionState.value.activeEndpoint
            val client = connectionManager.getClient(prevEndpoint.id)
            val wasPlaying = _sessionState.value.isPlaying
            val q = _sessionState.value.queue
            val idx = _sessionState.value.queueIndex
            val pos = _sessionState.value.position

            scope.launch {
                // 1. Pause remote first to avoid two simultaneous audible sources (K46D-38)
                if (_sessionState.value.isRemoteActive) {
                    if (client == null) {
                        onResult(false, "No hay conexión activa con ${prevEndpoint.name}")
                        return@launch
                    }

                    if (prevEndpoint.type == EndpointType.STREAM_RECEIVER || prevEndpoint.capabilities.contains("AUDIO_OUTPUT")) {
                        val tok = activeReceiverSessionToken
                        val patchResult = runCatching {
                            client.patchReceiverLiteSession(
                                org.michimusic.link.dto.ReceiverSessionPatchRequest(paused = true),
                                sessionToken = tok
                            )
                        }.getOrElse { Result.failure(it) }

                        if (patchResult.isFailure) {
                            onResult(false, "No se pudo pausar el receptor Michi Stream")
                            return@launch
                        }
                    } else {
                        val pauseResult = runCatching {
                            client.sendPlaybackCommand("pause")
                        }.getOrElse { Result.failure(it) }

                        if (pauseResult.isFailure) {
                            onResult(false, "No se pudo pausar la reproducción en ${prevEndpoint.name}")
                            return@launch
                        }
                    }
                }

                // 2. Prepare local playback queue and position
                if (q.isNotEmpty()) {
                    audioController?.playQueue(q, idx.coerceAtLeast(0))
                    if (pos > 0) audioController?.seekTo(pos)
                }

                // 3. Switch to local endpoint preserving playback intent
                selectLocalEndpoint(
                    intent = PlaybackTransitionIntent.USER_HANDOFF_TO_PHONE,
                    resumePlayback = wasPlaying
                )
                onResult(true, "Reproducción transferida al teléfono")
            }
        } else if (target.type == EndpointType.STREAM_RECEIVER || target.capabilities.contains("AUDIO_OUTPUT")) {
            // Handoff Local -> Remote
            val prevEp = _sessionState.value.activeEndpoint
            if (prevEp.id != target.id && (prevEp.type == EndpointType.STREAM_RECEIVER || prevEp.capabilities.contains("AUDIO_OUTPUT"))) {
                val prevClient = connectionManager.getClient(prevEp.id)
                val prevTok = activeReceiverSessionToken
                scope.launch(ioDispatcher) {
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
                        scope.launch(ioDispatcher) {
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
                        scope.launch(ioDispatcher) {
                            client.deleteReceiverLiteSession(sessionResp.sessionToken)
                        }
                        onResult(false, "No se pudo inicializar la transmisión RTP hacia $targetHost:${eff.streamPort}")
                        return@onSuccess
                    }

                    activeReceiverSessionId = sessionResp.sessionId
                    activeReceiverSessionToken = sessionResp.sessionToken
                    activeReceiverEffective = sessionResp.effective
                    currentReceiverSequence = 1L

                    // 2. Only once RTP is active, start PCM tap
                    org.michimusic.player.PlayerDependencies.startPcmStreaming(
                        listener = { pcmChunk ->
                            rtpAudioSender.sendPcmChunk(pcmChunk)
                        },
                        sessionToken = sessionResp.sessionToken,
                    )

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
                    receiverHeartbeatJob = scope.launch(ioDispatcher) {
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
                            } else {
                                // Processor reset or track transition re-negotiating identical PCM format.
                                // The active receiver session remains valid; unlock tap and resume transmission.
                                org.michimusic.player.PlayerDependencies.resumePcmStreaming(
                                    format = org.michimusic.core.models.PcmFormat(
                                        sampleRate = currentEffective.sampleRate,
                                        channels = currentEffective.channels,
                                        bitDepth = currentEffective.bitDepth,
                                        codec = currentEffective.codec,
                                    ),
                                    sessionToken = activeReceiverSessionToken,
                                )
                            }
                        }
                    }

                    onResult(true, "Sesión de audio y transmisión RTP activa en ${target.name}")
                }.onFailure { err ->
                    // Honest UI & Sink safety: Do not leave local audio in an inconsistent state on failure
                    org.michimusic.player.PlayerDependencies.stopPcmStreaming()
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
                        // Ya pertenece al catálogo remoto (identificador de catálogo del servidor)
                        if (track.source == TrackSource.STREAMING && track.id.isNotEmpty() && !track.id.startsWith("content://")) {
                            resolvedTrackIds.add(track.id)
                            continue
                        }

                        // Pista local: resolver contra catálogo remoto
                        val searchResult = client.search(track.title).getOrNull()
                        val match = searchResult?.firstOrNull { candidate ->
                            // 1. Match by canonical content hash if available
                            val hashMatch = track.contentHash.isNotEmpty() &&
                                candidate.contentHash.isNotEmpty() &&
                                track.contentHash.equals(candidate.contentHash, ignoreCase = true)
                            if (hashMatch) return@firstOrNull true

                            // 2. Match by normalized metadata fallback signature
                            val idMatch = track.metadataFallbackIdentity.equals(candidate.metadataFallbackIdentity, ignoreCase = true)
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

                    if (hasUnresolvableLocalTrack || resolvedTrackIds.size != q.size) {
                        onResult(false, "No se pudo transferir: algunas pistas locales no existen en el servidor ${target.name}")
                        return@launch
                    }

                    val resolvedTracks = q.mapIndexed { i, t -> t.copy(id = resolvedTrackIds[i]) }
                    val remoteSt = client.getPlaybackState().getOrNull()
                    val remoteQ = client.getQueue().getOrNull()
                    val remoteSize = remoteQ?.tracks?.size ?: 0
                    val remotePlaying = remoteSt?.playing == true

                    // K52-01: Preflight evaluation before destructive queue transfer during handoff
                    val preflight = RemoteQueuePreflightPolicy.evaluate(
                        destinationId = target.id,
                        destinationName = target.name,
                        remoteQueueSize = remoteSize,
                        remoteActiveTrackTitle = remoteQ?.tracks?.getOrNull(remoteSt?.queueIndex ?: -1)?.title ?: remoteSt?.trackId,
                        remotePlaying = remotePlaying,
                        proposedTracks = resolvedTracks,
                        proposedStartIndex = idx.coerceAtLeast(0),
                        sessionEpoch = _sessionEpoch.get(),
                        snapshotFingerprint = remoteQ?.tracks?.joinToString(",") { it.trackId }
                    )

                    if (preflight is RemoteQueuePreflightDecision.ConfirmationRequired) {
                        _issuedTickets.values.removeIf { it.endpointId == target.id }
                        _issuedTickets[preflight.ticket.ticketId] = preflight.ticket
                        _pendingQueueConfirmation.value = preflight

                        isRemoteSelected = true
                        _sessionState.value = _sessionState.value.copy(
                            activeEndpoint = target,
                            isRemoteActive = true
                        )
                        onResult(true, "Confirmación requerida para transferir cola a ${target.name}")
                        return@launch
                    }

                    val req = org.michimusic.link.dto.QueueTransferRequest(
                        trackIds = resolvedTrackIds,
                        currentIndex = idx.coerceAtLeast(0),
                        positionMs = pos,
                        source = "local",
                        queueId = remoteQ?.queueId?.ifBlank { null }
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

    fun play() {
        val activeEp = _sessionState.value.activeEndpoint
        if (_sessionState.value.isRemoteActive) {
            val client = connectionManager.getClient(activeEp.id)
            if (activeEp.type == EndpointType.STREAM_RECEIVER || activeEp.capabilities.contains("AUDIO_OUTPUT")) {
                val tok = activeReceiverSessionToken
                scope.launch {
                    val result = client?.patchReceiverLiteSession(
                        org.michimusic.link.dto.ReceiverSessionPatchRequest(paused = false),
                        sessionToken = tok
                    )
                    if (result != null && result.isSuccess) {
                        val pcmFormat = activeReceiverEffective?.let { eff ->
                            org.michimusic.core.models.PcmFormat(
                                sampleRate = eff.sampleRate,
                                channels = eff.channels,
                                bitDepth = eff.bitDepth,
                                codec = eff.codec,
                            )
                        } ?: org.michimusic.player.PlayerDependencies.getActivePcmFormat()
                        if (pcmFormat != null) {
                            org.michimusic.player.PlayerDependencies.resumePcmStreaming(
                                format = pcmFormat,
                                sessionToken = activeReceiverSessionToken,
                            )
                        }
                        audioController?.play()
                        _sessionState.value = _sessionState.value.copy(isPlaying = true, statusMessage = null)
                    } else {
                        val errMsg = result?.exceptionOrNull()?.message ?: "Cliente no disponible"
                        _sessionState.value = _sessionState.value.copy(statusMessage = "Error reanudando audio en receptor: $errMsg")
                    }
                }
            } else {
                val opEpoch = _sessionEpoch.get()
                scope.launch {
                    val result = client?.sendPlaybackCommand("play")
                    if (opEpoch != _sessionEpoch.get() || _sessionState.value.activeEndpoint.id != activeEp.id) return@launch
                    if (result != null && result.isSuccess) {
                        _sessionState.value = _sessionState.value.copy(isPlaying = true, statusMessage = null)
                    } else {
                        val errMsg = result?.exceptionOrNull()?.message ?: "Cliente remoto no conectado"
                        _sessionState.value = _sessionState.value.copy(statusMessage = "Error al enviar comando play: $errMsg")
                    }
                }
            }
        } else {
            audioController?.play()
        }
    }

    fun pause() {
        val activeEp = _sessionState.value.activeEndpoint
        if (_sessionState.value.isRemoteActive) {
            val client = connectionManager.getClient(activeEp.id)
            if (activeEp.type == EndpointType.STREAM_RECEIVER || activeEp.capabilities.contains("AUDIO_OUTPUT")) {
                val tok = activeReceiverSessionToken
                scope.launch {
                    val result = client?.patchReceiverLiteSession(
                        org.michimusic.link.dto.ReceiverSessionPatchRequest(paused = true),
                        sessionToken = tok
                    )
                    if (result != null && result.isSuccess) {
                        org.michimusic.player.PlayerDependencies.pausePcmStreaming()
                        audioController?.pause()
                        _sessionState.value = _sessionState.value.copy(isPlaying = false, statusMessage = null)
                    } else {
                        val errMsg = result?.exceptionOrNull()?.message ?: "Cliente no disponible"
                        _sessionState.value = _sessionState.value.copy(statusMessage = "Error pausando audio en receptor: $errMsg")
                    }
                }
            } else {
                val opEpoch = _sessionEpoch.get()
                scope.launch {
                    val result = client?.sendPlaybackCommand("pause")
                    if (opEpoch != _sessionEpoch.get() || _sessionState.value.activeEndpoint.id != activeEp.id) return@launch
                    if (result != null && result.isSuccess) {
                        _sessionState.value = _sessionState.value.copy(isPlaying = false, statusMessage = null)
                    } else {
                        val errMsg = result?.exceptionOrNull()?.message ?: "Cliente remoto no conectado"
                        _sessionState.value = _sessionState.value.copy(statusMessage = "Error al enviar comando pause: $errMsg")
                    }
                }
            }
        } else {
            audioController?.pause()
        }
    }

    fun stop() {
        val activeEp = _sessionState.value.activeEndpoint
        if (_sessionState.value.isRemoteActive) {
            val client = connectionManager.getClient(activeEp.id)
            if (activeEp.type == EndpointType.STREAM_RECEIVER || activeEp.capabilities.contains("AUDIO_OUTPUT")) {
                val tok = activeReceiverSessionToken
                scope.launch {
                    val result = client?.patchReceiverLiteSession(
                        org.michimusic.link.dto.ReceiverSessionPatchRequest(paused = true),
                        sessionToken = tok
                    )
                    if (result != null && result.isSuccess) {
                        org.michimusic.player.PlayerDependencies.stopPcmStreaming()
                        audioController?.seekTo(0L)
                        audioController?.pause()
                        _sessionState.value = _sessionState.value.copy(isPlaying = false, position = 0L, statusMessage = null)
                    } else {
                        val errMsg = result?.exceptionOrNull()?.message ?: "Cliente no disponible"
                        _sessionState.value = _sessionState.value.copy(statusMessage = "Error deteniendo sesión en receptor: $errMsg")
                    }
                }
            } else {
                val opEpoch = _sessionEpoch.get()
                scope.launch {
                    val result = client?.sendPlaybackCommand("stop")
                    if (opEpoch != _sessionEpoch.get() || _sessionState.value.activeEndpoint.id != activeEp.id) return@launch
                    if (result != null && result.isSuccess) {
                        _sessionState.value = _sessionState.value.copy(isPlaying = false, position = 0L, statusMessage = null)
                    } else {
                        val errMsg = result?.exceptionOrNull()?.message ?: "Cliente remoto no conectado"
                        _sessionState.value = _sessionState.value.copy(statusMessage = "Error al enviar comando stop: $errMsg")
                    }
                }
            }
        } else {
            audioController?.seekTo(0L)
            audioController?.pause()
        }
    }

    fun playPause() {
        val isPlaying = _sessionState.value.isPlaying
        if (isPlaying) pause() else if (_sessionState.value.isRemoteActive) play()
        else if (audioController?.playFromUserGesture() != true) {
            _sessionState.value = _sessionState.value.copy(
                statusMessage = "No se pudo reanudar. Selecciona una salida de audio disponible.")
        }
    }

    fun skipNext() {
        if (isRemotePlaybackHost) {
            val targetEndpoint = _sessionState.value.activeEndpoint
            val opEpoch = _sessionEpoch.get()
            scope.launch {
                if (opEpoch != _sessionEpoch.get() || _sessionState.value.activeEndpoint.id != targetEndpoint.id) return@launch
                connectionManager.getClient(targetEndpoint.id)?.sendPlaybackCommand("next")
            }
        } else {
            audioController?.skipNext()
        }
    }

    fun skipPrevious() {
        if (isRemotePlaybackHost) {
            val targetEndpoint = _sessionState.value.activeEndpoint
            val opEpoch = _sessionEpoch.get()
            scope.launch {
                if (opEpoch != _sessionEpoch.get() || _sessionState.value.activeEndpoint.id != targetEndpoint.id) return@launch
                connectionManager.getClient(targetEndpoint.id)?.sendPlaybackCommand("previous")
            }
        } else {
            audioController?.skipPrevious()
        }
    }

    fun seekTo(positionMs: Long) {
        if (isRemotePlaybackHost) {
            val targetEndpoint = _sessionState.value.activeEndpoint
            remoteSeekRequests.trySend(
                RemoteSeekRequest(
                    endpointId = targetEndpoint.id,
                    epoch = _sessionEpoch.get(),
                    positionMs = positionMs,
                ),
            )
        } else {
            audioController?.seekTo(positionMs)
        }
    }

    fun skipToQueueIndex(index: Int) {
        if (isRemotePlaybackHost) {
            val state = _sessionState.value
            if (index !in state.queue.indices) return
            val targetEndpoint = state.activeEndpoint
            val selectedId = state.queue[index].id
            val opEpoch = _sessionEpoch.get()
            // Never race two destructive/positional operations from the same UI session.
            if (state.lastRemoteCommand?.let {
                    it.endpointId == targetEndpoint.id && it.status == RemoteCommandStatus.PENDING
                } == true) return
            val cmd = RemoteCommandRecord(
                endpointId = targetEndpoint.id,
                epoch = opEpoch,
                action = "jump_queue_index",
                param = index.toString(),
                status = RemoteCommandStatus.PENDING,
            )
            _sessionState.value = state.copy(lastRemoteCommand = cmd)
            scope.launch {
                fun stillCurrent(): Boolean = opEpoch == _sessionEpoch.get() &&
                    _sessionState.value.activeEndpoint.id == targetEndpoint.id &&
                    _sessionState.value.lastRemoteCommand?.commandId == cmd.commandId
                if (!stillCurrent()) return@launch
                val client = connectionManager.getClient(targetEndpoint.id)
                val result = client?.queueJump(index)
                if (!stillCurrent()) return@launch
                val outcome = if (result?.isSuccess == true) {
                    // The transport ACK does not prove that the host selected the index.
                    val observed = runCatching { client?.getQueue()?.getOrNull() }.getOrNull()
                    if (!stillCurrent()) return@launch
                    if (observed != null) {
                        val tracks = observed.tracks.map { it.toDomainTrack() }
                        val observedIndex = observed.currentIndex.takeIf { it in tracks.indices } ?: -1
                        _sessionState.value = _sessionState.value.copy(
                            queue = tracks,
                            queueIndex = observedIndex,
                            currentTrack = tracks.getOrNull(observedIndex),
                        )
                        if (remoteQueueJumpWasObserved(index, selectedId, observedIndex, tracks.getOrNull(observedIndex)?.id)) {
                            cmd.copy(status = RemoteCommandStatus.CONFIRMED, epoch = opEpoch)
                        } else {
                            cmd.copy(
                                status = RemoteCommandStatus.ACCEPTED,
                                epoch = opEpoch,
                                resultMessage = "El host aceptó el salto, pero informó otra posición; se muestra la cola observada",
                            )
                        }
                    } else {
                        cmd.copy(
                            status = RemoteCommandStatus.ACCEPTED,
                            epoch = opEpoch,
                            resultMessage = "Salto aceptado; no se pudo confirmar el índice remoto",
                        )
                    }
                } else {
                    val (failure, reason) = resolveFailureStatus(client, result)
                    cmd.copy(status = failure, epoch = opEpoch, resultMessage = reason)
                }
                if (stillCurrent()) {
                    _sessionState.value = _sessionState.value.copy(lastRemoteCommand = outcome)
                }
            }
        } else {
            audioController?.skipToQueueIndex(index)
        }
    }

    private fun resolveFailureStatus(client: LinkClient?, result: Result<Any?>?): Pair<RemoteCommandStatus, String> {
        if (client == null) {
            return RemoteCommandStatus.DISCONNECTED to "Cliente no disponible o desconectado"
        }
        val ex = result?.exceptionOrNull()
        val msg = ex?.message ?: "Comando rechazado por el endpoint"
        return when {
            ex is java.net.SocketTimeoutException ||
            ex is java.util.concurrent.TimeoutException ||
            msg.contains("timeout", ignoreCase = true) -> {
                RemoteCommandStatus.TIMED_OUT to msg
            }
            ex is java.io.IOException ||
            msg.contains("connect", ignoreCase = true) ||
            msg.contains("broken pipe", ignoreCase = true) -> {
                RemoteCommandStatus.DISCONNECTED to msg
            }
            else -> {
                RemoteCommandStatus.REJECTED to msg
            }
        }
    }

    fun removeFromQueue(index: Int) {
        if (isRemotePlaybackHost) {
            val targetEndpoint = _sessionState.value.activeEndpoint
            val opEpoch = _sessionEpoch.get()
            val client = connectionManager.getClient(targetEndpoint.id)
            val currentQ = _sessionState.value.queue
            if (index in currentQ.indices) {
                val item = currentQ[index]
                if (!remoteQueueRemovalUnambiguous(currentQ.map { it.id }, index)) {
                    _sessionState.value = _sessionState.value.copy(
                        lastRemoteCommand = RemoteCommandRecord(
                            endpointId = targetEndpoint.id,
                            epoch = opEpoch,
                            action = "remove_queue_item",
                            param = item.id,
                            status = RemoteCommandStatus.UNSUPPORTED,
                            resultMessage = "No se puede eliminar una aparición repetida: el servidor no informa un ID único de elemento",
                        ),
                    )
                    return
                }
                val cmd = RemoteCommandRecord(
                    endpointId = targetEndpoint.id,
                    epoch = opEpoch,
                    action = "remove_queue_item",
                    param = item.id,
                    status = RemoteCommandStatus.PENDING,
                )
                _sessionState.value = _sessionState.value.copy(lastRemoteCommand = cmd)
                scope.launch {
                    val result = client?.removeQueueItem(item.id)
                    if (opEpoch != _sessionEpoch.get() || _sessionState.value.activeEndpoint.id != targetEndpoint.id) return@launch
                    val outcome = if (result != null && result.isSuccess) {
                        var queueSynced = false
                        var queueErr = ""
                        client.getQueue().onSuccess { qDto ->
                            if (_sessionState.value.activeEndpoint.id == targetEndpoint.id && opEpoch == _sessionEpoch.get()) {
                                val tracks = qDto.tracks.map { it.toDomainTrack() }
                                _sessionState.value = _sessionState.value.copy(queue = tracks, queueIndex = qDto.currentIndex)
                            }
                            queueSynced = true
                        }.onFailure { err ->
                            queueErr = err.message ?: "Fallo al sincronizar cola remota"
                        }
                        if (queueSynced) {
                            cmd.copy(status = RemoteCommandStatus.CONFIRMED, epoch = opEpoch)
                        } else {
                            cmd.copy(status = RemoteCommandStatus.ACCEPTED, epoch = opEpoch, resultMessage = "Aceptado por el servidor; sincronización de estado pendiente ($queueErr)")
                        }
                    } else {
                        val (failStatus, failMsg) = resolveFailureStatus(client, result)
                        cmd.copy(status = failStatus, epoch = opEpoch, resultMessage = failMsg)
                    }
                    if (_sessionState.value.activeEndpoint.id == targetEndpoint.id && opEpoch == _sessionEpoch.get()) {
                        _sessionState.value = _sessionState.value.copy(lastRemoteCommand = outcome)
                    }
                }
            }
        } else {
            audioController?.removeFromQueue(index)
        }
    }

    fun playNow(track: Track) {
        if (isRemotePlaybackHost) {
            val targetEndpoint = _sessionState.value.activeEndpoint
            val opEpoch = _sessionEpoch.get()
            val client = connectionManager.getClient(targetEndpoint.id)
            val cmd = RemoteCommandRecord(
                endpointId = targetEndpoint.id,
                epoch = opEpoch,
                action = "play_track",
                param = track.id,
                status = RemoteCommandStatus.PENDING,
            )
            _sessionState.value = _sessionState.value.copy(lastRemoteCommand = cmd)
            scope.launch {
                val result = client?.sendPlaybackCommand("play_track", track.id)
                if (opEpoch != _sessionEpoch.get() || _sessionState.value.activeEndpoint.id != targetEndpoint.id) return@launch
                val outcome = if (result != null && result.isSuccess) {
                    var synced = false
                    var syncErr = ""
                    val stRes = client.getPlaybackState()
                    val qRes = client.getQueue()
                    if (stRes.isSuccess && qRes.isSuccess) {
                        val st = stRes.getOrThrow()
                        val q = qRes.getOrThrow()
                        if (_sessionState.value.activeEndpoint.id == targetEndpoint.id && opEpoch == _sessionEpoch.get()) {
                            val tracks = q.tracks.map { it.toDomainTrack() }
                            val current = tracks.getOrNull(st.queueIndex)
                            _sessionState.value = _sessionState.value.copy(
                                currentTrack = current,
                                isPlaying = st.playing,
                                position = st.effectivePosition,
                                duration = st.effectiveDuration,
                                queue = tracks,
                                queueIndex = st.queueIndex
                            )
                        }
                        synced = true
                    } else {
                        syncErr = qRes.exceptionOrNull()?.message
                            ?: stRes.exceptionOrNull()?.message
                            ?: "Fallo al sincronizar estado y cola remotos"
                    }
                    if (synced) {
                        cmd.copy(status = RemoteCommandStatus.CONFIRMED, epoch = opEpoch)
                    } else {
                        cmd.copy(status = RemoteCommandStatus.ACCEPTED, epoch = opEpoch, resultMessage = "Aceptado por el servidor; sincronización de estado pendiente ($syncErr)")
                    }
                } else {
                    val (failStatus, failMsg) = resolveFailureStatus(client, result)
                    cmd.copy(status = failStatus, epoch = opEpoch, resultMessage = failMsg)
                }
                if (_sessionState.value.activeEndpoint.id == targetEndpoint.id && opEpoch == _sessionEpoch.get()) {
                    _sessionState.value = _sessionState.value.copy(lastRemoteCommand = outcome)
                }
            }
        } else {
            val q = _sessionState.value.queue
            val existingIdx = q.indexOfFirst { it.id == track.id }
            if (existingIdx >= 0) {
                skipToQueueIndex(existingIdx)
            } else {
                audioController?.playQueue(listOf(track), 0)
            }
        }
    }

    /**
     * Authoritative queue playback coordinator (Gate 0 / K46D-33 / K46D-34).
     *
     * Authority 1: STREAM_RECEIVER (Michi Stream ReceiverLite)
     *   Mobile is the decoder and sends PCM RTP to Michi Stream with phone speaker muted.
     *   Local and streaming tracks are fully supported through the local engine.
     * Authority 2: REMOTE_PLAYBACK_HOST (Server / Desktop Player)
     *   Remote host decodes from its own music catalog. Full queue semantics via transferQueue,
     *   or honest warning if local files cannot be decoded by the remote host.
     * Authority 3: Local Phone
     *   Dispatched directly to AudioController.
     */
    fun playQueue(
        tracks: List<Track>,
        startIndex: Int = 0,
        confirmedAction: RemoteQueueAction? = null
    ) {
        if (tracks.isEmpty()) return
        val validIndex = startIndex.coerceIn(0, tracks.lastIndex)
        if (!isRemotePlaybackHost) {
            audioController?.playQueue(tracks, validIndex)
            return
        }
        scope.launch {
            requestQueuePlayback(tracks, startIndex, confirmedAction)
        }
    }

    suspend fun requestQueuePlayback(
        tracks: List<Track>,
        startIndex: Int = 0,
        confirmedAction: RemoteQueueAction? = null,
        confirmedTicket: RemoteQueueAuthorizationTicket? = null,
    ): QueuePlaybackOutcome {
        val opId = java.util.UUID.randomUUID().toString()
        _activeQueuePlaybackOp.set(opId)
        val opEpoch = _sessionEpoch.get()
        val validIndex = startIndex.coerceIn(0, (tracks.size - 1).coerceAtLeast(0))
        val activeEp = _sessionState.value.activeEndpoint

        if (tracks.isEmpty()) {
            return QueuePlaybackOutcome.Rejected(
                operationId = opId,
                endpointId = activeEp.id,
                requestedTrackCount = 0,
                requestedIndex = startIndex,
                epoch = opEpoch,
                reason = "La lista de reproducción está vacía"
            )
        }

        val selectedTrack = tracks[validIndex]

        // Authority 1: Michi Stream Receiver
        if (isStreamReceiver) {
            audioController?.playQueue(tracks, validIndex)
            return QueuePlaybackOutcome.Confirmed(
                operationId = opId,
                endpointId = activeEp.id,
                requestedTrackCount = tracks.size,
                requestedIndex = validIndex,
                epoch = opEpoch,
                message = "Reproduciendo cola en ${activeEp.name} vía Michi Stream"
            )
        }

        // Authority 2: Remote Playback Host
        if (isRemotePlaybackHost) {
            val client = connectionManager.getClient(activeEp.id)
            if (client == null) {
                val cmd = RemoteCommandRecord(
                    commandId = opId,
                    endpointId = activeEp.id,
                    epoch = opEpoch,
                    action = "play_queue",
                    param = selectedTrack.id,
                    status = RemoteCommandStatus.DISCONNECTED,
                    resultMessage = "No hay conexión activa con ${activeEp.name}"
                )
                _sessionState.value = _sessionState.value.copy(
                    lastRemoteCommand = cmd,
                    statusMessage = "No hay conexión activa con ${activeEp.name}"
                )
                return QueuePlaybackOutcome.Disconnected(
                    operationId = opId,
                    endpointId = activeEp.id,
                    requestedTrackCount = tracks.size,
                    requestedIndex = validIndex,
                    epoch = opEpoch,
                    reason = "No hay conexión activa con ${activeEp.name}"
                )
            }

            val allRemotePlayable = tracks.all { t ->
                t.source == TrackSource.STREAMING ||
                    t.filepath.startsWith("http://") ||
                    t.filepath.startsWith("https://") ||
                    t.id.startsWith("srv_") ||
                    t.id.startsWith("remote_")
            }

            if (!allRemotePlayable) {
                val reasonMsg = "Pista local no disponible en ${activeEp.name}. Cambia la salida a 'Este teléfono' para reproducir archivos locales."
                _sessionState.value = _sessionState.value.copy(
                    statusMessage = reasonMsg
                )
                return QueuePlaybackOutcome.Rejected(
                    operationId = opId,
                    endpointId = activeEp.id,
                    requestedTrackCount = tracks.size,
                    requestedIndex = validIndex,
                    epoch = opEpoch,
                    reason = reasonMsg
                )
            }

            // K49-03: Pre-dispatch authorization evaluation before issuing destructive remote queue replacements.
            if (confirmedAction == RemoteQueueAction.CANCEL) {
                _pendingQueueConfirmation.value = null
                return QueuePlaybackOutcome.Cancelled(
                    operationId = opId,
                    endpointId = activeEp.id,
                    requestedTrackCount = tracks.size,
                    requestedIndex = validIndex,
                    epoch = opEpoch,
                    reason = "Transferencia de cola cancelada por el usuario"
                )
            }

            var initialRemoteFingerprint: String? = null
            var initialRemoteSize = 0
            var initialRemotePlaying = false

            if (confirmedAction == null) {
                // M50: Unknown host state must NEVER be interpreted as an empty queue.
                val freshSt = runCatching { client.getPlaybackState().getOrNull() }.getOrNull()
                if (!isQueuePlaybackCurrent(opId, opEpoch, activeEp.id)) {
                    return QueuePlaybackOutcome.Obsolete(opId, activeEp.id, tracks.size, validIndex, opEpoch,
                        "El destino cambió mientras se consultaba su reproducción")
                }
                val freshQ = runCatching { client.getQueue().getOrNull() }.getOrNull()
                if (!isQueuePlaybackCurrent(opId, opEpoch, activeEp.id)) {
                    return QueuePlaybackOutcome.Obsolete(opId, activeEp.id, tracks.size, validIndex, opEpoch,
                        "El destino cambió mientras se consultaba su cola")
                }
                if (freshSt == null || freshQ == null) {
                    return QueuePlaybackOutcome.Failed(opId, activeEp.id, tracks.size, validIndex, opEpoch,
                        "No se pudo comprobar el estado actual de la cola remota; no se ha enviado ninguna modificación")
                }
                val qTracks = freshQ.tracks.map { it.toDomainTrack() }
                _sessionState.value = _sessionState.value.copy(
                    currentTrack = qTracks.getOrNull(freshSt.queueIndex),
                    isPlaying = freshSt.playing,
                    position = freshSt.effectivePosition,
                    duration = freshSt.effectiveDuration,
                    queue = qTracks,
                    queueIndex = freshSt.queueIndex,
                )
                val remoteFingerprint = freshQ.tracks.joinToString(",") { it.trackId }
                initialRemoteFingerprint = remoteFingerprint
                initialRemoteSize = freshQ.tracks.size
                initialRemotePlaying = freshSt.playing

                val preflightDecision = RemoteQueuePreflightPolicy.evaluate(
                    destinationId = activeEp.id,
                    destinationName = activeEp.name,
                    remoteQueueSize = freshQ.tracks.size,
                    remoteActiveTrackTitle = freshQ.tracks.getOrNull(freshSt.queueIndex)?.title,
                    remotePlaying = freshSt.playing,
                    proposedTracks = tracks,
                    proposedStartIndex = validIndex,
                    sessionEpoch = opEpoch,
                    snapshotFingerprint = remoteFingerprint,
                )
                if (preflightDecision is RemoteQueuePreflightDecision.ConfirmationRequired) {
                    // K52-03: Invalidate prior unconsumed tickets for this endpoint to prevent ticket accumulation
                    _issuedTickets.values.removeIf { it.endpointId == activeEp.id }
                    // K50-02: Register issued ticket in authoritative manager registry
                    _issuedTickets[preflightDecision.ticket.ticketId] = preflightDecision.ticket
                    _pendingQueueConfirmation.value = preflightDecision
                    return QueuePlaybackOutcome.PendingConfirmation(
                        operationId = opId,
                        endpointId = activeEp.id,
                        requestedTrackCount = tracks.size,
                        requestedIndex = validIndex,
                        epoch = opEpoch,
                        confirmation = preflightDecision
                    )
                }
            }

            _pendingQueueConfirmation.value = null

            // M50: Explicit user action requires a valid ticket. No implicit bypass.
            if (confirmedAction != null && confirmedTicket == null) {
                return QueuePlaybackOutcome.Rejected(opId, activeEp.id, tracks.size, validIndex, opEpoch,
                    "La modificación de cola requiere una confirmación válida")
            }
            if (confirmedTicket != null) {
                // K50-02: Validate ticket against issued registry and consume it (single-use policy)
                val recordedTicket = _issuedTickets.remove(confirmedTicket.ticketId)
                if (recordedTicket == null || recordedTicket != confirmedTicket) {
                    return QueuePlaybackOutcome.Rejected(
                        opId, activeEp.id, tracks.size, validIndex, opEpoch,
                        "El ticket de confirmación no fue emitido por esta sesión o ya ha sido consumido"
                    )
                }
                if (confirmedTicket.isExpired) {
                    return QueuePlaybackOutcome.Cancelled(opId, activeEp.id, tracks.size, validIndex, opEpoch,
                        "La confirmación de cola caducó. Vuelve a intentarlo")
                }
                if (confirmedTicket.sessionEpoch != opEpoch || confirmedTicket.endpointId != activeEp.id) {
                    return QueuePlaybackOutcome.Obsolete(opId, activeEp.id, tracks.size, validIndex, opEpoch,
                        "El destino o la sesión cambiaron durante la confirmación")
                }
                // K52-03: Contextual binding validation - ticket must match proposed tracks fingerprint and start index
                val requestedFingerprint = tracks.joinToString(",") { it.id }
                if (confirmedTicket.proposedTracksFingerprint != null &&
                    confirmedTicket.proposedTracksFingerprint != requestedFingerprint) {
                    return QueuePlaybackOutcome.Rejected(
                        opId, activeEp.id, tracks.size, validIndex, opEpoch,
                        "Las pistas solicitadas no coinciden con la autorización previa del ticket"
                    )
                }
                if (confirmedTicket.proposedStartIndex != null &&
                    confirmedTicket.proposedStartIndex != validIndex) {
                    return QueuePlaybackOutcome.Rejected(
                        opId, activeEp.id, tracks.size, validIndex, opEpoch,
                        "El índice de reproducción no coincide con la autorización previa del ticket"
                    )
                }
            }

            // Never mutate a host on an unknown state or after an obsolete network response.
            val liveSt = runCatching { client.getPlaybackState().getOrNull() }.getOrNull()
            if (!isQueuePlaybackCurrent(opId, opEpoch, activeEp.id)) {
                return QueuePlaybackOutcome.Obsolete(opId, activeEp.id, tracks.size, validIndex, opEpoch,
                    "La reproducción cambió durante la comprobación final")
            }
            val liveQ = runCatching { client.getQueue().getOrNull() }.getOrNull()
            if (!isQueuePlaybackCurrent(opId, opEpoch, activeEp.id)) {
                return QueuePlaybackOutcome.Obsolete(opId, activeEp.id, tracks.size, validIndex, opEpoch,
                    "La cola cambió de destino durante la comprobación final")
            }
            if (liveSt == null || liveQ == null) {
                return QueuePlaybackOutcome.Failed(opId, activeEp.id, tracks.size, validIndex, opEpoch,
                    "No se pudo verificar la cola del host; modificación no enviada")
            }

            val liveFingerprint = liveQ.tracks.joinToString(",") { it.trackId }
            if (confirmedAction == null) {
                // K50-01: An initially empty/idle queue must NOT become occupied without explicit user confirmation!
                if (liveQ.tracks.isNotEmpty() || liveSt.playing ||
                    liveQ.tracks.size != initialRemoteSize || liveFingerprint != initialRemoteFingerprint) {
                    val newDecision = RemoteQueuePreflightPolicy.evaluate(
                        destinationId = activeEp.id,
                        destinationName = activeEp.name,
                        remoteQueueSize = liveQ.tracks.size,
                        remoteActiveTrackTitle = liveQ.tracks.getOrNull(liveSt.queueIndex)?.title,
                        remotePlaying = liveSt.playing,
                        proposedTracks = tracks,
                        proposedStartIndex = validIndex,
                        sessionEpoch = opEpoch,
                        snapshotFingerprint = liveFingerprint,
                    )
                    if (newDecision is RemoteQueuePreflightDecision.ConfirmationRequired) {
                        _issuedTickets[newDecision.ticket.ticketId] = newDecision.ticket
                        _pendingQueueConfirmation.value = newDecision
                        return QueuePlaybackOutcome.PendingConfirmation(
                            operationId = opId,
                            endpointId = activeEp.id,
                            requestedTrackCount = tracks.size,
                            requestedIndex = validIndex,
                            epoch = opEpoch,
                            confirmation = newDecision
                        )
                    } else {
                        return QueuePlaybackOutcome.Cancelled(opId, activeEp.id, tracks.size, validIndex, opEpoch,
                            "La cola remota cambió antes de transferir; operación detenida")
                    }
                }
            } else if (confirmedTicket != null) {
                if (confirmedTicket.snapshotTrackCount != liveQ.tracks.size ||
                    confirmedTicket.snapshotTrackFingerprint == null ||
                    confirmedTicket.snapshotTrackFingerprint != liveFingerprint) {
                    return QueuePlaybackOutcome.Cancelled(opId, activeEp.id, tracks.size, validIndex, opEpoch,
                        "La cola remota cambió desde la confirmación; vuelve a revisarla")
                }
            }

            val isAppend = confirmedAction == RemoteQueueAction.APPEND
            val finalTracks = if (isAppend) liveQ.tracks.map { it.toDomainTrack() } + tracks else tracks
            val finalIndex = if (isAppend) {
                liveSt.queueIndex.coerceIn(0, (finalTracks.size - 1).coerceAtLeast(0))
            } else validIndex
            val finalPos = if (isAppend) liveSt.effectivePosition else 0L
            val transferSource = if (isAppend) "mobile_queue_append" else "mobile_queue"

            val trackIds = finalTracks.map { it.id }
            val cmd = RemoteCommandRecord(
                commandId = opId,
                endpointId = activeEp.id,
                epoch = opEpoch,
                action = if (isAppend) "append_queue" else "play_queue",
                param = selectedTrack.id,
                status = RemoteCommandStatus.PENDING,
            )
            _sessionState.value = _sessionState.value.copy(lastRemoteCommand = cmd)

            val req = org.michimusic.link.dto.QueueTransferRequest(
                trackIds = trackIds,
                currentIndex = finalIndex,
                positionMs = finalPos,
                source = transferSource,
                queueId = liveQ.queueId.ifBlank { null }
            )

            // Operation check before transferQueue
            if (!isQueuePlaybackCurrent(opId, opEpoch, activeEp.id)) {
                return QueuePlaybackOutcome.Obsolete(
                    operationId = opId,
                    endpointId = activeEp.id,
                    requestedTrackCount = finalTracks.size,
                    requestedIndex = finalIndex,
                    epoch = opEpoch,
                    reason = "Sesión invalidada antes de transferir cola"
                )
            }

            val transferRes = client.transferQueue(req)

            // Epoch & operation guard after transferQueue
            if (!isQueuePlaybackCurrent(opId, opEpoch, activeEp.id)) {
                return QueuePlaybackOutcome.Obsolete(
                    operationId = opId,
                    endpointId = activeEp.id,
                    requestedTrackCount = finalTracks.size,
                    requestedIndex = finalIndex,
                    epoch = opEpoch,
                    reason = "Sesión invalidada durante la transferencia de cola"
                )
            }

            if (transferRes.isSuccess) {
                val playRes = if (isAppend && _sessionState.value.isPlaying) {
                    Result.success("already_playing")
                } else {
                    client.sendPlaybackCommand("play")
                }

                // Epoch & operation guard after play command
                if (!isQueuePlaybackCurrent(opId, opEpoch, activeEp.id)) {
                    return QueuePlaybackOutcome.Obsolete(
                        operationId = opId,
                        endpointId = activeEp.id,
                        requestedTrackCount = finalTracks.size,
                        requestedIndex = finalIndex,
                        epoch = opEpoch,
                        reason = "Sesión invalidada durante la orden play"
                    )
                }

                if (playRes.isFailure) {
                    val (playFailStatus, playFailMsg) = resolveFailureStatus(client, playRes)
                    val outcome = cmd.copy(
                        status = playFailStatus,
                        epoch = opEpoch,
                        resultMessage = "Cola transferida pero falló orden play: $playFailMsg"
                    )
                    if (isQueuePlaybackCurrent(opId, opEpoch, activeEp.id)) {
                        _sessionState.value = _sessionState.value.copy(lastRemoteCommand = outcome)
                    }
                    return QueuePlaybackOutcome.Failed(
                        operationId = opId,
                        endpointId = activeEp.id,
                        requestedTrackCount = finalTracks.size,
                        requestedIndex = finalIndex,
                        epoch = opEpoch,
                        error = "Cola transferida pero falló orden play en ${activeEp.name}: $playFailMsg"
                    )
                }

                val stRes = client.getPlaybackState()
                val qRes = client.getQueue()

                // Epoch & operation guard after query state
                if (!isQueuePlaybackCurrent(opId, opEpoch, activeEp.id)) {
                    return QueuePlaybackOutcome.Obsolete(
                        operationId = opId,
                        endpointId = activeEp.id,
                        requestedTrackCount = finalTracks.size,
                        requestedIndex = finalIndex,
                        epoch = opEpoch,
                        reason = "Sesión invalidada durante la confirmación de estado"
                    )
                }

                val st = stRes.getOrNull()
                val q = qRes.getOrNull()

                if (st != null && q != null && isQueuePlaybackCurrent(opId, opEpoch, activeEp.id)) {
                    val qTracks = q.tracks.map { it.toDomainTrack() }
                    val current = qTracks.getOrNull(st.queueIndex)
                    _sessionState.value = _sessionState.value.copy(
                        currentTrack = current,
                        isPlaying = st.playing,
                        position = st.effectivePosition,
                        duration = st.effectiveDuration,
                        queue = qTracks,
                        queueIndex = st.queueIndex
                    )
                }

                // Strict verification via RemoteQueueConfirmation (K47-01)
                val verification = RemoteQueueConfirmation.verify(
                    requestedTracks = finalTracks,
                    requestedIndex = finalIndex,
                    remoteQueueTracks = q?.tracks,
                    remotePlaybackState = st
                )
                val isFullyConfirmed = verification.isFullyConfirmed
                val verificationNote = verification.note

                val outcome = if (isFullyConfirmed) {
                    cmd.copy(status = RemoteCommandStatus.CONFIRMED, epoch = opEpoch, resultMessage = verificationNote)
                } else {
                    cmd.copy(
                        status = RemoteCommandStatus.ACCEPTED,
                        epoch = opEpoch,
                        resultMessage = "Cola transferida; $verificationNote"
                    )
                }

                if (isQueuePlaybackCurrent(opId, opEpoch, activeEp.id)) {
                    _sessionState.value = _sessionState.value.copy(lastRemoteCommand = outcome)
                }

                return if (isFullyConfirmed) {
                    QueuePlaybackOutcome.Confirmed(
                        operationId = opId,
                        endpointId = activeEp.id,
                        requestedTrackCount = finalTracks.size,
                        requestedIndex = finalIndex,
                        epoch = opEpoch,
                        message = "Cola completa confirmada en ${activeEp.name}"
                    )
                } else {
                    QueuePlaybackOutcome.Accepted(
                        operationId = opId,
                        endpointId = activeEp.id,
                        requestedTrackCount = finalTracks.size,
                        requestedIndex = finalIndex,
                        epoch = opEpoch,
                        message = "Cola transferida a ${activeEp.name}; $verificationNote"
                    )
                }
            } else {
                // Honest error reporting: do NOT secretly fall back to play_track (K46D-39)
                val (failStatus, failMsg) = resolveFailureStatus(client, transferRes)
                val outcome = cmd.copy(status = failStatus, epoch = opEpoch, resultMessage = failMsg)
                if (isQueuePlaybackCurrent(opId, opEpoch, activeEp.id)) {
                    _sessionState.value = _sessionState.value.copy(lastRemoteCommand = outcome)
                }
                return when (failStatus) {
                    RemoteCommandStatus.DISCONNECTED -> QueuePlaybackOutcome.Disconnected(
                        operationId = opId,
                        endpointId = activeEp.id,
                        requestedTrackCount = finalTracks.size,
                        requestedIndex = finalIndex,
                        epoch = opEpoch,
                        reason = failMsg
                    )
                    RemoteCommandStatus.REJECTED -> QueuePlaybackOutcome.Rejected(
                        operationId = opId,
                        endpointId = activeEp.id,
                        requestedTrackCount = finalTracks.size,
                        requestedIndex = finalIndex,
                        epoch = opEpoch,
                        reason = failMsg
                    )
                    else -> QueuePlaybackOutcome.Failed(
                        operationId = opId,
                        endpointId = activeEp.id,
                        requestedTrackCount = finalTracks.size,
                        requestedIndex = finalIndex,
                        epoch = opEpoch,
                        error = failMsg
                    )
                }
            }
        }

        // Authority 3: Local Phone
        audioController?.playQueue(tracks, validIndex)
        return QueuePlaybackOutcome.Confirmed(
            operationId = opId,
            endpointId = PlaybackEndpoint.LocalPhone.id,
            requestedTrackCount = tracks.size,
            requestedIndex = validIndex,
            epoch = opEpoch,
            message = "Reproduciendo localmente"
        )
    }

    /**
     * Resolves an interactive preflight queue confirmation prompt (K49-03).
     * If CANCEL, dismisses without issuing destructive mutations.
     * If REPLACE or APPEND, executes remote queue transfer with chosen policy.
     */
    fun resolvePendingQueueConfirmation(action: RemoteQueueAction) {
        val conf = _pendingQueueConfirmation.value ?: return
        _pendingQueueConfirmation.value = null
        if (action == RemoteQueueAction.CANCEL) {
            _issuedTickets.remove(conf.ticket.ticketId)
            return
        }
        if (conf.ticket.isExpired) {
            _issuedTickets.remove(conf.ticket.ticketId)
            _sessionState.value = _sessionState.value.copy(
                statusMessage = "La confirmación de cola caducó; por favor, confirma nuevamente."
            )
            return
        }
        if (_sessionEpoch.get() != conf.ticket.sessionEpoch || _sessionState.value.activeEndpoint.id != conf.ticket.endpointId) {
            _issuedTickets.remove(conf.ticket.ticketId)
            _sessionState.value = _sessionState.value.copy(
                statusMessage = "El destino de reproducción cambió durante la confirmación."
            )
            return
        }
        scope.launch {
            requestQueuePlayback(
                tracks = conf.proposedTracks,
                startIndex = conf.proposedStartIndex,
                confirmedAction = action,
                confirmedTicket = conf.ticket,
            )
        }
    }

    fun dismissPendingQueueConfirmation() {
        resolvePendingQueueConfirmation(RemoteQueueAction.CANCEL)
    }

    fun playNext(track: Track) {
        if (isRemotePlaybackHost) {
            val targetEndpoint = _sessionState.value.activeEndpoint
            val opEpoch = _sessionEpoch.get()
            val client = connectionManager.getClient(targetEndpoint.id)
            val cmd = RemoteCommandRecord(
                endpointId = targetEndpoint.id,
                epoch = opEpoch,
                action = "play_next",
                param = track.id,
                status = RemoteCommandStatus.PENDING,
            )
            _sessionState.value = _sessionState.value.copy(lastRemoteCommand = cmd)
            scope.launch {
                val result = client?.sendPlaybackCommand("play_next", track.id)
                if (opEpoch != _sessionEpoch.get() || _sessionState.value.activeEndpoint.id != targetEndpoint.id) return@launch
                val outcome = if (result != null && result.isSuccess) {
                    var queueSynced = false
                    var queueErr = ""
                    client.getQueue().onSuccess { qDto ->
                        if (_sessionState.value.activeEndpoint.id == targetEndpoint.id && opEpoch == _sessionEpoch.get()) {
                            val tracks = qDto.tracks.map { it.toDomainTrack() }
                            _sessionState.value = _sessionState.value.copy(queue = tracks, queueIndex = qDto.currentIndex)
                        }
                        queueSynced = true
                    }.onFailure { err ->
                        queueErr = err.message ?: "Fallo al sincronizar cola remota"
                    }
                    if (queueSynced) {
                        cmd.copy(status = RemoteCommandStatus.CONFIRMED, epoch = opEpoch)
                    } else {
                        cmd.copy(status = RemoteCommandStatus.ACCEPTED, epoch = opEpoch, resultMessage = "Aceptado por el servidor; sincronización de estado pendiente ($queueErr)")
                    }
                } else {
                    val (failStatus, failMsg) = resolveFailureStatus(client, result)
                    cmd.copy(status = failStatus, epoch = opEpoch, resultMessage = failMsg)
                }
                if (_sessionState.value.activeEndpoint.id == targetEndpoint.id && opEpoch == _sessionEpoch.get()) {
                    _sessionState.value = _sessionState.value.copy(lastRemoteCommand = outcome)
                }
            }
        } else {
            audioController?.playNext(track)
        }
    }

    fun addToQueue(track: Track) {
        if (isRemotePlaybackHost) {
            val targetEndpoint = _sessionState.value.activeEndpoint
            val opEpoch = _sessionEpoch.get()
            val client = connectionManager.getClient(targetEndpoint.id)
            val cmd = RemoteCommandRecord(
                endpointId = targetEndpoint.id,
                epoch = opEpoch,
                action = "add_to_queue",
                param = track.id,
                status = RemoteCommandStatus.PENDING,
            )
            _sessionState.value = _sessionState.value.copy(lastRemoteCommand = cmd)
            scope.launch {
                val result = client?.sendPlaybackCommand("add_to_queue", track.id)
                if (opEpoch != _sessionEpoch.get() || _sessionState.value.activeEndpoint.id != targetEndpoint.id) return@launch
                val outcome = if (result != null && result.isSuccess) {
                    var queueSynced = false
                    var queueErr = ""
                    client.getQueue().onSuccess { qDto ->
                        if (_sessionState.value.activeEndpoint.id == targetEndpoint.id && opEpoch == _sessionEpoch.get()) {
                            val tracks = qDto.tracks.map { it.toDomainTrack() }
                            _sessionState.value = _sessionState.value.copy(queue = tracks, queueIndex = qDto.currentIndex)
                        }
                        queueSynced = true
                    }.onFailure { err ->
                        queueErr = err.message ?: "Fallo al sincronizar cola remota"
                    }
                    if (queueSynced) {
                        cmd.copy(status = RemoteCommandStatus.CONFIRMED, epoch = opEpoch)
                    } else {
                        cmd.copy(status = RemoteCommandStatus.ACCEPTED, epoch = opEpoch, resultMessage = "Aceptado por el servidor; sincronización de estado pendiente ($queueErr)")
                    }
                } else {
                    val (failStatus, failMsg) = resolveFailureStatus(client, result)
                    cmd.copy(status = failStatus, epoch = opEpoch, resultMessage = failMsg)
                }
                if (_sessionState.value.activeEndpoint.id == targetEndpoint.id && opEpoch == _sessionEpoch.get()) {
                    _sessionState.value = _sessionState.value.copy(lastRemoteCommand = outcome)
                }
            }
        } else {
            audioController?.addToQueue(track)
        }
    }

    fun moveQueueItem(fromIndex: Int, toIndex: Int) {
        if (isRemotePlaybackHost) {
            val targetEndpoint = _sessionState.value.activeEndpoint
            val opEpoch = _sessionEpoch.get()
            val client = connectionManager.getClient(targetEndpoint.id)
            val cmd = RemoteCommandRecord(
                endpointId = targetEndpoint.id,
                epoch = opEpoch,
                action = "reorder_queue",
                param = "$fromIndex->$toIndex",
                status = RemoteCommandStatus.PENDING,
            )
            _sessionState.value = _sessionState.value.copy(lastRemoteCommand = cmd)
            scope.launch {
                val result = client?.reorderQueue(org.michimusic.link.dto.QueueReorderRequest(fromIndex = fromIndex, toIndex = toIndex))
                if (opEpoch != _sessionEpoch.get() || _sessionState.value.activeEndpoint.id != targetEndpoint.id) return@launch
                val outcome = if (result != null && result.isSuccess) {
                    var queueSynced = false
                    var queueErr = ""
                    client.getQueue().onSuccess { qDto ->
                        if (_sessionState.value.activeEndpoint.id == targetEndpoint.id && opEpoch == _sessionEpoch.get()) {
                            val tracks = qDto.tracks.map { it.toDomainTrack() }
                            _sessionState.value = _sessionState.value.copy(queue = tracks, queueIndex = qDto.currentIndex)
                        }
                        queueSynced = true
                    }.onFailure { err ->
                        queueErr = err.message ?: "Fallo al sincronizar cola remota"
                    }
                    if (queueSynced) {
                        cmd.copy(status = RemoteCommandStatus.CONFIRMED, epoch = opEpoch)
                    } else {
                        cmd.copy(status = RemoteCommandStatus.ACCEPTED, epoch = opEpoch, resultMessage = "Aceptado por el servidor; sincronización de estado pendiente ($queueErr)")
                    }
                } else {
                    val (failStatus, failMsg) = resolveFailureStatus(client, result)
                    cmd.copy(status = failStatus, epoch = opEpoch, resultMessage = failMsg)
                }
                if (_sessionState.value.activeEndpoint.id == targetEndpoint.id && opEpoch == _sessionEpoch.get()) {
                    _sessionState.value = _sessionState.value.copy(lastRemoteCommand = outcome)
                }
            }
        } else {
            audioController?.moveQueueItem(fromIndex, toIndex)
        }
    }

    fun clearQueue() {
        if (isRemotePlaybackHost) {
            val targetEndpoint = _sessionState.value.activeEndpoint
            val opEpoch = _sessionEpoch.get()
            val client = connectionManager.getClient(targetEndpoint.id)
            val cmd = RemoteCommandRecord(
                endpointId = targetEndpoint.id,
                epoch = opEpoch,
                action = "clear_queue",
                status = RemoteCommandStatus.PENDING,
            )
            _sessionState.value = _sessionState.value.copy(lastRemoteCommand = cmd)
            scope.launch {
                val result = client?.sendPlaybackCommand("clear_queue")
                if (opEpoch != _sessionEpoch.get() || _sessionState.value.activeEndpoint.id != targetEndpoint.id) return@launch
                val outcome = if (result != null && result.isSuccess) {
                    if (_sessionState.value.activeEndpoint.id == targetEndpoint.id && opEpoch == _sessionEpoch.get()) {
                        _sessionState.value = _sessionState.value.copy(queue = emptyList(), queueIndex = -1)
                    }
                    cmd.copy(status = RemoteCommandStatus.CONFIRMED, epoch = opEpoch)
                } else {
                    val (failStatus, failMsg) = resolveFailureStatus(client, result)
                    cmd.copy(status = failStatus, epoch = opEpoch, resultMessage = failMsg)
                }
                if (_sessionState.value.activeEndpoint.id == targetEndpoint.id && opEpoch == _sessionEpoch.get()) {
                    _sessionState.value = _sessionState.value.copy(lastRemoteCommand = outcome)
                }
            }
        } else {
            audioController?.clearQueue()
        }
    }

    fun setRepeatMode(mode: Int) {
        if (_sessionState.value.isRemoteActive) {
            val targetEndpoint = _sessionState.value.activeEndpoint
            val opEpoch = _sessionEpoch.get()
            val client = connectionManager.getClient(targetEndpoint.id)
            val repeatStr = when (mode) { 1 -> "one"; 2 -> "all"; else -> "off" }
            val cmd = RemoteCommandRecord(
                endpointId = targetEndpoint.id,
                epoch = opEpoch,
                action = "set_repeat_mode",
                param = repeatStr,
                status = RemoteCommandStatus.PENDING,
            )
            _sessionState.value = _sessionState.value.copy(lastRemoteCommand = cmd)
            scope.launch {
                val result = client?.setQueueRepeatMode(repeatStr)
                if (opEpoch != _sessionEpoch.get() || _sessionState.value.activeEndpoint.id != targetEndpoint.id) return@launch
                val outcome = if (result != null && result.isSuccess) {
                    if (_sessionState.value.activeEndpoint.id == targetEndpoint.id && opEpoch == _sessionEpoch.get()) {
                        _sessionState.value = _sessionState.value.copy(repeatMode = mode)
                    }
                    cmd.copy(status = RemoteCommandStatus.CONFIRMED, epoch = opEpoch)
                } else {
                    val (failStatus, failMsg) = resolveFailureStatus(client, result)
                    cmd.copy(status = failStatus, epoch = opEpoch, resultMessage = failMsg)
                }
                if (_sessionState.value.activeEndpoint.id == targetEndpoint.id && opEpoch == _sessionEpoch.get()) {
                    _sessionState.value = _sessionState.value.copy(lastRemoteCommand = outcome)
                }
            }
        } else {
            _sessionState.value = _sessionState.value.copy(repeatMode = mode)
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
        if (_sessionState.value.isRemoteActive) {
            val targetEndpoint = _sessionState.value.activeEndpoint
            val opEpoch = _sessionEpoch.get()
            val client = connectionManager.getClient(targetEndpoint.id)
            val cmd = RemoteCommandRecord(
                endpointId = targetEndpoint.id,
                epoch = opEpoch,
                action = "set_shuffle_mode",
                param = mode.toString(),
                status = RemoteCommandStatus.PENDING,
            )
            _sessionState.value = _sessionState.value.copy(lastRemoteCommand = cmd)
            scope.launch {
                val result = client?.setQueueShuffle(mode == 1)
                if (opEpoch != _sessionEpoch.get() || _sessionState.value.activeEndpoint.id != targetEndpoint.id) return@launch
                val outcome = if (result != null && result.isSuccess) {
                    if (_sessionState.value.activeEndpoint.id == targetEndpoint.id && opEpoch == _sessionEpoch.get()) {
                        _sessionState.value = _sessionState.value.copy(shuffleMode = mode)
                    }
                    cmd.copy(status = RemoteCommandStatus.CONFIRMED, epoch = opEpoch)
                } else {
                    val (failStatus, failMsg) = resolveFailureStatus(client, result)
                    cmd.copy(status = failStatus, epoch = opEpoch, resultMessage = failMsg)
                }
                if (_sessionState.value.activeEndpoint.id == targetEndpoint.id && opEpoch == _sessionEpoch.get()) {
                    _sessionState.value = _sessionState.value.copy(lastRemoteCommand = outcome)
                }
            }
        } else {
            _sessionState.value = _sessionState.value.copy(shuffleMode = mode)
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
                intent = PlaybackTransitionIntent.FORMAT_FAILURE,
                reason = StreamErrorReason.FORMAT_UNSUPPORTED,
                message = "Esta pista no es compatible con Michi Stream; la reproducción continúa en este teléfono",
                resumePlayback = wasPlaying
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
                    intent = PlaybackTransitionIntent.FORMAT_FAILURE,
                    reason = StreamErrorReason.FORMAT_UNSUPPORTED,
                    message = "Esta pista no es compatible con Michi Stream; la reproducción continúa en este teléfono",
                    resumePlayback = wasPlaying
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
                // Rebind PCM tap to new session token and sender
                org.michimusic.player.PlayerDependencies.startPcmStreaming(
                    listener = { pcmChunk -> rtpAudioSender.sendPcmChunk(pcmChunk) },
                    sessionToken = sessionResp.sessionToken,
                )

                val resumed = org.michimusic.player.PlayerDependencies.resumePcmStreaming(
                    format = org.michimusic.core.models.PcmFormat(
                        sampleRate = sessionResp.effective.sampleRate,
                        channels = sessionResp.effective.channels,
                        bitDepth = sessionResp.effective.bitDepth,
                        codec = sessionResp.effective.codec,
                    ),
                    sessionToken = sessionResp.sessionToken,
                )

                // 4. Resume playback clock now that RTP transmission is running and resumed successfully
                if (resumed && wasPlaying) {
                    audioController?.play()
                } else if (!resumed) {
                    selectLocalEndpoint(
                        intent = PlaybackTransitionIntent.RTP_FAILURE,
                        reason = StreamErrorReason.RTP_START_FAILED,
                        message = "Fallo al reactivar flujo de audio con Michi Stream; reproducción pausada por seguridad",
                        resumePlayback = false
                    )
                    return@onSuccess
                }

                val intervalMs = (sessionResp.leaseSeconds / 3).coerceAtLeast(1) * 1000L
                receiverHeartbeatJob = scope.launch(ioDispatcher) {
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
                                    intent = PlaybackTransitionIntent.SESSION_REVOKED,
                                    reason = StreamErrorReason.SESSION_EXPIRED,
                                    message = "Se perdió la sesión con Michi Stream; reproducción pausada por seguridad",
                                    resumePlayback = false
                                )
                            }
                            break
                        }
                    }
                }
            } else {
                selectLocalEndpoint(
                    intent = PlaybackTransitionIntent.RTP_FAILURE,
                    reason = StreamErrorReason.RTP_START_FAILED,
                    message = "No se pudo iniciar la transmisión de audio; reproducción pausada por seguridad",
                    resumePlayback = false
                )
            }
        }.onFailure {
            selectLocalEndpoint(
                intent = PlaybackTransitionIntent.SESSION_REVOKED,
                reason = StreamErrorReason.RECEIVER_NEGOTIATION_FAILED,
                message = "No se pudo renegociar la sesión con Michi Stream; reproducción pausada por seguridad",
                resumePlayback = false
            )
        }
    }

    fun getRtpMetrics(): org.michimusic.link.rtp.RtpMetrics = rtpAudioSender.getMetrics()
}

private fun org.michimusic.link.dto.QueueTrackDto.toDomainTrack(): Track {
    return Track(
        id = trackId,
        title = title,
        artist = artist,
        album = album,
        duration = duration,
        coverId = coverId,
        source = TrackSource.STREAMING,
    )
}
