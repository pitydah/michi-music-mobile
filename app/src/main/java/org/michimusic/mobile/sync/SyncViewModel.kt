package org.michimusic.mobile.sync

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.michimusic.core.models.DiscoveredPeer
import org.michimusic.core.models.RegisterResponse
import org.michimusic.core.models.SyncConnectionState
import org.michimusic.data.repository.SyncedTrackRepository
import org.michimusic.sync.DiscoveryClient
import org.michimusic.sync.DiscoveryEvent
import org.michimusic.sync.MichiSyncClient
import org.michimusic.sync.SyncSession

data class SyncUiState(
    val state: SyncConnectionState = SyncConnectionState.DISCONNECTED,
    val peers: List<DiscoveredPeer> = emptyList(),
    val connectedPeer: DiscoveredPeer? = null,
    val registration: RegisterResponse? = null,
    val error: String? = null,
    val syncProgress: SyncProgress = SyncProgress.Idle,
)

class SyncViewModel(
    private val context: Context,
    private val discoveryClient: DiscoveryClient,
    private val session: SyncSession,
    private val trackRepository: SyncedTrackRepository,
) : ViewModel() {

    private val _error = MutableStateFlow<String?>(null)
    private val _syncProgress = MutableStateFlow<SyncProgress>(SyncProgress.Idle)

    val uiState: StateFlow<SyncUiState> = combine(
        combine(
            discoveryClient.peers.map { it.values.toList() },
            session.connectionState,
            session.connectedPeer,
            session.registration,
        ) { peers, connState, peer, reg ->
            SyncUiState(
                state = connState,
                peers = peers,
                connectedPeer = peer,
                registration = reg,
            )
        },
        _error,
        _syncProgress,
    ) { state, err, progress ->
        state.copy(error = err, syncProgress = progress)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, SyncUiState())

    private var clientId: String = ""

    fun startDiscovery() {
        if (uiState.value.state != SyncConnectionState.DISCONNECTED) return
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
            if (uiState.value.state == SyncConnectionState.DISCOVERING) {
                session.updateState(SyncConnectionState.DISCONNECTED)
            }
        }
    }

    fun connectToPeer(peer: DiscoveredPeer) {
        if (uiState.value.state != SyncConnectionState.DISCOVERING) return
        session.updateState(SyncConnectionState.CONNECTING)
        val baseUrl = "http://${peer.ip}:${peer.port}"
        val client = MichiSyncClient(baseUrl = baseUrl)
        client.deviceId = clientId

        viewModelScope.launch {
            if (peer.authRequired) {
                session.updateState(SyncConnectionState.PAIRING_REQUIRED)
                _error.value = "Este servidor requiere emparejamiento. Función en preparación."
                client.close()
                return@launch
            }

            client
                .registerLegacy(
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
        val reg = session.registration.value ?: return
        _syncProgress.value = SyncProgress.Downloading(0, 0)

        val inputData = SyncWorker.buildInputData(
            baseUrl = client.baseUrl,
            sessionToken = client.sessionToken,
            deviceId = reg.clientDeviceId,
            alias = android.os.Build.MODEL,
        )

        val workRequest = OneTimeWorkRequestBuilder<SyncWorker>()
            .setInputData(inputData)
            .build()

        WorkManager.getInstance(context)
            .enqueueUniqueWork("michi_sync", ExistingWorkPolicy.REPLACE, workRequest)

        _syncProgress.value = SyncProgress.Downloading(0, 1)
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
