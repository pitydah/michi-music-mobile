package org.michimusic.mobile.sync

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.michimusic.core.models.DiscoveredPeer
import org.michimusic.core.models.RegisterResponse
import org.michimusic.core.models.SyncConnectionState
import org.michimusic.data.cache.CachedTrack
import org.michimusic.data.repository.SyncedTrackRepository
import org.michimusic.sync.CoverCache
import org.michimusic.sync.DiscoveryClient
import org.michimusic.sync.DiscoveryEvent
import org.michimusic.sync.MichiSyncClient
import org.michimusic.sync.SyncSession
import org.michimusic.sync.SyncTransferManager

class SyncViewModel(
    private val discoveryClient: DiscoveryClient,
    private val session: SyncSession,
    private val trackRepository: SyncedTrackRepository,
    private val transferManager: SyncTransferManager,
    private val coverCache: CoverCache,
) : ViewModel() {

    val peers: StateFlow<List<DiscoveredPeer>> = discoveryClient.peers
        .map { it.values.toList() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val connectionState: StateFlow<SyncConnectionState> = session.connectionState
    val connectedPeer: StateFlow<DiscoveredPeer?> = session.connectedPeer
    val registration: StateFlow<RegisterResponse?> = session.registration
    val downloads = transferManager.downloads

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _syncProgress = MutableStateFlow<SyncProgress>(SyncProgress.Idle)
    val syncProgress: StateFlow<SyncProgress> = _syncProgress.asStateFlow()

    private var clientId: String = ""

    fun startDiscovery() {
        if (connectionState.value != SyncConnectionState.DISCONNECTED) return
        clientId = "android_${System.currentTimeMillis().toString().takeLast(6)}"
        session.updateState(SyncConnectionState.DISCOVERING)
        viewModelScope.launch { discoveryClient.start() }
        viewModelScope.launch {
            discoveryClient.events.collect { event ->
                if (event is DiscoveryEvent.PeerFound) {
                    stopDiscovery()
                    connectToPeer(event.peer)
                }
            }
        }
    }

    fun stopDiscovery() {
        viewModelScope.launch {
            discoveryClient.stop()
            if (connectionState.value == SyncConnectionState.DISCOVERING) {
                session.updateState(SyncConnectionState.DISCONNECTED)
            }
        }
    }

    fun connectToPeer(peer: DiscoveredPeer) {
        if (connectionState.value != SyncConnectionState.DISCOVERING) return
        session.updateState(SyncConnectionState.CONNECTING)
        val baseUrl = "http://${peer.ip}:${peer.port}"
        val client = MichiSyncClient(baseUrl = baseUrl)

        viewModelScope.launch {
            client
                .register(
                    alias = android.os.Build.MODEL,
                    deviceModel = android.os.Build.MODEL,
                    clientDeviceId = clientId,
                )
                .onSuccess { response ->
                    session.onConnected(peer, client)
                    session.onRegistered(response)
                }
                .onFailure { e ->
                    _error.value = "Error al conectar: ${e.message}"
                    session.updateState(SyncConnectionState.ERROR)
                    client.close()
                }
        }
    }

    fun disconnect() {
        viewModelScope.launch { discoveryClient.stop() }
        session.disconnect()
        _syncProgress.value = SyncProgress.Idle
    }

    fun syncLibrary() {
        val client = session.syncClient ?: return
        _syncProgress.value = SyncProgress.Downloading(0, 0)

        viewModelScope.launch {
            client
                .fetchLibrary()
                .onSuccess { library ->
                    trackRepository.saveLibrary(library.tracks)
                    val deviceId = registration.value?.clientDeviceId ?: ""
                    val manifest = if (deviceId.isNotEmpty()) {
                        client.fetchManifest(deviceId).getOrNull()
                    } else null

                    val tracksToDownload = if (manifest != null && manifest.tracks.isNotEmpty()) {
                        manifest.tracks.mapNotNull { manifestTrack ->
                            val cached = trackRepository.getById(manifestTrack.trackId)
                            if (cached == null || !cached.downloaded) {
                                manifestTrack.trackId to manifestTrack.title
                            } else null
                        }
                    } else {
                        library.tracks.map { it.id to it.title }
                    }

                    val total = tracksToDownload.size
                    _syncProgress.value = SyncProgress.Downloading(0, total)
                    if (tracksToDownload.isNotEmpty()) {
                        syncTracks(client, tracksToDownload)
                    } else {
                        _syncProgress.value = SyncProgress.Complete(tracks = library.tracks.size, downloaded = 0)
                    }
                }
                .onFailure { e ->
                    _error.value = "Error al sincronizar: ${e.message}"
                    _syncProgress.value = SyncProgress.Idle
                }
        }
    }

    private suspend fun syncTracks(
        client: MichiSyncClient,
        trackIds: List<Pair<String, String>>,
    ) {
        val total = trackIds.size

        val results = transferManager.downloadTracks(client, trackIds) { completed, _ ->
            _syncProgress.value = SyncProgress.Downloading(completed, total)
        }

        val downloaded = results.count { it.value.isSuccess }

        results.forEach { (id, result) ->
            result.onSuccess { file ->
                trackRepository.markDownloadedWithPath(id, file.absolutePath)
            }
        }

        _syncProgress.value = SyncProgress.Complete(
            tracks = total,
            downloaded = downloaded,
        )
    }

    fun clearError() { _error.value = null }

    override fun onCleared() {
        super.onCleared()
        session.disconnect()
    }
}

sealed class SyncProgress {
    data object Idle : SyncProgress()
    data class Downloading(val completed: Int, val total: Int) : SyncProgress()
    data class Complete(
        val tracks: Int,
        val downloaded: Int,
    ) : SyncProgress()
    data class Error(val message: String) : SyncProgress()
}
