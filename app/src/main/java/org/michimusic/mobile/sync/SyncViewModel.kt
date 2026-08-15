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

import org.michimusic.link.PairedDeviceRegistry
import org.michimusic.link.ConnectionManager
import org.michimusic.link.PairedDevice

import org.michimusic.link.TokenStore
import org.michimusic.link.dto.LegacyPairConfirmResponseDto
import org.michimusic.link.dto.LegacyPairStartResponseDto
import org.michimusic.link.dto.PairingStrategy
import org.michimusic.link.dto.QrClaimRequest
import org.michimusic.link.dto.ServerInfoDto
import org.michimusic.link.errors.LinkException

data class SyncUiState(
    val state: SyncConnectionState = SyncConnectionState.DISCONNECTED,
    val peers: List<DiscoveredPeer> = emptyList(),
    val unifiedDevices: List<org.michimusic.core.models.UnifiedDevice> = emptyList(),
    val connectedPeer: DiscoveredPeer? = null,
    val pairingStart: LegacyPairStartResponseDto? = null,
    val pairingConfirm: LegacyPairConfirmResponseDto? = null,
    val pairingStrategy: PairingStrategy = PairingStrategy.LEGACY,
    val error: String? = null,
    val syncProgress: SyncProgress = SyncProgress.Idle,
)

class SyncViewModel(
    private val context: Context,
    private val linkDiscovery: LinkDiscovery,
    private val registry: PairedDeviceRegistry,
    private val connectionManager: ConnectionManager,
    private val identity: org.michimusic.link.identity.MichiIdentity,
    private val trackRepository: SyncedTrackRepository,
) : ViewModel() {

    private val _connectionState = MutableStateFlow(SyncConnectionState.DISCONNECTED)
    private val _connectedPeer = MutableStateFlow<DiscoveredPeer?>(null)
    private val _pairStartResponse = MutableStateFlow<LegacyPairStartResponseDto?>(null)
    private val _pairConfirmResponse = MutableStateFlow<LegacyPairConfirmResponseDto?>(null)
    private val _error = MutableStateFlow<String?>(null)
    private val _syncProgress = MutableStateFlow<SyncProgress>(SyncProgress.Idle)
    private val _pairingStrategy = MutableStateFlow(PairingStrategy.LEGACY)

    val uiState: StateFlow<SyncUiState> = combine(
        combine(
            linkDiscovery.peers.map { it.values.toList() },
            _connectionState,
            connectionManager.connectionStates
        ) { peers, connState, connStates ->
            Triple(peers, connState, connStates)
        },
        combine(
            _connectedPeer,
            _pairStartResponse,
            _pairingStrategy,
        ) { peer, pairStart, strategy ->
            Triple(peer, pairStart, strategy)
        },
    ) { (peers, connState, connStates), (peer, pairStart, strategy) ->
        val registered = registry.getAllDevices()
        val unified = mutableListOf<org.michimusic.core.models.UnifiedDevice>()
        
        // Add all registered devices first
        registered.forEach { reg ->
            val dPeer = peers.find { it.deviceId == reg.deviceId || it.ip == reg.lastUrl.substringAfter("://").substringBefore(":") }
            unified.add(
                org.michimusic.core.models.UnifiedDevice(
                    id = reg.deviceId,
                    name = dPeer?.alias ?: reg.deviceName,
                    ip = dPeer?.ip ?: reg.lastUrl.substringAfter("://").substringBefore(":"),
                    port = dPeer?.port ?: 53318,
                    connectionState = connStates[reg.deviceId] ?: (if (dPeer != null) SyncConnectionState.DISCONNECTED else SyncConnectionState.OFFLINE),
                    isPaired = true,
                    deviceType = reg.serviceType.ifEmpty { "server" },
                    roles = reg.roles,
                    features = reg.features
                )
            )
        }
        
        // Add discovered devices that are not registered
        peers.filter { p -> registered.none { it.deviceId == p.deviceId || it.lastUrl.contains(p.ip) } }.forEach { p ->
            unified.add(
                org.michimusic.core.models.UnifiedDevice(
                    id = p.deviceId.ifEmpty { p.ip },
                    name = p.alias,
                    ip = p.ip,
                    port = p.port,
                    connectionState = SyncConnectionState.DISCONNECTED,
                    isPaired = false,
                    deviceType = p.deviceType,
                    roles = emptyList(),
                    features = emptyList()
                )
            )
        }

        SyncUiState(
            state = connState,
            peers = peers,
            unifiedDevices = unified,
            connectedPeer = peer,
            pairingStart = pairStart,
            pairingStrategy = strategy,
        )
    }.combine(
        combine(_error, _syncProgress) { err, progress -> err to progress }
    ) { state, (err, progress) ->
        state.copy(error = err, syncProgress = progress)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SyncUiState(state = _connectionState.value))

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
        if (_connectionState.value != SyncConnectionState.DISCONNECTED) return
        _connectionState.value = SyncConnectionState.DISCOVERING
        viewModelScope.launch { linkDiscovery.start() }
    }

    fun stopDiscovery() {
        viewModelScope.launch {
            linkDiscovery.stop()
            if (_connectionState.value == SyncConnectionState.DISCOVERING) {
                _connectionState.value = SyncConnectionState.DISCONNECTED
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
        _connectionState.value = SyncConnectionState.DISCOVERING
        selectPeer(peer)
    }

    fun selectPeer(peer: DiscoveredPeer) {
        if (_connectionState.value != SyncConnectionState.DISCOVERING) return

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
                _connectionState.value = SyncConnectionState.DISCONNECTED
                return@launch
            }

            val existingDevice = registry.getDevice(resolvedDeviceId)
            if (existingDevice != null && existingDevice.deviceToken.isNotEmpty()) {
                client.deviceToken = existingDevice.deviceToken
                if (client.getServerInfo().isSuccess) {
                    _connectedPeer.value = peer
                    _connectionState.value = SyncConnectionState.CONNECTED
                    return@launch
                }
            }
            
            _connectionState.value = SyncConnectionState.PAIRING_REQUIRED
        }
    }

    private suspend fun resolveServerDeviceId(client: LinkClient, peer: DiscoveredPeer): String {
        if (peer.deviceId.isNotEmpty()) return peer.deviceId
        return runCatching {
            client.getServerInfoWithFallback().getOrNull()?.serverDeviceId
        }.getOrNull() ?: ""
    }

    private fun connectToPeerLegacy(peer: DiscoveredPeer) {
        _connectionState.value = SyncConnectionState.CONNECTING
        val baseUrl = "http://${peer.ip}:${peer.port}"
        val client = LinkClient(baseUrl = baseUrl, clientDeviceId = clientId)
        currentClient = client

        viewModelScope.launch {
            client.register(
                alias = android.os.Build.MODEL,
                deviceModel = android.os.Build.MODEL,
                clientDeviceId = clientId,
            ).onSuccess { response ->
                _connectedPeer.value = peer
                _connectionState.value = SyncConnectionState.CONNECTED
            }.onFailure { e ->
                _error.value = "Error al conectar: ${e.message}"
                _connectionState.value = SyncConnectionState.ERROR
                client.close()
            }
        }
    }

    fun startPairing(peer: DiscoveredPeer, username: String, password: String = "", pin: String = "") {
        val client = currentClient ?: return
        val strategy = _pairingStrategy.value
        _connectionState.value = SyncConnectionState.PAIRING

        if (strategy == PairingStrategy.SERVER_CODE) {
            startCodePairing(client, pin)
            return
        }

        viewModelScope.launch {
            if (strategy == PairingStrategy.LEGACY) {
                client.pairStartLegacy(
                    alias = android.os.Build.MODEL,
                    deviceModel = android.os.Build.MODEL,
                    clientDeviceId = clientId,
                ).onSuccess { startResp ->
                    pendingPairingId = startResp.pairingId
                    _pairStartResponse.value = startResp
                    _connectionState.value = SyncConnectionState.PAIRING
    
                    client.pairConfirmLegacy(
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
                            _connectionState.value = SyncConnectionState.PAIRING_REQUIRED
                            return@launch
                        }
                        val resolvedDeviceId = confirmResp.serverDeviceId.ifEmpty {
                            client.getServerInfoWithFallback().getOrNull()?.serverDeviceId.orEmpty()
                        }
                        val serverInfo = client.getServerInfo().getOrNull()
                        val device = org.michimusic.link.PairedDevice(
                            deviceId = resolvedDeviceId,
                            deviceName = confirmResp.serverAlias,
                            serviceType = serverInfo?.service ?: "",
                            deviceToken = effectiveToken,
                            refreshToken = confirmResp.refreshToken,
                            permissions = confirmResp.permissions,
                            roles = serverInfo?.roles ?: emptyList(),
                            features = emptyList(),
                            authStrategy = serverInfo?.effectiveAuthStrategy ?: PairingStrategy.LEGACY,
                            tokenRefreshSupported = client.tokenRefreshSupported ?: false,
                            pairedAt = System.currentTimeMillis(),
                            lastUrl = client.baseUrl
                        )
                        registry.saveDevice(device)
                        
                        client.deviceToken = effectiveToken
                        client.clientDeviceId = clientId
                        _pairConfirmResponse.value = confirmResp
                        _connectedPeer.value = peer
                        _connectionState.value = SyncConnectionState.PAIRED
                    }.onFailure { e ->
                        handlePairingFailure(Result.failure<Any>(e))
                    }
                }.onFailure { e ->
                    _error.value = "Error al iniciar emparejamiento: ${e.message}"
                    _connectionState.value = SyncConnectionState.ERROR
                }
            } else {
                val nonce = identity.generateNonce()
                val sig = identity.signChallenge(nonce)
                val request = org.michimusic.link.dto.PairStartRequestDto(
                    deviceName = android.os.Build.MODEL,
                    deviceType = "mobile",
                    roles = listOf("remote_controller"),
                    authStrategy = "ED25519_CHALLENGE",
                    michiId = identity.michiId,
                    publicKey = identity.publicKeyBase64Url,
                    challengeNonce = nonce,
                    challengeSignature = sig,
                )
                client.pairStart(request).onSuccess { startResp ->
                    if (!identity.verifyServerIdentity(startResp.serverMichiId, startResp.serverPublicKey)) {
                        _error.value = "Identity mismatch: La clave pública del servidor no coincide con su Michi ID."
                        _connectionState.value = SyncConnectionState.ERROR
                        return@onSuccess
                    }
                    _connectionState.value = SyncConnectionState.PAIRING
                    
                    val confirmReq = org.michimusic.link.dto.PairConfirmRequestDto(
                        sessionId = startResp.sessionId,
                        pin = pin,
                        michiId = identity.michiId,
                        publicKey = identity.publicKeyBase64Url,
                    )
                    client.pairConfirm(confirmReq).onSuccess { confirmResp ->
                        val effectiveToken = confirmResp.token
                        val resolvedDeviceId = confirmResp.serverId.ifEmpty {
                            client.getServerInfoWithFallback().getOrNull()?.serverDeviceId.orEmpty()
                        }
                        val serverInfo = client.getServerInfo().getOrNull()
                        val device = org.michimusic.link.PairedDevice(
                            deviceId = resolvedDeviceId,
                            deviceName = serverInfo?.effectiveName ?: peer.alias,
                            serviceType = serverInfo?.service ?: "",
                            deviceToken = effectiveToken,
                            refreshToken = confirmResp.refreshToken ?: "",
                            permissions = emptyList(),
                            roles = serverInfo?.roles ?: emptyList(),
                            features = emptyList(),
                            authStrategy = serverInfo?.effectiveAuthStrategy ?: PairingStrategy.ED25519_CHALLENGE,
                            tokenRefreshSupported = client.tokenRefreshSupported ?: false,
                            pairedAt = System.currentTimeMillis(),
                            lastUrl = client.baseUrl
                        )
                        registry.saveDevice(device)
                        
                        client.deviceToken = effectiveToken
                        client.clientDeviceId = identity.michiId
                        _connectedPeer.value = peer
                        _connectionState.value = SyncConnectionState.PAIRED
                    }.onFailure { e ->
                        handlePairingFailure(Result.failure<Any>(e))
                    }
                }.onFailure { e ->
                    _error.value = "Error al iniciar emparejamiento canónico: ${e.message}"
                    _connectionState.value = SyncConnectionState.ERROR
                }
            }
        }
    }

    private fun startCodePairing(client: LinkClient, pin: String) {
        viewModelScope.launch {
            val nonce = identity.generateNonce()
            val sig = identity.signChallenge(nonce)
            val request = org.michimusic.link.dto.PairStartRequestDto(
                deviceName = android.os.Build.MODEL,
                deviceType = "mobile",
                roles = listOf("remote_controller"),
                authStrategy = "SERVER_CODE",
                michiId = identity.michiId,
                publicKey = identity.publicKeyBase64Url,
                challengeNonce = nonce,
                challengeSignature = sig,
            )
            client.pairStart(request).onSuccess { startResp ->
                if (!identity.verifyServerIdentity(startResp.serverMichiId, startResp.serverPublicKey)) {
                    _error.value = "Identity mismatch: La clave pública del servidor no coincide con su Michi ID."
                    _connectionState.value = SyncConnectionState.ERROR
                    return@onSuccess
                }
                _pairStartResponse.value = org.michimusic.link.dto.LegacyPairStartResponseDto(
                    pairingId = startResp.sessionId,
                    serverDeviceId = startResp.serverMichiId
                )
                _connectionState.value = SyncConnectionState.PAIRING
                
                val confirmReq = org.michimusic.link.dto.PairConfirmRequestDto(
                    sessionId = startResp.sessionId,
                    pin = pin,
                    michiId = identity.michiId,
                    publicKey = identity.publicKeyBase64Url,
                )
                client.pairConfirm(confirmReq).onSuccess { confirmResp ->
                    val effectiveToken = confirmResp.token
                    if (effectiveToken.isBlank()) {
                        _error.value = "El código no otorgó un token válido"
                        _connectionState.value = SyncConnectionState.PAIRING_REQUIRED
                        return@launch
                    }
                    val resolvedDeviceId = confirmResp.serverId.ifEmpty {
                        client.getServerInfoWithFallback().getOrNull()?.serverDeviceId.orEmpty()
                    }
                    val serverInfo = client.getServerInfo().getOrNull()
                    
                    val device = PairedDevice(
                        deviceId = resolvedDeviceId,
                        deviceName = serverInfo?.effectiveName ?: "Michi Server",
                        serviceType = serverInfo?.service ?: "",
                        deviceToken = effectiveToken,
                        refreshToken = confirmResp.refreshToken ?: "",
                        permissions = emptyList(),
                        roles = serverInfo?.roles ?: emptyList(),
                        features = emptyList(),
                        authStrategy = serverInfo?.effectiveAuthStrategy ?: PairingStrategy.SERVER_CODE,
                        tokenRefreshSupported = client.tokenRefreshSupported ?: false,
                        pairedAt = System.currentTimeMillis(),
                        lastUrl = client.baseUrl
                    )
                    registry.saveDevice(device)
                    
                    client.deviceToken = effectiveToken
                    client.clientDeviceId = identity.michiId
                    _connectionState.value = SyncConnectionState.PAIRED
                }.onFailure { handlePairingFailure(Result.failure<Any>(it)) }
            }.onFailure { e ->
                _error.value = "Error al iniciar emparejamiento por código: ${e.message}"
                _connectionState.value = SyncConnectionState.ERROR
            }
        }
    }

    private fun handlePairingFailure(result: Result<*>) {
        val e = result.exceptionOrNull()
        if (e is LinkException.InvalidCredentials) {
            _error.value = "Credenciales inválidas"
            _connectionState.value = SyncConnectionState.PAIRING_REQUIRED
        } else {
            _error.value = "Error de emparejamiento: ${e?.message}"
            _connectionState.value = SyncConnectionState.ERROR
        }
    }

    fun connectToDevice(deviceId: String) {
        connectionManager.connect(deviceId)
    }

    fun forgetDevice(deviceId: String) {
        registry.removeDevice(deviceId)
        connectionManager.disconnect(deviceId)
        
        // If we are currently disconnected from everything else, we can stop discovery or clear state
        if (registry.getAllDevices().isEmpty()) {
            disconnect()
        }
    }

    fun disconnect() {
        viewModelScope.launch { linkDiscovery.stop() }
        currentClient?.close()
        currentClient = null
        _connectionState.value = SyncConnectionState.DISCONNECTED
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

        val parser = org.michimusic.link.identity.QrPairingParser(identity)
        val result = parser.parseAndValidate(qrContent)
        
        result.onFailure {
            onResult(false, it.message ?: "QR inválido")
            return
        }
        
        val canonicalQr = result.getOrThrow()

        viewModelScope.launch {
            try {
                // Ensure endpoint is an absolute URL
                val url = if (!canonicalQr.endpoint.startsWith("http")) {
                    "http://${canonicalQr.endpoint}"
                } else {
                    canonicalQr.endpoint
                }
                
                val client = org.michimusic.link.LinkClient(baseUrl = url, clientDeviceId = identity.michiId)
                
                val req = org.michimusic.link.dto.PairConfirmRequestDto(
                    sessionId = canonicalQr.sessionId,
                    pin = "",
                    michiId = identity.michiId,
                    publicKey = identity.publicKeyBase64Url
                )
                
                client.pairConfirm(req).onSuccess { confirmResp ->
                    val serverInfo = client.getServerInfoWithFallback().getOrNull()
                    val device = org.michimusic.link.PairedDevice(
                        deviceId = confirmResp.serverId.ifEmpty { canonicalQr.serverMichiId },
                        deviceName = serverInfo?.effectiveName ?: "Servidor Michi",
                        serviceType = serverInfo?.service ?: "",
                        deviceToken = confirmResp.token,
                        refreshToken = confirmResp.refreshToken ?: "",
                        permissions = emptyList(),
                        roles = serverInfo?.roles ?: emptyList(),
                        features = emptyList(),
                        authStrategy = serverInfo?.effectiveAuthStrategy ?: org.michimusic.link.dto.PairingStrategy.SERVER_CODE,
                        tokenRefreshSupported = client.tokenRefreshSupported ?: false,
                        pairedAt = System.currentTimeMillis(),
                        lastUrl = url
                    )
                    registry.saveDevice(device)
                    _connectionState.value = SyncConnectionState.PAIRED
                    onResult(true, "Dispositivo vinculado correctamente")
                }.onFailure { err ->
                    val msg = when {
                        err.message?.contains("404") == true -> "Código QR expirado o no encontrado"
                        err.message?.contains("401") == true -> "Permiso denegado por el servidor"
                        err.message?.contains("Connect") == true -> "No se pudo conectar con el servidor"
                        else -> "Error de vinculación: ${err.message ?: "Intenta de nuevo"}"
                    }
                    onResult(false, msg)
                }
            } catch (e: Exception) {
                onResult(false, "Error interno: ${e.message}")
            }
        }
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
