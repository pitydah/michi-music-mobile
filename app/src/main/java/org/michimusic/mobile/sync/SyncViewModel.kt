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
    private val creds: SyncCredentialsStore,
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

    private var clientId: String = creds.clientDeviceId
    private var isReconnecting = false

    init {
        if (creds.hasSavedSession) {
            reconnectSaved()
        }
    }

    private fun reconnectSaved() {
        isReconnecting = true
        session.updateState(SyncConnectionState.CONNECTING)
        val baseUrl = creds.lastBaseUrl
        val client = MichiSyncClient(baseUrl = baseUrl)
        client.sessionToken = creds.sessionToken
        client.deviceId = clientId

        viewModelScope.launch {
            client.ping().let { alive ->
                if (alive) {
                    val peer = DiscoveredPeer(
                        alias = creds.serverAlias,
                        ip = baseUrl.removePrefix("http://").substringBeforeLast(":"),
                        port = baseUrl.substringAfterLast(":").toIntOrNull() ?: 53318,
                    )
                    session.onConnected(peer, client)
                    session.onRegistered(
                        RegisterResponse(
                            sessionToken = creds.sessionToken,
                            serverDeviceId = creds.serverDeviceId,
                            clientDeviceId = clientId,
                            librarySize = 0,
                        )
                    )
                } else {
                    creds.clear()
                    session.updateState(SyncConnectionState.DISCONNECTED)
                }
            }
            isReconnecting = false
        }
    }

    fun startDiscovery() {
        if (uiState.value.state != SyncConnectionState.DISCONNECTED) return
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
                session.onConnected(peer, client)
                session.updateState(SyncConnectionState.PAIRING_REQUIRED)
                _error.value = "Este servidor requiere emparejamiento."
                return@launch
            }

            client
                .registerLegacy(
                    alias = android.os.Build.MODEL,
                    deviceModel = android.os.Build.MODEL,
                    clientDeviceId = clientId,
                )
                .onSuccess { response ->
                    creds.saveFromSession(baseUrl, response.sessionToken, response.serverDeviceId, peer.alias)
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

    fun pairWithServer(peer: DiscoveredPeer, username: String, password: String) {
        if (uiState.value.state != SyncConnectionState.PAIRING_REQUIRED) return
        session.updateState(SyncConnectionState.PAIRING)
        val baseUrl = "http://${peer.ip}:${peer.port}"
        val client = MichiSyncClient(baseUrl = baseUrl)
        client.deviceId = clientId

        viewModelScope.launch {
            val startRequest = org.michimusic.core.models.PairStartRequest(
                username = username,
                password = password,
                deviceAlias = android.os.Build.MODEL,
                deviceType = "android",
                clientDeviceId = clientId,
            )
            client.pairStart(startRequest)
                .onSuccess { pairResp ->
                    val confirmRequest = org.michimusic.core.models.PairConfirmRequest(
                        pairingCode = pairResp.pairingCode,
                        clientDeviceId = clientId,
                    )
                    client.pairConfirm(confirmRequest)
                        .onSuccess { confirmResp ->
                            creds.saveFromSession(
                                baseUrl,
                                confirmResp.sessionToken,
                                confirmResp.serverDeviceId,
                                peer.alias,
                            )
                            val reg = RegisterResponse(
                                sessionToken = confirmResp.sessionToken,
                                serverDeviceId = confirmResp.serverDeviceId,
                                clientDeviceId = confirmResp.clientDeviceId,
                                librarySize = confirmResp.librarySize,
                                version = confirmResp.version,
                            )
                            session.onConnected(peer, client)
                            session.onRegistered(reg)
                        }
                        .onFailure { e ->
                            _error.value = "Error al confirmar emparejamiento: ${e.message}"
                            session.updateState(SyncConnectionState.PAIRING_REQUIRED)
                            client.close()
                        }
                }
                .onFailure { e ->
                    _error.value = "Error al iniciar emparejamiento: ${e.message}"
                    session.updateState(SyncConnectionState.PAIRING_REQUIRED)
                    client.close()
                }
        }
    }

    fun disconnect() {
        viewModelScope.launch { discoveryClient.stop() }
        creds.clear()
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
