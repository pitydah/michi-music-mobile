package org.michimusic.mobile.sync

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.BackoffPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.michimusic.core.models.DiscoveredPeer
import org.michimusic.core.models.SyncConnectionState
import org.michimusic.data.repository.SyncedTrackRepository
import org.michimusic.link.LinkClient
import org.michimusic.link.LinkDiscovery
import org.michimusic.link.LinkSession
import org.michimusic.link.TokenStore
import org.michimusic.link.dto.PairConfirmResponseDto
import org.michimusic.link.dto.PairStartResponseDto
import org.michimusic.link.dto.PairingStrategy
import org.michimusic.link.dto.QrClaimRequest
import org.michimusic.link.dto.ServerInfoDto
import org.michimusic.link.errors.LinkException

data class SyncUiState(
    val state: SyncConnectionState = SyncConnectionState.DISCONNECTED,
    val peers: List<DiscoveredPeer> = emptyList(),
    val connectedPeer: DiscoveredPeer? = null,
    val pairingStart: PairStartResponseDto? = null,
    val pairingConfirm: PairConfirmResponseDto? = null,
    val pairingStrategy: PairingStrategy = PairingStrategy.LEGACY,
    val error: String? = null,
    val syncProgress: SyncProgress = SyncProgress.Idle,
)

class SyncViewModel(
    private val context: Context,
    private val linkDiscovery: LinkDiscovery,
    private val linkSession: LinkSession,
    private val trackRepository: SyncedTrackRepository,
) : ViewModel() {

    private val tokenStore = TokenStore(context)

    private val _error = MutableStateFlow<String?>(null)
    private val _syncProgress = MutableStateFlow<SyncProgress>(SyncProgress.Idle)

    private val _pairingStrategy = MutableStateFlow(PairingStrategy.LEGACY)

    val uiState: StateFlow<SyncUiState> = combine(
        combine(
            linkDiscovery.peers.map { it.values.toList() },
            linkSession.connectionState,
        ) { peers, connState ->
            peers to connState
        },
        combine(
            linkSession.connectedPeer,
            linkSession.pairStartResponse,
            _pairingStrategy,
        ) { peer, pairStart, strategy ->
            Triple(peer, pairStart, strategy)
        },
    ) { (peers, connState), (peer, pairStart, strategy) ->
        SyncUiState(
            state = connState,
            peers = peers,
            connectedPeer = peer,
            pairingStart = pairStart,
            pairingStrategy = strategy,
        )
    }.combine(
        combine(_error, _syncProgress) { err, progress -> err to progress }
    ) { state, (err, progress) ->
        state.copy(error = err, syncProgress = progress)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SyncUiState(state = linkSession.connectionState.value))

    private var clientId: String = ""
    private var pendingPairingId: String = ""
    private var currentClient: LinkClient? = null

    init {
        clientId = try {
            "android_${android.provider.Settings.Secure.getString(
                context.contentResolver,
                android.provider.Settings.Secure.ANDROID_ID,
            )?.takeLast(6) ?: "000000"}"
        } catch (_: Exception) {
            "android_000000"
        }
    }

    fun startDiscovery() {
        if (linkSession.connectionState.value != SyncConnectionState.DISCONNECTED) return
        linkSession.updateState(SyncConnectionState.DISCOVERING)
        viewModelScope.launch { linkDiscovery.start() }
    }

    fun stopDiscovery() {
        viewModelScope.launch {
            linkDiscovery.stop()
            if (linkSession.connectionState.value == SyncConnectionState.DISCOVERING) {
                linkSession.updateState(SyncConnectionState.DISCONNECTED)
            }
        }
    }

    fun connectManual(name: String, host: String, port: Int = 53318) {
        val parsedHost = if (host.contains(":")) host.substringBefore(":") else host
        val parsedPort = if (host.contains(":")) host.substringAfter(":").toIntOrNull() ?: port else port
        val peer = DiscoveredPeer(
            alias = name.ifBlank { "Michi Node" },
            ip = parsedHost.trim(),
            port = parsedPort,
        )
        linkSession.updateState(SyncConnectionState.DISCOVERING)
        selectPeer(peer)
    }

    fun selectPeer(peer: DiscoveredPeer) {
        if (linkSession.connectionState.value != SyncConnectionState.DISCOVERING) return

        stopDiscovery()
        currentClient = LinkClient(
            baseUrl = "http://${peer.ip}:${peer.port}",
            clientDeviceId = clientId,
        )

        viewModelScope.launch {
            val client = currentClient ?: return@launch
            val resolvedDeviceId = resolveServerDeviceId(client, peer)

            // Resolve pairing strategy from server info
            val serverInfo = client.getServerInfo().getOrNull()
            val strategy = serverInfo?.effectiveAuthStrategy ?: PairingStrategy.LEGACY
            _pairingStrategy.value = strategy
            if (strategy == PairingStrategy.RECEIVER_BUTTON) {
                _error.value = "Este dispositivo no es una fuente controlable"
                linkSession.updateState(SyncConnectionState.DISCONNECTED)
                return@launch
            }

            val storedToken = tokenStore.getDeviceToken()
            val storedServerId = tokenStore.getServerDeviceId()
            val storedClientId = tokenStore.getClientDeviceId()

            if (storedToken != null && storedServerId == resolvedDeviceId) {
                connectWithToken(peer, storedToken, storedClientId ?: clientId)
            } else if (peer.authRequired) {
                linkSession.onConnected(peer, client)
                linkSession.updateState(SyncConnectionState.PAIRING_REQUIRED)
                // store strategy for UI
            } else {
                connectToPeerLegacy(peer)
            }
        }
    }

    private suspend fun resolveServerDeviceId(client: LinkClient, peer: DiscoveredPeer): String {
        if (peer.deviceId.isNotEmpty()) return peer.deviceId
        return runCatching {
            client.getServerInfoWithFallback().getOrNull()?.serverDeviceId
        }.getOrNull() ?: ""
    }

    private fun connectWithToken(peer: DiscoveredPeer, token: String, storedClientId: String) {
        linkSession.updateState(SyncConnectionState.CONNECTING)
        val baseUrl = "http://${peer.ip}:${peer.port}"
        val client = LinkClient(
            baseUrl = baseUrl,
            deviceToken = token,
            clientDeviceId = storedClientId,
        )
        currentClient = client

        viewModelScope.launch {
            val pingOk = client.ping()
            if (pingOk) {
                linkSession.onConnected(peer, client)
                linkSession.updateState(SyncConnectionState.PAIRED)
            } else {
                tokenStore.clear()
                linkSession.updateState(SyncConnectionState.DISCONNECTED)
                _error.value = "No se pudo conectar con el servidor. Vuelve a emparejar."
            }
        }
    }

    private fun connectToPeerLegacy(peer: DiscoveredPeer) {
        linkSession.updateState(SyncConnectionState.CONNECTING)
        val baseUrl = "http://${peer.ip}:${peer.port}"
        val client = LinkClient(baseUrl = baseUrl, clientDeviceId = clientId)
        currentClient = client

        viewModelScope.launch {
            client.register(
                alias = android.os.Build.MODEL,
                deviceModel = android.os.Build.MODEL,
                clientDeviceId = clientId,
            ).onSuccess { response ->
                linkSession.onConnected(peer, client)
            }.onFailure { e ->
                _error.value = "Error al conectar: ${e.message}"
                linkSession.updateState(SyncConnectionState.ERROR)
                client.close()
            }
        }
    }

    fun startPairing(peer: DiscoveredPeer, username: String, password: String = "", pin: String = "") {
        val client = currentClient ?: return
        val strategy = _pairingStrategy.value
        linkSession.updateState(SyncConnectionState.PAIRING)

        if (strategy == PairingStrategy.SERVER_CODE) {
            startCodePairing(client, pin)
            return
        }

        viewModelScope.launch {
            client.pairStart(
                alias = android.os.Build.MODEL,
                deviceModel = android.os.Build.MODEL,
                clientDeviceId = clientId,
            ).onSuccess { startResp ->
                pendingPairingId = startResp.pairingId
                linkSession.onPairingStarted(startResp)

                client.pairConfirm(
                    pairingId = startResp.pairingId,
                    username = username,
                    password = password,
                    clientDeviceId = clientId,
                    alias = android.os.Build.MODEL,
                    deviceModel = android.os.Build.MODEL,
                ).onSuccess { confirmResp ->
                    val effectiveToken = confirmResp.deviceToken.ifEmpty { confirmResp.sessionToken }
                    if (effectiveToken.isBlank()) {
                        _error.value = "El servidor no otorgó un token válido"
                        linkSession.updateState(SyncConnectionState.PAIRING_REQUIRED)
                        return@launch
                    }
                    val resolvedDeviceId = confirmResp.serverDeviceId.ifEmpty {
                        client.getServerInfoWithFallback().getOrNull()?.serverDeviceId.orEmpty()
                    }
                    val serverInfo = client.getServerInfo().getOrNull()
                    tokenStore.save(
                        serverId = resolvedDeviceId,
                        serverName = confirmResp.serverAlias,
                        service = serverInfo?.service ?: "",
                        serverDeviceId = resolvedDeviceId,
                        serverAlias = confirmResp.serverAlias,
                        clientDeviceId = clientId,
                        deviceToken = effectiveToken,
                        refreshToken = confirmResp.refreshToken,
                        permissions = confirmResp.permissions,
                        serverUrl = client.baseUrl,
                        roles = serverInfo?.roles ?: emptyList(),
                        features = emptyList(),
                        authStrategy = serverInfo?.effectiveAuthStrategy ?: PairingStrategy.LEGACY,
                        tokenRefreshSupported = client.tokenRefreshSupported ?: false,
                    )
                    client.deviceToken = effectiveToken
                    client.clientDeviceId = clientId
                    linkSession.onPaired(confirmResp)
                }.onFailure { e ->
                    handlePairingFailure(e)
                }
            }.onFailure { e ->
                _error.value = "Error al iniciar emparejamiento: ${e.message}"
                linkSession.updateState(SyncConnectionState.ERROR)
            }
        }
    }

    private fun startCodePairing(client: LinkClient, pin: String) {
        viewModelScope.launch {
            client.pairStart(
                alias = android.os.Build.MODEL,
                deviceModel = android.os.Build.MODEL,
                clientDeviceId = clientId,
            ).onSuccess { startResp ->
                linkSession.onPairingStarted(startResp)
                client.pairConfirm(
                    pairingId = startResp.pairingId,
                    username = "",
                    password = "",
                    pin = pin,
                    clientDeviceId = clientId,
                    alias = android.os.Build.MODEL,
                    deviceModel = android.os.Build.MODEL,
                ).onSuccess { confirmResp ->
                    val effectiveToken = confirmResp.deviceToken.ifEmpty { confirmResp.sessionToken }
                    if (effectiveToken.isBlank()) {
                        _error.value = "El servidor no otorgó un token válido"
                        linkSession.updateState(SyncConnectionState.PAIRING_REQUIRED)
                        return@launch
                    }
                    val resolvedDeviceId = confirmResp.serverDeviceId.ifEmpty {
                        client.getServerInfoWithFallback().getOrNull()?.serverDeviceId.orEmpty()
                    }
                    val serverInfo = client.getServerInfo().getOrNull()
                    tokenStore.save(
                        serverId = resolvedDeviceId,
                        serverName = confirmResp.serverAlias,
                        service = serverInfo?.service ?: "",
                        serverDeviceId = resolvedDeviceId,
                        serverAlias = confirmResp.serverAlias,
                        clientDeviceId = clientId,
                        deviceToken = effectiveToken,
                        refreshToken = confirmResp.refreshToken,
                        permissions = confirmResp.permissions,
                        serverUrl = client.baseUrl,
                        roles = serverInfo?.roles ?: emptyList(),
                        features = emptyList(),
                        authStrategy = serverInfo?.effectiveAuthStrategy ?: PairingStrategy.LEGACY,
                        tokenRefreshSupported = client.tokenRefreshSupported ?: false,
                    )
                    client.deviceToken = effectiveToken
                    client.clientDeviceId = clientId
                    linkSession.onPaired(confirmResp)
                }.onFailure { handlePairingFailure(it) }
            }.onFailure { e ->
                _error.value = "Error al iniciar emparejamiento: ${e.message}"
                linkSession.updateState(SyncConnectionState.ERROR)
            }
        }
    }

    private fun handlePairingFailure(e: Throwable) {
        when (e) {
            is LinkException.InvalidCredentials -> {
                _error.value = "Credenciales o código incorrectos"
                linkSession.updateState(SyncConnectionState.PAIRING_REQUIRED)
            }
            is LinkException.Revoked -> {
                _error.value = "Acceso denegado por el servidor"
                linkSession.updateState(SyncConnectionState.REVOKED)
            }
            else -> {
                _error.value = "Error de emparejamiento: ${e.message}"
                linkSession.updateState(SyncConnectionState.ERROR)
            }
        }
    }

    fun forgetServer() {
        tokenStore.clear()
        disconnect()
    }

    fun disconnect() {
        viewModelScope.launch { linkDiscovery.stop() }
        currentClient?.close()
        currentClient = null
        linkSession.disconnect()
        _syncProgress.value = SyncProgress.Idle
    }

    fun syncLibrary() {
        val client = currentClient ?: return
        val currentState = uiState.value
        val pairConfirm = currentState.pairingConfirm

        val deviceId = pairConfirm?.deviceId ?: clientId
        val effectiveToken = client.deviceToken.ifEmpty { client.sessionToken }

        _syncProgress.value = SyncProgress.Downloading(0, 0)

        val inputData = SyncWorker.buildInputData(
            baseUrl = client.baseUrl,
            sessionToken = client.sessionToken,
            deviceId = deviceId,
            alias = android.os.Build.MODEL,
            deviceToken = effectiveToken,
            clientDeviceId = client.clientDeviceId.ifEmpty { clientId },
        )

        val workRequest = OneTimeWorkRequestBuilder<SyncWorker>()
            .setInputData(inputData)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()

        WorkManager.getInstance(context)
            .enqueueUniqueWork("michi_sync", ExistingWorkPolicy.REPLACE, workRequest)

        viewModelScope.launch {
            WorkManager.getInstance(context)
                .getWorkInfoByIdFlow(workRequest.id)
                .collect { info ->
                    if (info == null) return@collect
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

    fun pairWithQr(qrContent: String, onResult: (Boolean, String) -> Unit) {
        if (qrContent.isBlank()) {
            onResult(false, "El código QR está vacío")
            return
        }

        viewModelScope.launch {
            try {
                var host: String? = null
                var port = 53318
                var qrCode: String = qrContent.trim()

                if (qrContent.startsWith("michi://") || qrContent.startsWith("http://") || qrContent.startsWith("https://")) {
                    val uri = android.net.Uri.parse(qrContent)
                    uri.getQueryParameter("host")?.let { host = it }
                    uri.getQueryParameter("port")?.toIntOrNull()?.let { port = it }
                    uri.getQueryParameter("code")?.let { qrCode = it }
                    uri.getQueryParameter("qr_code")?.let { qrCode = it }
                } else if (qrContent.startsWith("{") && qrContent.endsWith("}")) {
                    try {
                        val jsonObj = org.json.JSONObject(qrContent)
                        if (jsonObj.has("host")) host = jsonObj.getString("host")
                        if (jsonObj.has("port")) port = jsonObj.getInt("port")
                        if (jsonObj.has("qr_code")) qrCode = jsonObj.getString("qr_code")
                        else if (jsonObj.has("code")) qrCode = jsonObj.getString("code")
                    } catch (_: Exception) {}
                }

                val targetHost = host
                    ?: currentClient?.baseUrl?.substringAfter("://")?.substringBefore(":")
                    ?: linkSession.connectedPeer.value?.ip

                if (targetHost.isNullOrBlank()) {
                    val firstPeer = linkDiscovery.peers.value.values.firstOrNull()
                    if (firstPeer != null) {
                        val client = LinkClient(baseUrl = "http://${firstPeer.ip}:${firstPeer.port}", clientDeviceId = clientId)
                        executeQrClaim(client, firstPeer, qrCode, onResult)
                    } else {
                        onResult(false, "No se encontró ningún servidor Michi en tu red local.")
                    }
                    return@launch
                }

                val baseUrl = "http://$targetHost:$port"
                val client = LinkClient(baseUrl = baseUrl, clientDeviceId = clientId)
                val peer = DiscoveredPeer(
                    alias = "Michi Node",
                    ip = targetHost,
                    port = port,
                    deviceType = "server",
                    deviceId = "",
                )
                executeQrClaim(client, peer, qrCode, onResult)
            } catch (e: Exception) {
                onResult(false, "Error al procesar el código: ${e.message}")
            }
        }
    }

    private suspend fun executeQrClaim(
        client: LinkClient,
        peer: DiscoveredPeer,
        qrCode: String,
        onResult: (Boolean, String) -> Unit,
    ) {
        client.claimQr(qrCode, QrClaimRequest(clientDeviceId = clientId)).fold(
            onSuccess = { response ->
                if (response.success && response.deviceToken.isNotBlank()) {
                    tokenStore.save(
                        serverId = response.deviceId.ifEmpty { peer.deviceId },
                        serverName = peer.alias,
                        service = "michi-link",
                        serverDeviceId = response.deviceId,
                        serverAlias = peer.alias,
                        clientDeviceId = clientId,
                        deviceToken = response.deviceToken,
                        refreshToken = response.refreshToken,
                        permissions = listOf("playback", "library", "sync"),
                        serverUrl = client.baseUrl,
                        roles = listOf("server"),
                        features = emptyList(),
                        authStrategy = PairingStrategy.SERVER_CODE,
                        tokenRefreshSupported = true,
                    )
                    client.deviceToken = response.deviceToken
                    currentClient = client
                    linkSession.onConnected(peer, client)
                    onResult(true, "Dispositivo vinculado correctamente")
                } else {
                    onResult(false, "El código QR es inválido o ha expirado")
                }
            },
            onFailure = { err ->
                val msg = when {
                    err.message?.contains("404") == true -> "Código QR expirado o no encontrado"
                    err.message?.contains("401") == true -> "Permiso denegado por el servidor"
                    err.message?.contains("Connect") == true -> "No se pudo conectar con el servidor"
                    else -> "Error de vinculación: ${err.message ?: "Intenta de nuevo"}"
                }
                onResult(false, msg)
            }
        )
    }

    fun clearError() { _error.value = null }

    fun schedulePeriodicSyncIfEnabled() {
        val prefs = context.getSharedPreferences("michi_settings", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("auto_sync", false)) return
        val client = currentClient ?: return
        val deviceId = uiState.value.pairingConfirm?.deviceId ?: clientId
        val effectiveToken = client.deviceToken.ifEmpty { client.sessionToken }

        val inputData = SyncWorker.buildInputData(
            baseUrl = client.baseUrl,
            sessionToken = client.sessionToken,
            deviceId = deviceId,
            alias = android.os.Build.MODEL,
            deviceToken = effectiveToken,
            clientDeviceId = client.clientDeviceId.ifEmpty { clientId },
        )

        val workRequest = PeriodicWorkRequestBuilder<SyncWorker>(12, TimeUnit.HOURS)
            .setInputData(inputData)
            .build()

        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork("michi_sync_periodic", ExistingPeriodicWorkPolicy.KEEP, workRequest)
    }

    override fun onCleared() {
        super.onCleared()
        currentClient?.close()
        linkSession.disconnect()
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
