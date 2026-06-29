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
import org.michimusic.core.models.PairConfirmResponse
import org.michimusic.core.models.PairStartResponse
import org.michimusic.core.models.RegisterResponse
import org.michimusic.core.models.SyncConnectionState
import org.michimusic.data.repository.SyncedTrackRepository
import org.michimusic.sync.DiscoveryClient
import org.michimusic.sync.DiscoveryEvent
import org.michimusic.sync.MichiSyncClient
import org.michimusic.sync.PairingException
import org.michimusic.sync.SecureTokenStore
import org.michimusic.sync.SyncSession

data class SyncUiState(
    val state: SyncConnectionState = SyncConnectionState.DISCONNECTED,
    val peers: List<DiscoveredPeer> = emptyList(),
    val connectedPeer: DiscoveredPeer? = null,
    val registration: RegisterResponse? = null,
    val pairingStart: PairStartResponse? = null,
    val pairingConfirm: PairConfirmResponse? = null,
    val error: String? = null,
    val syncProgress: SyncProgress = SyncProgress.Idle,
)

class SyncViewModel(
    private val context: Context,
    private val discoveryClient: DiscoveryClient,
    private val session: SyncSession,
    private val trackRepository: SyncedTrackRepository,
) : ViewModel() {

    private val tokenStore = SecureTokenStore(context)

    private val _error = MutableStateFlow<String?>(null)
    private val _syncProgress = MutableStateFlow<SyncProgress>(SyncProgress.Idle)

    val uiState: StateFlow<SyncUiState> = combine(
        combine(
            discoveryClient.peers.map { it.values.toList() },
            session.connectionState,
        ) { peers, connState ->
            peers to connState
        },
        combine(
            session.connectedPeer,
            session.registration,
        ) { peer, reg ->
            peer to reg
        },
        combine(
            session.pairStartResponse,
            session.pairConfirmResponse,
        ) { pairStart, pairConfirm ->
            pairStart to pairConfirm
        },
    ) { (peers, connState), (peer, reg), (pairStart, pairConfirm) ->
        SyncUiState(
            state = connState,
            peers = peers,
            connectedPeer = peer,
            registration = reg,
            pairingStart = pairStart,
            pairingConfirm = pairConfirm,
        )
    }.combine(
        combine(_error, _syncProgress) { err, progress -> err to progress }
    ) { state, (err, progress) ->
        state.copy(error = err, syncProgress = progress)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, SyncUiState())

    private var clientId: String = ""
    private var pendingPairingId: String = ""

    init {
        clientId = "android_${android.provider.Settings.Secure.getString(
            context.contentResolver,
            android.provider.Settings.Secure.ANDROID_ID,
        )?.takeLast(6) ?: "000000"}"
    }

    fun startDiscovery() {
        if (uiState.value.state != SyncConnectionState.DISCONNECTED) return
        session.updateState(SyncConnectionState.DISCOVERING)
        viewModelScope.launch { discoveryClient.start() }
    }

    fun stopDiscovery() {
        viewModelScope.launch {
            discoveryClient.stop()
            if (uiState.value.state == SyncConnectionState.DISCOVERING) {
                session.updateState(SyncConnectionState.DISCONNECTED)
            }
        }
    }

    fun selectPeer(peer: DiscoveredPeer) {
        if (uiState.value.state != SyncConnectionState.DISCOVERING) return

        val storedToken = tokenStore.getDeviceToken()
        val storedServerId = tokenStore.getServerDeviceId()

        if (storedToken != null && storedServerId == peer.deviceId) {
            connectWithToken(peer, storedToken)
        } else if (peer.authRequired) {
            session.updateState(SyncConnectionState.PAIRING_REQUIRED)
            session.onConnected(peer, MichiSyncClient(baseUrl = "http://${peer.ip}:${peer.port}"))
        } else {
            connectToPeerLegacy(peer)
        }
    }

    private fun connectWithToken(peer: DiscoveredPeer, token: String) {
        session.updateState(SyncConnectionState.CONNECTING)
        val baseUrl = "http://${peer.ip}:${peer.port}"
        val client = MichiSyncClient(baseUrl = baseUrl, deviceToken = token)

        viewModelScope.launch {
            val pingOk = client.ping()
            if (pingOk) {
                session.onConnected(peer, client)
                session.updateState(SyncConnectionState.PAIRED)
            } else {
                tokenStore.clear()
                selectPeer(peer)
            }
        }
    }

    private fun connectToPeerLegacy(peer: DiscoveredPeer) {
        if (uiState.value.state != SyncConnectionState.DISCOVERING &&
            uiState.value.state != SyncConnectionState.PAIRING_REQUIRED) return
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

    fun startPairing(peer: DiscoveredPeer, username: String, password: String) {
        val client = session.syncClient ?: return
        session.updateState(SyncConnectionState.PAIRING)

        viewModelScope.launch {
            client.pairStart(
                alias = android.os.Build.MODEL,
                deviceModel = android.os.Build.MODEL,
                clientDeviceId = clientId,
            ).onSuccess { startResp ->
                pendingPairingId = startResp.pairingId
                session.onPairingStarted(startResp)

                client.pairConfirm(
                    pairingId = startResp.pairingId,
                    username = username,
                    password = password,
                    clientDeviceId = clientId,
                ).onSuccess { confirmResp ->
                    tokenStore.saveToken(
                        serverDeviceId = confirmResp.serverDeviceId,
                        serverAlias = confirmResp.serverAlias,
                        clientDeviceId = clientId,
                        deviceToken = confirmResp.deviceToken,
                        refreshToken = confirmResp.refreshToken,
                        permissions = confirmResp.permissions,
                    )
                    client.deviceToken = confirmResp.deviceToken
                    session.onPaired(confirmResp)
                    session.updateState(SyncConnectionState.PAIRED)
                }.onFailure { e ->
                    when (e) {
                        is PairingException.InvalidCredentials -> {
                            _error.value = "Credenciales incorrectas"
                            session.updateState(SyncConnectionState.PAIRING_REQUIRED)
                        }
                        is PairingException.Revoked -> {
                            _error.value = "Acceso denegado por el servidor"
                            session.updateState(SyncConnectionState.REVOKED)
                        }
                        else -> {
                            _error.value = "Error de emparejamiento: ${e.message}"
                            session.updateState(SyncConnectionState.ERROR)
                        }
                    }
                }
            }.onFailure { e ->
                _error.value = "Error al iniciar emparejamiento: ${e.message}"
                session.updateState(SyncConnectionState.ERROR)
            }
        }
    }

    fun forgetServer() {
        tokenStore.clear()
        disconnect()
    }

    fun disconnect() {
        viewModelScope.launch { discoveryClient.stop() }
        session.disconnect()
        _syncProgress.value = SyncProgress.Idle
    }

    fun syncLibrary() {
        val client = session.syncClient ?: return
        val currentState = uiState.value
        val reg = currentState.registration
        val pairConfirm = currentState.pairingConfirm

        val deviceId = reg?.clientDeviceId
            ?: pairConfirm?.deviceId
            ?: clientId
        val effectiveToken = client.deviceToken.ifEmpty { client.sessionToken }

        _syncProgress.value = SyncProgress.Downloading(0, 0)

        val inputData = SyncWorker.buildInputData(
            baseUrl = client.baseUrl,
            sessionToken = client.sessionToken,
            deviceId = deviceId,
            alias = android.os.Build.MODEL,
            deviceToken = effectiveToken,
        )

        val workRequest = OneTimeWorkRequestBuilder<SyncWorker>()
            .setInputData(inputData)
            .build()

        WorkManager.getInstance(context)
            .enqueueUniqueWork("michi_sync", ExistingWorkPolicy.REPLACE, workRequest)

        viewModelScope.launch {
            WorkManager.getInstance(context)
                .getWorkInfoByIdFlow(workRequest.id)
                .collect { info ->
                    val progress = info.progress
                    val total = progress.getInt(SyncWorker.PROGRESS_TOTAL, 0)
                    val current = progress.getInt(SyncWorker.PROGRESS_CURRENT, 0)
                    if (info.state.isFinished) {
                        val downloaded = info.outputData.getInt(SyncWorker.RESULT_DOWNLOADED, 0)
                        val errors = info.outputData.getInt(SyncWorker.RESULT_ERROR, 0)
                        if (downloaded > 0 || errors == 0) {
                            _syncProgress.value = SyncProgress.Complete(
                                tracks = trackRepository.count(),
                                downloaded = downloaded,
                                errors = errors,
                            )
                        } else {
                            _syncProgress.value = SyncProgress.Error("Error en sincronización")
                        }
                    } else {
                        _syncProgress.value = SyncProgress.Downloading(current, total)
                    }
                }
        }
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
        val errors: Int = 0,
    ) : SyncProgress()
    data class Error(val message: String) : SyncProgress()
}
