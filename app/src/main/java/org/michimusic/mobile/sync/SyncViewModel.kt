package org.michimusic.mobile.sync


import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.BackoffPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
import org.michimusic.mobile.ui.components.parseManualEndpoint
import org.michimusic.mobile.ui.components.sameDiscoveredEndpoint
import org.michimusic.mobile.ui.components.matchesObservedPeer
import org.michimusic.mobile.ui.components.isProvisionedMichiStream
import org.michimusic.mobile.ui.components.STREAM_PROVISIONING_GUIDANCE
import org.michimusic.mobile.ui.components.refusesPhysicalButtonForMichiStream
import java.net.URI


data class WizardUiState(
    val isLoading: Boolean = false,
    val error: String? = null,
    val deviceId: String = "",
    val deviceName: String = "",
    val candidateTracks: List<org.michimusic.core.models.ManifestTrack> = emptyList(),
    val playlists: List<org.michimusic.core.models.ManifestPlaylist> = emptyList(),
    val serverCapabilities: Set<String> = emptySet(),
    val serverRepresentations: List<String> = emptyList(),
    val availableStorageBytes: Long = 0L,
    val totalStorageBytes: Long = 0L,
    val currentLocalMusicBytes: Long = 0L,
    val localFingerprints: Set<String> = emptySet(),
    val existingPartials: Map<String, Long> = emptyMap(),
    val manifestVersion: Int = 1,
    val manifestChecksum: String = "",
    val storageStatus: org.michimusic.core.models.StoragePreflightStatus = org.michimusic.core.models.StoragePreflightStatus.AVAILABLE,
)


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
    val deviceSyncProgress: Map<String, SyncProgress> = emptyMap(),
    val planSyncProgress: Map<String, SyncProgress> = emptyMap(),
    val airCatProgress: org.michimusic.mobile.devices.aircat.AirCatProgressEvent? = null,
    val availableStorageBytes: Long = 0L,
)


class SyncViewModel(
    private val context: Context,
    private val linkDiscovery: LinkDiscovery,
    private val registry: PairedDeviceRegistry,
    private val connectionManager: ConnectionManager,
    private val identity: org.michimusic.link.identity.MichiIdentity,
    private val trackRepository: SyncedTrackRepository,
    private val airCatClient: org.michimusic.mobile.devices.aircat.AirCatTransferClient? = null,
    private val checkpointRepository: org.michimusic.core.models.TransferCheckpointRepository? = null,
    private val approvedPlanRepository: org.michimusic.core.models.ApprovedSyncPlanRepository? = null,
) : ViewModel() {


    private val _wizardUiState = MutableStateFlow(WizardUiState())
    val wizardUiState: StateFlow<WizardUiState> = _wizardUiState.asStateFlow()


    private val _connectionState = MutableStateFlow(SyncConnectionState.DISCONNECTED)
    private val _connectedPeer = MutableStateFlow<DiscoveredPeer?>(null)
    private val _pairStartResponse = MutableStateFlow<LegacyPairStartResponseDto?>(null)
    private val _pairConfirmResponse = MutableStateFlow<LegacyPairConfirmResponseDto?>(null)
    private val _error = MutableStateFlow<String?>(null)
    private val _syncProgress = MutableStateFlow<SyncProgress>(SyncProgress.Idle)
    private val _deviceSyncProgress = MutableStateFlow<Map<String, SyncProgress>>(emptyMap())
    private val _planSyncProgress = MutableStateFlow<Map<String, SyncProgress>>(emptyMap())
    private val _pairingStrategy = MutableStateFlow(PairingStrategy.LEGACY)
    // A receiver's advertised service/type is not proof of identity, but it can
    // prevent offering an impossible physical-button workflow for Michi Stream.
    private var pairingTargetIsMichiStream = false


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
            val previousEndpoint = runCatching { URI(reg.lastUrl) }.getOrNull()
            val hostFromUrl = previousEndpoint?.host.orEmpty()
            val portFromUrl = previousEndpoint?.port?.takeIf { it in 1..65535 } ?: 8400
            // A reused IP is NOT proof that a new Michi ID belongs to the old
            // paired device. Prefer exact nonblank identity, else exact endpoint.
            val dPeer = peers.find {
                matchesObservedPeer(reg.deviceId, hostFromUrl, portFromUrl, it.deviceId, it.ip, it.port)
            }
            unified.add(
                org.michimusic.core.models.UnifiedDevice(
                    id = reg.deviceId,
                    name = dPeer?.alias ?: reg.deviceName,
                    ip = dPeer?.ip ?: hostFromUrl,
                    port = dPeer?.port ?: portFromUrl,
                    connectionState = connStates[reg.deviceId] ?: (if (dPeer != null) SyncConnectionState.DISCONNECTED else SyncConnectionState.OFFLINE),
                    isPaired = true,
                    deviceType = reg.serviceType.ifBlank { dPeer?.deviceType?.takeIf { it.isNotBlank() } ?: "unknown" },
                    roles = reg.roles,
                    features = reg.features,
                    isRevoked = reg.isRevoked,
                )
            )
        }
        
        // Add discovered devices that are not registered (roles remain empty/UNKNOWN until ServerInfo is queried)
        peers.filter { p ->
            registered.none { reg ->
                val endpoint = runCatching { URI(reg.lastUrl) }.getOrNull()
                matchesObservedPeer(
                    reg.deviceId, endpoint?.host.orEmpty(),
                    endpoint?.port?.takeIf { it in 1..65535 } ?: 8400,
                    p.deviceId, p.ip, p.port,
                )
            }
        }.forEach { p ->
            val devId = if (p.deviceId.isNotBlank()) p.deviceId else "${p.ip}:${p.port}"
            unified.add(
                org.michimusic.core.models.UnifiedDevice(
                    id = devId,
                    name = p.alias,
                    ip = p.ip,
                    port = p.port,
                    connectionState = SyncConnectionState.DISCONNECTED,
                    isPaired = false,
                    deviceType = p.deviceType,
                    roles = emptyList(), // Strict: No functional roles granted prior to ServerInfo resolution
                    features = emptyList()
                )
            )
        }

        val statFs = try { android.os.StatFs(context.filesDir.absolutePath) } catch (_: Exception) { null }
        val freeBytes = statFs?.availableBytes ?: 0L

        SyncUiState(
            state = connState,
            peers = peers,
            unifiedDevices = unified.distinctBy { it.id },
            connectedPeer = peer,
            pairingStart = pairStart,
            pairingStrategy = strategy,
            availableStorageBytes = freeBytes,
        )
    }.combine(
        combine(
            _error,
            _syncProgress,
            _deviceSyncProgress,
            _planSyncProgress,
            airCatClient?.progressEvents ?: MutableStateFlow(null)
        ) { err, progress, devMap, planMap, airCatEvt ->
            val effectiveProg = if (progress is SyncProgress.Idle) {
                devMap.values.find { it is SyncProgress.Downloading || it is SyncProgress.Cancelling }
                    ?: planMap.values.find { it is SyncProgress.Downloading || it is SyncProgress.Cancelling }
                    ?: progress
            } else {
                progress
            }
            listOf(err, effectiveProg, devMap, planMap, airCatEvt)
        }
    ) { state, items ->
        val err = items[0] as? String
        val progress = items[1] as SyncProgress
        @Suppress("UNCHECKED_CAST")
        val devMap = items[2] as Map<String, SyncProgress>
        @Suppress("UNCHECKED_CAST")
        val planMap = items[3] as Map<String, SyncProgress>
        val airCatEvt = items[4] as? org.michimusic.mobile.devices.aircat.AirCatProgressEvent
        state.copy(
            error = err,
            syncProgress = progress,
            deviceSyncProgress = devMap,
            planSyncProgress = planMap,
            airCatProgress = airCatEvt
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SyncUiState(state = _connectionState.value))


    private var clientId: String = ""
    private var pendingPairingId: String = ""
    private var currentClient: LinkClient? = null


    init {
        clientId = identity.michiId.ifEmpty {
            try {
                "android_${android.provider.Settings.Secure.getString(
                    context.contentResolver,
                    android.provider.Settings.Secure.ANDROID_ID,
                )?.takeLast(6) ?: "000000"}"
            } catch (_: Exception) {
                "android_000000"
            }
        }


        // Restore active sync state after process death (both per-device and per-plan)
        viewModelScope.launch {
            try {
                WorkManager.getInstance(context)
                    .getWorkInfosByTagFlow("michi_sync")
                    .collect { workList ->
                        val updatedDevMap = mutableMapOf<String, SyncProgress>()
                        val updatedPlanMap = mutableMapOf<String, SyncProgress>()
                        workList.forEach { workInfo ->
                            val planTag = workInfo.tags.find { it.startsWith("michi_sync_plan_") }
                            val planTagId = planTag?.removePrefix("michi_sync_plan_") ?: ""
                            val devTag = workInfo.tags.find { it.startsWith("michi_sync_") && !it.startsWith("michi_sync_plan_") }
                            val devId = devTag?.removePrefix("michi_sync_") ?: ""


                            val currentProg: SyncProgress = if (!workInfo.state.isFinished) {
                                val progress = workInfo.progress
                                val total = progress.getInt(SyncWorker.PROGRESS_TOTAL, 0)
                                val current = progress.getInt(SyncWorker.PROGRESS_CURRENT, 0)
                                SyncProgress.Downloading(current, total, devId)
                            } else if (workInfo.state == WorkInfo.State.SUCCEEDED) {
                                val output = workInfo.outputData
                                val downloaded = output.getInt(SyncWorker.RESULT_DOWNLOADED, 0)
                                val errors = output.getInt(SyncWorker.RESULT_ERROR, 0)
                                SyncProgress.Complete(tracks = downloaded + errors, downloaded = downloaded, errors = errors)
                            } else if (workInfo.state == WorkInfo.State.FAILED) {
                                val output = workInfo.outputData
                                val errors = output.getInt(SyncWorker.RESULT_ERROR, 1)
                                SyncProgress.Error("Sincronización fallida ($errors error(es))")
                            } else {
                                SyncProgress.Error("Sincronización cancelada")
                            }


                            if (devId.isNotEmpty()) {
                                updatedDevMap[devId] = currentProg
                            }
                            if (planTagId.isNotEmpty()) {
                                updatedPlanMap[planTagId] = currentProg
                            }
                        }
                        _deviceSyncProgress.value = _deviceSyncProgress.value + updatedDevMap
                        _planSyncProgress.value = _planSyncProgress.value + updatedPlanMap
                        val anyActive = updatedPlanMap.values.find { it is SyncProgress.Downloading }
                            ?: updatedDevMap.values.find { it is SyncProgress.Downloading }
                        if (anyActive != null) {
                            _syncProgress.value = anyActive
                        }
                    }
            } catch (_: Exception) {
                // Ignore in headless/test environments where WorkManager is not initialized
            }
        }
    }


    fun startDiscovery() {
        _error.value = null
        if (_connectionState.value == SyncConnectionState.DISCONNECTED || _connectionState.value == SyncConnectionState.ERROR) {
            _connectionState.value = SyncConnectionState.DISCOVERING
        }
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


    fun connectManual(
        name: String,
        host: String,
        port: Int = 8400,
        onStrategyResolved: (PairingStrategy, String) -> Unit = { _, _ -> }
    ) {
        val endpoint = parseManualEndpoint(host, port)
        if (endpoint == null) {
            _error.value = "Dirección o puerto inválido. Revisa la conexión manual."
            _connectionState.value = SyncConnectionState.ERROR
            return
        }
        _error.value = null
        // Discovery metadata is useful for choosing the UI flow, but does not
        // confer trust. Authentication is still enforced by preparePairing.
        val discovered = linkDiscovery.peers.value.values.firstOrNull {
            sameDiscoveredEndpoint(endpoint.host, endpoint.port, it.ip, it.port)
        }
        val peer = discovered?.copy(alias = name.ifBlank { discovered.alias }) ?: DiscoveredPeer(
            alias = name.ifBlank { "Michi Node" }, ip = endpoint.host, port = endpoint.port,
        )
        preparePairing(peer, onStrategyResolved)
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


    fun preparePairing(peer: DiscoveredPeer, onStrategyResolved: (PairingStrategy, String) -> Unit) {
        _error.value = null
        stopDiscovery()
        _connectionState.value = SyncConnectionState.CONNECTING
        pairingTargetIsMichiStream = isProvisionedMichiStream(peer.deviceType) ||
            peer.deviceType.equals("stream", ignoreCase = true)
        val client = LinkClient(
            baseUrl = "http://${peer.ip}:${peer.port}",
            clientDeviceId = identity.michiId,
        )
        currentClient = client
        _connectedPeer.value = peer


        viewModelScope.launch {
            val serverInfo = client.getServerInfo().getOrNull()
            val resolvedDeviceId = resolveServerDeviceId(client, peer)
            pairingTargetIsMichiStream = pairingTargetIsMichiStream ||
                isProvisionedMichiStream(serverInfo?.service.orEmpty())
            val strategy = when {
                serverInfo?.auth?.required == false -> PairingStrategy.ED25519_CHALLENGE
                serverInfo != null && serverInfo.auth?.strategy?.isNotEmpty() == true -> serverInfo.effectiveAuthStrategy
                peer.deviceType.lowercase() in listOf("receiver", "stream") -> {
                    // Preprovisioned or trust-network receivers without explicit auth requirements don't demand button press
                    if (!peer.authRequired && serverInfo?.auth?.required != true) PairingStrategy.ED25519_CHALLENGE
                    else PairingStrategy.RECEIVER_BUTTON
                }
                peer.authRequired -> PairingStrategy.SERVER_CODE
                else -> PairingStrategy.ED25519_CHALLENGE
            }
            val existingDevice = registry.getDevice(resolvedDeviceId)
            if (existingDevice != null && existingDevice.deviceToken.isNotEmpty()) {
                client.deviceToken = existingDevice.deviceToken
                if (client.getServerInfo().isSuccess) {
                    _connectionState.value = SyncConnectionState.CONNECTED
                    return@launch
                }
            }


            // Firmware-provisioned Michi Stream has no button. Reject an
            // incompatible server-advertised legacy strategy rather than
            // substituting auth methods or misleading the user.
            if (refusesPhysicalButtonForMichiStream(
                    peer.deviceType, serverInfo?.service.orEmpty(), strategy.name,
                )) {
                _error.value = STREAM_PROVISIONING_GUIDANCE
                _connectionState.value = SyncConnectionState.ERROR
                client.close()
                if (currentClient === client) currentClient = null
                return@launch
            }
            _pairingStrategy.value = strategy

            _connectionState.value = SyncConnectionState.PAIRING_REQUIRED
            val serverName = serverInfo?.effectiveName ?: peer.alias.ifEmpty { "Michi Node" }
            onStrategyResolved(strategy, serverName)
        }
    }


    fun startPairing(peer: DiscoveredPeer? = _connectedPeer.value, username: String = "", password: String = "", pin: String = "") {
        confirmPairing(pin = pin, username = username, password = password, peer = peer)
    }


    fun confirmPairing(pin: String = "", username: String = "", password: String = "", peer: DiscoveredPeer? = _connectedPeer.value) {
        if (peer != null) _connectedPeer.value = peer
        val client = currentClient ?: return
        val strategy = _pairingStrategy.value
        if (pairingTargetIsMichiStream && strategy == PairingStrategy.RECEIVER_BUTTON) {
            _error.value = STREAM_PROVISIONING_GUIDANCE
            _connectionState.value = SyncConnectionState.ERROR
            return
        }
        _connectionState.value = SyncConnectionState.PAIRING

        when (strategy) {
            PairingStrategy.SERVER_CODE -> {
                if (pin.isBlank()) {
                    _error.value = "Se requiere el código PIN para este servidor"
                    _connectionState.value = SyncConnectionState.PAIRING_REQUIRED
                    return
                }
                startCodePairing(client, pin)
            }
            PairingStrategy.RECEIVER_BUTTON -> {
                if (pin.isBlank()) {
                    _error.value = "Este receptor requiere un PIN de confirmación"
                    _connectionState.value = SyncConnectionState.PAIRING_REQUIRED
                    return
                }
                startCanonicalChallengePairing(client, strategy, pin, peer)
            }
            PairingStrategy.ED25519_CHALLENGE -> {
                // Cryptographic challenge pairing (no PIN required)
                startCanonicalChallengePairing(client, strategy, pin = "", peer = peer)
            }
            PairingStrategy.PLAYER_PASSWORD, PairingStrategy.LEGACY -> {
                startLegacyPairing(client, username, password, peer)
            }
        }
    }


    private fun startLegacyPairing(client: LinkClient, username: String, password: String, peer: DiscoveredPeer?) {
        viewModelScope.launch {
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
                    val serverInfo = client.getServerInfo().getOrNull()
                    val remoteSrvId = serverInfo?.effectiveServerId?.ifEmpty { confirmResp.serverDeviceId } ?: confirmResp.serverDeviceId
                    val assignedClientId = confirmResp.deviceId.ifEmpty { clientId }
                    val device = org.michimusic.link.PairedDevice(
                        deviceId = remoteSrvId,
                        deviceName = confirmResp.serverAlias.ifEmpty { serverInfo?.effectiveName ?: "Michi Server" },
                        serviceType = serverInfo?.service ?: "",
                        deviceToken = effectiveToken,
                        refreshToken = confirmResp.refreshToken,
                        permissions = confirmResp.permissions,
                        roles = serverInfo?.roles ?: emptyList(),
                        features = serverInfo?.effectiveFeatures ?: emptyList(),
                        featuresMap = serverInfo?.features ?: emptyMap(),
                        authStrategy = serverInfo?.effectiveAuthStrategy ?: PairingStrategy.LEGACY,
                        tokenRefreshSupported = client.tokenRefreshSupported ?: false,
                        pairedAt = System.currentTimeMillis(),
                        lastUrl = client.baseUrl,
                        serverId = remoteSrvId,
                        michiId = serverInfo?.michiId ?: "",
                        remoteServerId = remoteSrvId,
                        remoteMichiId = serverInfo?.michiId ?: "",
                        remotePublicKey = serverInfo?.publicKey ?: "",
                        identityScheme = serverInfo?.identityScheme ?: "legacy",
                        pairedClientDeviceId = assignedClientId,
                    )
                    registry.saveDevice(device)
                    
                    client.deviceToken = effectiveToken
                    client.clientDeviceId = assignedClientId
                    client.pairedClientDeviceId = assignedClientId
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
        }
    }


    private fun startCanonicalChallengePairing(
        client: LinkClient,
        strategy: PairingStrategy,
        pin: String,
        peer: DiscoveredPeer?
    ) {
        viewModelScope.launch {
            val nonce = identity.generateNonce()
            val sig = identity.signChallenge(nonce)
            val request = org.michimusic.link.dto.PairStartRequestDto(
                deviceName = android.os.Build.MODEL,
                deviceType = "mobile",
                roles = listOf("remote_controller"),
                authStrategy = if (strategy == PairingStrategy.RECEIVER_BUTTON) "RECEIVER_BUTTON" else "ED25519_CHALLENGE",
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
                    pin = if (strategy == PairingStrategy.ED25519_CHALLENGE) null else pin,
                    michiId = identity.michiId,
                    publicKey = identity.publicKeyBase64Url,
                )
                client.pairConfirm(confirmReq).onSuccess { confirmResp ->
                    val effectiveToken = confirmResp.token
                    val serverInfo = client.getServerInfo().getOrNull()
                    val remoteSrvId = serverInfo?.effectiveServerId ?: confirmResp.serverId.ifEmpty { startResp.serverMichiId }
                    val assignedClientId = confirmResp.deviceId.ifEmpty { clientId }


                    val device = org.michimusic.link.PairedDevice(
                        deviceId = remoteSrvId,
                        deviceName = serverInfo?.effectiveName ?: peer?.alias.orEmpty().ifEmpty { "Michi Node" },
                        serviceType = serverInfo?.service ?: "",
                        deviceToken = effectiveToken,
                        refreshToken = confirmResp.refreshToken ?: "",
                        permissions = emptyList(),
                        roles = serverInfo?.roles ?: emptyList(),
                        features = serverInfo?.effectiveFeatures ?: emptyList(),
                        featuresMap = serverInfo?.features ?: emptyMap(),
                        authStrategy = serverInfo?.effectiveAuthStrategy ?: (if (strategy == PairingStrategy.RECEIVER_BUTTON) PairingStrategy.RECEIVER_BUTTON else PairingStrategy.ED25519_CHALLENGE),
                        tokenRefreshSupported = client.tokenRefreshSupported ?: false,
                        pairedAt = System.currentTimeMillis(),
                        lastUrl = client.baseUrl,
                        serverId = remoteSrvId,
                        michiId = startResp.serverMichiId,
                        publicKey = startResp.serverPublicKey,
                        identityScheme = serverInfo?.identityScheme ?: "ed25519-blake3-v1",
                        remoteServerId = remoteSrvId,
                        remoteMichiId = startResp.serverMichiId,
                        remotePublicKey = startResp.serverPublicKey,
                        pairedClientDeviceId = assignedClientId,
                    )
                    registry.saveDevice(device)
                    
                    client.deviceToken = effectiveToken
                    client.clientDeviceId = assignedClientId
                    client.pairedClientDeviceId = assignedClientId
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
                    val serverInfo = client.getServerInfo().getOrNull()
                    val remoteSrvId = serverInfo?.effectiveServerId ?: confirmResp.serverId.ifEmpty { startResp.serverMichiId }
                    val assignedClientId = confirmResp.deviceId.ifEmpty { clientId }
                    
                    val device = PairedDevice(
                        deviceId = remoteSrvId,
                        deviceName = serverInfo?.effectiveName ?: "Michi Server",
                        serviceType = serverInfo?.service ?: "",
                        deviceToken = effectiveToken,
                        refreshToken = confirmResp.refreshToken ?: "",
                        permissions = emptyList(),
                        roles = serverInfo?.roles ?: emptyList(),
                        features = serverInfo?.effectiveFeatures ?: emptyList(),
                        featuresMap = serverInfo?.features ?: emptyMap(),
                        authStrategy = serverInfo?.effectiveAuthStrategy ?: PairingStrategy.SERVER_CODE,
                        tokenRefreshSupported = client.tokenRefreshSupported ?: false,
                        pairedAt = System.currentTimeMillis(),
                        lastUrl = client.baseUrl,
                        serverId = remoteSrvId,
                        michiId = startResp.serverMichiId,
                        publicKey = startResp.serverPublicKey,
                        identityScheme = serverInfo?.identityScheme ?: "ed25519-blake3-v1",
                        remoteServerId = remoteSrvId,
                        remoteMichiId = startResp.serverMichiId,
                        remotePublicKey = startResp.serverPublicKey,
                        pairedClientDeviceId = assignedClientId,
                    )
                    registry.saveDevice(device)
                    
                    client.deviceToken = effectiveToken
                    client.clientDeviceId = assignedClientId
                    client.pairedClientDeviceId = assignedClientId
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


    fun disconnectDevice(deviceId: String) {
        connectionManager.disconnectDevice(deviceId)
        if (_connectedPeer.value?.deviceId == deviceId) {
            _connectedPeer.value = null
            _connectionState.value = SyncConnectionState.DISCONNECTED
        }
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


    fun syncPlannedLibrary(plan: org.michimusic.core.models.SyncPlan) {
        if (!plan.isExecutable || plan.totalSelectedTracks == 0) return
        val targetDeviceId = plan.endpointId


        val approvedRepo = approvedPlanRepository ?: org.michimusic.mobile.di.approvedSyncPlanRepository
        viewModelScope.launch {
            val manifestVer = if (plan.manifestVersion > 0) plan.manifestVersion else _wizardUiState.value.manifestVersion
            val manifestCheck = plan.manifestChecksum.ifEmpty { _wizardUiState.value.manifestChecksum }
            val saveResult = runCatching {
                approvedRepo?.savePlan(
                    plan = plan,
                    manifestVersion = manifestVer,
                    manifestChecksum = manifestCheck,
                )
            }


            if (saveResult.isFailure || (approvedRepo != null && saveResult.getOrNull() == null)) {
                _error.value = "Error al persistir el plan de sincronización. No se ha iniciado la transferencia."
                return@launch
            }


            val tracksToDownload = plan.items.filter {
                it.action == org.michimusic.core.models.SyncItemAction.DOWNLOAD ||
                it.action == org.michimusic.core.models.SyncItemAction.RESUME_PARTIAL ||
                it.action == org.michimusic.core.models.SyncItemAction.REPLACE_BETTER_QUALITY
            }.map { it.trackId }


            syncLibrary(
                targetDeviceId = targetDeviceId,
                selectedTrackIds = tracksToDownload,
                profile = plan.deliveryChoice.profile.name,
                scope = plan.selection.scope.name,
                planId = plan.planId,
                expectedBytes = plan.totalDownloadBytes,
            )
        }
    }


    fun syncLibrary(
        targetDeviceId: String? = null,
        selectedTrackIds: List<String> = emptyList(),
        profile: String = "ORIGINAL",
        scope: String = "FULL_LIBRARY",
        planId: String = "",
        expectedBytes: Long = 0L,
    ) {
        val currentState = uiState.value
        val pairConfirm = currentState.pairingConfirm
        val connectedPeer = currentState.connectedPeer


        // Resolve remote server deviceId strictly: targetDeviceId -> pairConfirm -> connectedPeer -> registry
        val remoteDeviceId = targetDeviceId
            ?: pairConfirm?.deviceId
            ?: connectedPeer?.deviceId?.ifEmpty { null }
            ?: registry.getAllDevices().firstOrNull()?.effectiveServerId
            ?: return


        val pairedDevice = registry.getDevice(remoteDeviceId)
        if (pairedDevice != null && pairedDevice.isRevoked) {
            _error.value = "Dispositivo remoto revocado"
            return
        }


        val baseUrl = currentClient?.baseUrl
            ?: pairedDevice?.lastUrl?.ifEmpty { null }
            ?: (if (connectedPeer != null) "http://${connectedPeer.ip}:${connectedPeer.port}" else return)


        // Cryptographic separation: local phone identity vs remote target vs paired client device ID
        val localMichiId = identity.michiId
        val pairedClientDeviceId = pairedDevice?.effectiveClientDeviceId?.ifEmpty { null }
            ?: currentClient?.pairedClientDeviceId?.ifEmpty { null }
            ?: localMichiId


        _syncProgress.value = SyncProgress.Downloading(0, 0, remoteDeviceId)


        val inputData = SyncWorker.buildInputData(
            baseUrl = baseUrl,
            deviceId = remoteDeviceId,
            alias = android.os.Build.MODEL,
            clientDeviceId = pairedClientDeviceId,
            selectedTrackIds = selectedTrackIds.joinToString(","),
            profile = profile,
            scope = scope,
            planId = planId,
            expectedBytes = expectedBytes,
        )


        val safeDev = remoteDeviceId.replace(Regex("[^A-Za-z0-9_]"), "_")
        val safePlan = if (planId.isNotEmpty()) planId.replace(Regex("[^A-Za-z0-9_]"), "_") else ""
        val workRequestBuilder = OneTimeWorkRequestBuilder<SyncWorker>()
            .setInputData(inputData)
            .addTag("michi_sync")
            .addTag("michi_sync_$safeDev")


        if (safePlan.isNotEmpty()) {
            workRequestBuilder.addTag("michi_sync_plan_$safePlan")
        }


        val workRequest = workRequestBuilder
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()


        val workName = if (safePlan.isNotEmpty()) "michi_sync_plan_$safePlan" else "michi_sync_$safeDev"
        WorkManager.getInstance(context)
            .enqueueUniqueWork(workName, ExistingWorkPolicy.REPLACE, workRequest)


        if (planId.isNotEmpty()) {
            _planSyncProgress.value = _planSyncProgress.value + (planId to SyncProgress.Downloading(0, 0, remoteDeviceId))
        }


        viewModelScope.launch {
            WorkManager.getInstance(context)
                .getWorkInfoByIdFlow(workRequest.id)
                .collect { info ->
                    if (info == null) return@collect
                    val progress = info.progress
                    val total = progress.getInt(SyncWorker.PROGRESS_TOTAL, 0)
                    val current = progress.getInt(SyncWorker.PROGRESS_CURRENT, 0)
                    val currentProg = if (info.state.isFinished) {
                        if (info.state == WorkInfo.State.SUCCEEDED) {
                            val output = info.outputData
                            val downloaded = output.getInt(SyncWorker.RESULT_DOWNLOADED, current)
                            val errors = output.getInt(SyncWorker.RESULT_ERROR, 0)
                            SyncProgress.Complete(tracks = total, downloaded = downloaded, errors = errors)
                        } else if (info.state == WorkInfo.State.FAILED) {
                            val output = info.outputData
                            val errors = output.getInt(SyncWorker.RESULT_ERROR, 1)
                            SyncProgress.Error("Sincronización fallida ($errors error(es))")
                        } else {
                            SyncProgress.Error("Sincronización cancelada")
                        }
                    } else {
                        SyncProgress.Downloading(current, total, remoteDeviceId)
                    }


                    _deviceSyncProgress.value = _deviceSyncProgress.value + (remoteDeviceId to currentProg)
                    if (planId.isNotEmpty()) {
                        _planSyncProgress.value = _planSyncProgress.value + (planId to currentProg)
                    }
                    _syncProgress.value = currentProg
                }
        }
    }


    fun retryPlan(planId: String) {
        val approvedRepo = approvedPlanRepository ?: org.michimusic.mobile.di.approvedSyncPlanRepository
        if (approvedRepo == null) return


        viewModelScope.launch {
            val approvedPlan = approvedRepo.getPlan(planId) ?: return@launch


            // Reintentos manuales NO pueden revivir planes terminales FAILED ni sobrescribir
            // el CAS. Solo RETRY_PENDING (fallo clasificado recuperable por SyncWorker)
            // o QUEUED pueden encolarse nuevamente sin una revisión de autorización.
            val retryable = setOf(
                org.michimusic.core.models.TransferLifecycleState.RETRY_PENDING.name,
                org.michimusic.core.models.TransferLifecycleState.QUEUED.name,
            )
            if (approvedPlan.executionState !in retryable) {
                _error.value = "El plan $planId no admite reintento directo; revisa y aprueba un plan nuevo."
                return@launch
            }
            val decisions = try {
                kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
                    .decodeFromString<List<org.michimusic.core.models.SyncItemDecision>>(approvedPlan.itemsJson)
            } catch (_: Exception) {
                _error.value = "No se puede reintentar $planId: las decisiones aprobadas son inválidas."
                return@launch
            }
            val tracksToDownload = decisions.filter {
                it.action == org.michimusic.core.models.SyncItemAction.DOWNLOAD ||
                it.action == org.michimusic.core.models.SyncItemAction.RESUME_PARTIAL ||
                it.action == org.michimusic.core.models.SyncItemAction.REPLACE_BETTER_QUALITY
            }.map { it.trackId }


            syncLibrary(
                targetDeviceId = approvedPlan.endpointId,
                selectedTrackIds = tracksToDownload,
                profile = "ORIGINAL",
                scope = approvedPlan.scope,
                planId = approvedPlan.planId,
                expectedBytes = approvedPlan.totalDownloadBytes,
            )
        }
    }


    fun loadWizardData(targetDeviceId: String) {
        _wizardUiState.value = WizardUiState(isLoading = true, deviceId = targetDeviceId)
        viewModelScope.launch {
            try {
                val filesDir = context.filesDir
                val isWritable = filesDir != null && filesDir.exists() && filesDir.canWrite()
                val statFs = if (isWritable) {
                    try { android.os.StatFs(filesDir.absolutePath) } catch (_: Exception) { null }
                } else null


                val storageStatus = when {
                    !isWritable -> org.michimusic.core.models.StoragePreflightStatus.NOT_WRITABLE
                    statFs == null -> org.michimusic.core.models.StoragePreflightStatus.UNKNOWN
                    else -> org.michimusic.core.models.StoragePreflightStatus.AVAILABLE
                }


                val availableBytes = statFs?.availableBytes ?: 0L
                val totalBytes = statFs?.totalBytes ?: 0L


                val syncedMusicDir = java.io.File(context.filesDir, "synced_music")
                val dirBytes = if (syncedMusicDir.exists()) {
                    syncedMusicDir.walkTopDown().filter { it.isFile && !it.name.endsWith(".part") }.sumOf { it.length() }
                } else 0L
                val dbBytes = trackRepository.getDownloadedBytes()
                val currentLocalMusicBytes = maxOf(dirBytes, dbBytes)


                val localFingerprints = trackRepository.getDownloadedTrackFingerprints()
                val effectiveCheckpointRepo = checkpointRepository ?: org.michimusic.mobile.di.transferCheckpointRepository
                val checkpoints = effectiveCheckpointRepo?.getCheckpointsForDevice(targetDeviceId) ?: emptyList()
                val partials = checkpoints.filter { it.receivedBytes > 0 && it.transferState != org.michimusic.core.models.TransferStatus.COMPLETED }
                    .associate { it.trackId to it.receivedBytes }


                val pairedDevice = registry.getDevice(targetDeviceId)
                val client = connectionManager.getClient(targetDeviceId)
                    ?: if (pairedDevice != null && pairedDevice.lastUrl.isNotEmpty() && pairedDevice.deviceToken.isNotEmpty()) {
                        LinkClient(
                            baseUrl = pairedDevice.lastUrl,
                            sessionToken = pairedDevice.deviceToken,
                            deviceToken = pairedDevice.deviceToken,
                            clientDeviceId = pairedDevice.pairedClientDeviceId.ifEmpty { identity.michiId },
                            pairedClientDeviceId = pairedDevice.pairedClientDeviceId,
                        )
                    } else null


                if (client == null) {
                    _wizardUiState.value = WizardUiState(
                        isLoading = false,
                        error = "Dispositivo no conectado ni registrado",
                        deviceId = targetDeviceId,
                        availableStorageBytes = availableBytes,
                        totalStorageBytes = totalBytes,
                        currentLocalMusicBytes = currentLocalMusicBytes,
                        storageStatus = storageStatus,
                    )
                    return@launch
                }


                val serverInfoRes = client.getServerInfo()
                val serverInfo = serverInfoRes.getOrNull()
                val serverFeatures = serverInfo?.effectiveFeatures?.toSet() ?: pairedDevice?.features?.toSet() ?: emptySet()
                val serverRepresentations = serverInfo?.audio?.codecs ?: emptyList()


                val manifestRes = client.fetchSyncManifest(targetDeviceId)
                if (manifestRes.isFailure) {
                    _wizardUiState.value = WizardUiState(
                        isLoading = false,
                        error = "Error al obtener catálogo: ${manifestRes.exceptionOrNull()?.message}",
                        deviceId = targetDeviceId,
                        deviceName = pairedDevice?.deviceName ?: "Micro Server",
                        serverCapabilities = serverFeatures,
                        serverRepresentations = serverRepresentations,
                        availableStorageBytes = availableBytes,
                        totalStorageBytes = totalBytes,
                        currentLocalMusicBytes = currentLocalMusicBytes,
                        storageStatus = storageStatus,
                    )
                    return@launch
                }


                val manifestDto = manifestRes.getOrThrow()
                val candidateTracks = manifestDto.effectiveTracks.map { dto ->
                    org.michimusic.core.models.ManifestTrack(
                        trackId = dto.trackId,
                        title = dto.title,
                        artist = dto.artist,
                        album = dto.album,
                        size = dto.size,
                        format = dto.format,
                        duration = dto.duration,
                        year = dto.year,
                        genre = dto.genre,
                        coverId = dto.coverId,
                        checksum = dto.checksum ?: "",
                        downloadPath = dto.downloadPath,
                    )
                }
                val playlists = manifestDto.playlists.map { it.toDomainPlaylist() }


                _wizardUiState.value = WizardUiState(
                    isLoading = false,
                    deviceId = targetDeviceId,
                    deviceName = pairedDevice?.deviceName ?: serverInfo?.effectiveName ?: "Micro Server",
                    candidateTracks = candidateTracks,
                    playlists = playlists,
                    serverCapabilities = serverFeatures,
                    serverRepresentations = serverRepresentations,
                    availableStorageBytes = availableBytes,
                    totalStorageBytes = totalBytes,
                    currentLocalMusicBytes = currentLocalMusicBytes,
                    localFingerprints = localFingerprints,
                    existingPartials = partials,
                    manifestVersion = manifestDto.version,
                    manifestChecksum = manifestDto.manifestId.ifEmpty { manifestDto.createdAt },
                    storageStatus = storageStatus,
                )
            } catch (e: Exception) {
                _wizardUiState.value = WizardUiState(
                    isLoading = false,
                    error = e.message ?: "Error desconocido cargando asistente",
                    deviceId = targetDeviceId,
                )
            }
        }
    }


    fun pauseAirCatTransfer(transferId: String) {
        airCatClient?.pauseTransfer(transferId)
    }


    fun resumeAirCatTransfer(transferId: String) {
        airCatClient?.resumeTransfer(transferId)
    }


    fun cancelAirCatTransfer(transferId: String) {
        airCatClient?.cancelTransfer(transferId)
    }

    fun clearAirCatProgress() {
        airCatClient?.clearProgress()
    }


    fun recoverIncompleteAirCatTransfers(targetDeviceId: String, onResult: (Boolean, String) -> Unit = { _, _ -> }) {
        val client = currentClient
        val airCat = airCatClient
        if (airCat == null) {
            onResult(false, "Servicio AirCat no disponible")
            return
        }
        val device = registry.getDevice(targetDeviceId)
        if (device == null || device.isRevoked) {
            onResult(false, "Dispositivo no encontrado o revocado")
            return
        }
        viewModelScope.launch {
            try {
                val effectiveClient = connectionManager.getClient(targetDeviceId)
                    ?: if (client != null && client.baseUrl == device.lastUrl && client.deviceToken == device.deviceToken) {
                        client
                    } else {
                        LinkClient(
                            baseUrl = device.lastUrl,
                            sessionToken = device.deviceToken,
                            deviceToken = device.deviceToken,
                            clientDeviceId = device.pairedClientDeviceId.ifEmpty { identity.michiId },
                            pairedClientDeviceId = device.pairedClientDeviceId,
                        )
                    }
                val recoveryResult = airCat.recoverIncompleteTransfers(targetDeviceId, effectiveClient)
                if (recoveryResult.isSuccess) {
                    val count = recoveryResult.getOrThrow().size
                    onResult(true, "Recuperación completada ($count pistas restauradas)")
                } else {
                    onResult(false, "Fallo en recuperación: ${recoveryResult.exceptionOrNull()?.message}")
                }
            } catch (e: Exception) {
                onResult(false, "Error durante recuperación: ${e.message}")
            }
        }
    }


    fun startAirCatTransfer(targetDeviceId: String, onResult: (Boolean, String) -> Unit = { _, _ -> }) {
        val client = currentClient
        val airCat = airCatClient
        if (airCat == null) {
            onResult(false, "Servicio AirCat no disponible")
            return
        }


        val device = registry.getDevice(targetDeviceId)
        if (device == null) {
            onResult(false, "Dispositivo no encontrado")
            return
        }


        viewModelScope.launch {
            try {
                if (device.deviceToken.isEmpty() || device.lastUrl.isEmpty()) {
                    onResult(false, "El dispositivo $targetDeviceId no posee credenciales o URL válidas")
                    return@launch
                }


                // Strictly resolve client matching targetDeviceId to avoid cross-device token collision
                val effectiveClient = connectionManager.getClient(targetDeviceId)
                    ?: if (client != null && client.baseUrl == device.lastUrl && client.deviceToken == device.deviceToken) {
                        client
                    } else {
                        LinkClient(
                            baseUrl = device.lastUrl,
                            sessionToken = device.deviceToken,
                            deviceToken = device.deviceToken,
                            clientDeviceId = device.pairedClientDeviceId.ifEmpty { identity.michiId },
                            pairedClientDeviceId = device.pairedClientDeviceId,
                        )
                    }


                // Verify remote session trust, active connection, and peer identity consistency
                if (device.isRevoked) {
                    onResult(false, "El dispositivo $targetDeviceId ha sido revocado")
                    return@launch
                }


                val serverInfoRes = effectiveClient.getServerInfo()
                if (serverInfoRes.isFailure) {
                    onResult(false, "No se pudo verificar la sesión remota con $targetDeviceId: ${serverInfoRes.exceptionOrNull()?.message}")
                    return@launch
                }
                val serverInfo = serverInfoRes.getOrThrow()
                val remoteServerId = serverInfo.effectiveServerId
                val remoteMichiId = serverInfo.michiId ?: ""
                val remotePublicKey = serverInfo.publicKey ?: ""


                // 1. Identity match validation: reject if expected attribute is present but missing or mismatched from peer
                val expectedServerId = device.effectiveServerId
                val expectedMichiId = device.effectiveMichiId
                val expectedPublicKey = device.effectivePublicKey


                if (expectedServerId.isNotEmpty()) {
                    if (remoteServerId.isEmpty() || expectedServerId != remoteServerId) {
                        onResult(false, "Inconsistencia de serverId remoto: esperado $expectedServerId pero respondió ${remoteServerId.ifEmpty { "<vacio>" }}")
                        return@launch
                    }
                }


                if (expectedMichiId.isNotEmpty()) {
                    if (remoteMichiId.isEmpty() || expectedMichiId != remoteMichiId) {
                        onResult(false, "Inconsistencia de MichiId remoto: esperado $expectedMichiId pero respondió ${remoteMichiId.ifEmpty { "<vacio>" }}")
                        return@launch
                    }
                }


                // 2. Cryptographic public key verification against pinned trusted registry
                if (expectedPublicKey.isNotEmpty()) {
                    if (remotePublicKey.isEmpty() || expectedPublicKey != remotePublicKey) {
                        onResult(false, "Inconsistencia criptográfica: clave pública remota ${if (remotePublicKey.isEmpty()) "no provista" else "no coincide"} para $targetDeviceId")
                        return@launch
                    }
                }


                // 1. Fetch manifest
                val manifestRes = effectiveClient.fetchSyncManifest(targetDeviceId)
                if (manifestRes.isFailure) {
                    onResult(false, "Error al obtener catálogo: ${manifestRes.exceptionOrNull()?.message}")
                    return@launch
                }


                val manifestDto = manifestRes.getOrThrow()
                val tracks = manifestDto.effectiveTracks
                if (tracks.isEmpty()) {
                    onResult(true, "No hay pistas disponibles para transferir")
                    return@launch
                }


                val transferId = "aircat_${System.currentTimeMillis()}"
                val destLibId = "lib_synced_${targetDeviceId}"
                val manifestItems = tracks.map { t ->
                    org.michimusic.mobile.devices.aircat.AirCatManifestItem(
                        logicalTrackId = t.trackId,
                        remoteTrackId = t.trackId,
                        title = t.title,
                        artist = t.artist,
                        album = t.album,
                        format = t.format,
                        size = t.size,
                        sha256 = t.checksum ?: "",
                    )
                }


                val aircatManifest = org.michimusic.mobile.devices.aircat.AirCatManifest(
                    manifestVersion = 1,
                    transferId = transferId,
                    sourceDeviceId = targetDeviceId,
                    totalTracks = manifestItems.size,
                    totalBytes = manifestItems.sumOf { it.size },
                    items = manifestItems,
                )


                val intent = org.michimusic.mobile.devices.aircat.TransferIntent(
                    transferId = transferId,
                    sourceMichiId = targetDeviceId,
                    destinationMichiId = identity.michiId,
                    sourceLibraryId = targetDeviceId,
                    destinationLibraryId = destLibId,
                )


                val statFs = try { android.os.StatFs(context.filesDir.absolutePath) } catch (_: Exception) { null }
                val freeBytes = statFs?.availableBytes ?: (10L * 1024 * 1024 * 1024)


                val result = airCat.executeTransfer(
                    intent = intent,
                    manifest = aircatManifest,
                    linkClient = effectiveClient,
                    availableStorageBytes = freeBytes,
                )


                if (result.isSuccess) {
                    onResult(true, "Transferencia AirCat completada (${result.getOrThrow().size} pistas)")
                } else {
                    onResult(false, "Transferencia AirCat falló: ${result.exceptionOrNull()?.message}")
                }
            } catch (e: Exception) {
                onResult(false, "Error en AirCat: ${e.message}")
            }
        }
    }


    fun pairWithQr(qrData: String, pin: String, onResult: (Boolean, String) -> Unit = { _, _ -> }) {
        val parser = org.michimusic.link.identity.QrPairingParser(identity)
        val result = parser.parseAndValidate(qrData)
        if (result.isFailure) {
            onResult(false, result.exceptionOrNull()?.message ?: "El código QR no es válido")
            return
        }


        val canonicalQr = result.getOrThrow()


        viewModelScope.launch {
            try {
                val url = canonicalQr.endpoint
                val client = LinkClient(
                    baseUrl = url,
                    clientDeviceId = identity.michiId
                )
                
                val req = org.michimusic.link.dto.PairConfirmRequestDto(
                    sessionId = canonicalQr.sessionId,
                    pin = pin,
                    michiId = identity.michiId,
                    publicKey = identity.publicKeyBase64Url
                )
                
                client.pairConfirm(req).onSuccess { confirmResp ->
                    val serverInfo = client.getServerInfoWithFallback().getOrNull()
                    val remoteSrvId = serverInfo?.effectiveServerId ?: confirmResp.serverId.ifEmpty { canonicalQr.serverMichiId }
                    val assignedClientId = confirmResp.deviceId.ifEmpty { clientId }


                    val device = org.michimusic.link.PairedDevice(
                        deviceId = remoteSrvId,
                        deviceName = serverInfo?.effectiveName ?: "Servidor Michi",
                        serviceType = serverInfo?.service ?: "",
                        deviceToken = confirmResp.token,
                        refreshToken = confirmResp.refreshToken ?: "",
                        permissions = emptyList(),
                        roles = serverInfo?.roles ?: emptyList(),
                        features = serverInfo?.effectiveFeatures ?: emptyList(),
                        featuresMap = serverInfo?.features ?: emptyMap(),
                        authStrategy = serverInfo?.effectiveAuthStrategy ?: org.michimusic.link.dto.PairingStrategy.SERVER_CODE,
                        tokenRefreshSupported = client.tokenRefreshSupported ?: false,
                        pairedAt = System.currentTimeMillis(),
                        lastUrl = url,
                        serverId = remoteSrvId,
                        michiId = canonicalQr.serverMichiId,
                        publicKey = canonicalQr.serverPublicKey,
                        identityScheme = serverInfo?.identityScheme ?: "ed25519-blake3-v1",
                        remoteServerId = remoteSrvId,
                        remoteMichiId = canonicalQr.serverMichiId,
                        remotePublicKey = canonicalQr.serverPublicKey,
                        pairedClientDeviceId = assignedClientId,
                    )
                    registry.saveDevice(device)
                    client.deviceToken = confirmResp.token
                    client.clientDeviceId = assignedClientId
                    client.pairedClientDeviceId = assignedClientId
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


    fun schedulePeriodicSyncIfEnabled(targetDeviceId: String? = null) {
        val prefs = context.getSharedPreferences("michi_settings", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("auto_sync", false)) return
        val target = targetDeviceId
            ?: uiState.value.pairingConfirm?.deviceId
            ?: uiState.value.connectedPeer?.deviceId
            ?: registry.getAllDevices().firstOrNull { !it.isRevoked }?.effectiveServerId
            ?: return


        val pairedDevice = registry.getDevice(target)
        if (pairedDevice == null || pairedDevice.isRevoked) return


        val baseUrl = currentClient?.baseUrl ?: pairedDevice.lastUrl.ifEmpty { return }
        val pairedClientDeviceId = pairedDevice.effectiveClientDeviceId.ifEmpty { identity.michiId }


        val inputData = SyncWorker.buildInputData(
            baseUrl = baseUrl,
            deviceId = target,
            alias = android.os.Build.MODEL,
            clientDeviceId = pairedClientDeviceId,
        )


        val workRequest = PeriodicWorkRequestBuilder<SyncWorker>(12, TimeUnit.HOURS)
            .setInputData(inputData)
            .build()


        val periodicWorkName = "michi_sync_periodic_${target.replace(Regex("[^A-Za-z0-9_]"), "_")}"
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(periodicWorkName, ExistingPeriodicWorkPolicy.KEEP, workRequest)
    }


    fun cancelSync(deviceId: String? = null, planId: String? = null) {
        if (!planId.isNullOrEmpty()) {
            val safePlan = planId.replace(Regex("[^A-Za-z0-9_]"), "_")
            WorkManager.getInstance(context).cancelUniqueWork("michi_sync_plan_$safePlan")
            WorkManager.getInstance(context).cancelAllWorkByTag("michi_sync_plan_$safePlan")
            _planSyncProgress.value = _planSyncProgress.value - planId
        }
        if (!deviceId.isNullOrEmpty()) {
            val safeDev = deviceId.replace(Regex("[^A-Za-z0-9_]"), "_")
            val workName = "michi_sync_$safeDev"
            WorkManager.getInstance(context).cancelUniqueWork(workName)
            WorkManager.getInstance(context).cancelAllWorkByTag("michi_sync_$safeDev")
        } else if (planId.isNullOrEmpty()) {
            WorkManager.getInstance(context).cancelAllWorkByTag("michi_sync")
        }
        val currentProg = _syncProgress.value
        if (currentProg is SyncProgress.Downloading) {
            val cancellingProg = SyncProgress.Cancelling(
                completed = currentProg.completed,
                total = currentProg.total,
                deviceId = currentProg.deviceId,
            )
            _syncProgress.value = cancellingProg
            if (!deviceId.isNullOrEmpty()) {
                _deviceSyncProgress.value = _deviceSyncProgress.value + (deviceId to cancellingProg)
            }
        } else {
            _syncProgress.value = SyncProgress.Idle
            if (!deviceId.isNullOrEmpty()) {
                _deviceSyncProgress.value = _deviceSyncProgress.value - deviceId
            } else {
                _deviceSyncProgress.value = emptyMap()
            }
        }
    }


    override fun onCleared() {
        super.onCleared()
        currentClient?.close()
    }
}


sealed class SyncProgress {
    data object Idle : SyncProgress()
    data class Downloading(val completed: Int, val total: Int, val deviceId: String = "") : SyncProgress()
    data class Cancelling(val completed: Int, val total: Int, val deviceId: String = "") : SyncProgress()
    data class Complete(
        val tracks: Int,
        val downloaded: Int,
        val errors: Int = 0,
    ) : SyncProgress()
    data class Error(val message: String) : SyncProgress()
}