package org.michimusic.mobile.ui.screens

import org.michimusic.mobile.ui.components.sameDiscoveredEndpoint
import org.michimusic.mobile.ui.components.matchesObservedPeer
import org.michimusic.mobile.ui.components.parseManualEndpoint
import org.michimusic.mobile.ui.components.syncProgressPresentation
import org.michimusic.mobile.ui.components.allowLegacyReceiverButton
import org.michimusic.mobile.ui.components.UNVERIFIED_RECEIVER_GUIDANCE

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import org.michimusic.mobile.ui.components.premium.MichiIconAction
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddLink
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.DesktopWindows
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LaptopMac
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Speaker
import androidx.compose.material.icons.filled.WifiTethering
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import org.michimusic.core.models.DiscoveredPeer
import org.michimusic.core.models.SyncConnectionState
import org.michimusic.core.models.UnifiedDevice
import org.michimusic.mobile.devices.aircat.AirCatEligibilityPolicy
import org.michimusic.mobile.playback.PlaybackEndpoint
import org.michimusic.mobile.playback.PlaybackSessionManager
import org.michimusic.mobile.sync.DeviceAction
import org.michimusic.mobile.sync.DeviceActionResolver
import org.michimusic.mobile.sync.DeviceActionType
import org.michimusic.mobile.sync.SyncViewModel
import org.michimusic.mobile.ui.components.GlassCard
import org.michimusic.mobile.ui.components.AeroSurface
import org.michimusic.mobile.ui.components.AeroPremiumBackdrop
import org.michimusic.mobile.ui.components.AeroActionButton
import org.michimusic.mobile.ui.components.AeroActionKind
import org.michimusic.mobile.ui.components.AeroEmptyEditorial
import org.michimusic.mobile.ui.components.isProvisionedMichiStream
import org.michimusic.mobile.ui.components.STREAM_PROVISIONING_GUIDANCE
import org.michimusic.mobile.ui.components.airCatUiPresentation
import org.michimusic.mobile.ui.components.AeroInfoPanel
import org.michimusic.mobile.ui.components.AeroStatusPill
import org.michimusic.mobile.ui.components.transferProgressPercent
import org.michimusic.mobile.ui.components.linkOverviewSummary
import org.michimusic.mobile.ui.components.ManualConnectionDialog
import org.michimusic.mobile.ui.components.PinPairingDialog
import org.michimusic.mobile.ui.components.QrScannerDialog
import org.michimusic.mobile.ui.components.ReceiverButtonPairingDialog
import org.michimusic.mobile.ui.theme.AeroFinish
import org.michimusic.mobile.ui.theme.AeroSurfaceRole
import org.michimusic.mobile.ui.theme.LocalVisualPreferences
import org.michimusic.mobile.ui.theme.AeroGraphiteRaised
import org.michimusic.mobile.ui.theme.AeroHairline
import org.michimusic.mobile.ui.theme.AccentCoral
import org.michimusic.mobile.ui.theme.ErrorColor
import org.michimusic.mobile.ui.theme.GlassBorderHigh
import org.michimusic.mobile.ui.theme.GlassBorderLow
import org.michimusic.mobile.ui.theme.GlassFillHigh
import org.michimusic.mobile.ui.theme.GlassFillLow
import org.michimusic.mobile.ui.theme.MichiShapes
import org.michimusic.mobile.ui.theme.MichiSpacing
import org.michimusic.mobile.ui.theme.MichiTypography
import org.michimusic.mobile.ui.theme.PrimaryPinkContainer
import org.michimusic.mobile.ui.theme.PureWhite
import org.michimusic.mobile.ui.theme.SurfaceContainer
import org.michimusic.mobile.ui.theme.SurfaceObsidian
import org.michimusic.mobile.ui.theme.TertiaryCyan
import org.michimusic.mobile.ui.theme.TextMuted
import org.michimusic.mobile.ui.theme.TextSecondary

@Composable
fun DevicesScreen(
    onNavigateToSettings: () -> Unit = {},
    onNavigateToSynced: () -> Unit = {},
    onNavigateToSyncWizard: (deviceId: String) -> Unit = {},
    onNavigateToRemote: () -> Unit = {},
    viewModel: SyncViewModel = koinViewModel(),
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val isSearching = uiState.state == SyncConnectionState.DISCOVERING
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    val sessionManager: PlaybackSessionManager = koinInject()
    val playbackSession by sessionManager.sessionState.collectAsStateWithLifecycle()

    var showQrDialog by remember { mutableStateOf(false) }
    var showManualDialog by remember { mutableStateOf(false) }
    var showUnverifiedReceiverNotice by remember { mutableStateOf(false) }
    var pendingQrForPin by remember { mutableStateOf<String?>(null) }
    var pendingPeerForPin by remember { mutableStateOf<Pair<org.michimusic.core.models.DiscoveredPeer, String>?>(null) }
    var pendingPeerForReceiver by remember { mutableStateOf<Pair<org.michimusic.core.models.DiscoveredPeer, String>?>(null) }
    var pendingPeerForPassword by remember { mutableStateOf<Pair<org.michimusic.core.models.DiscoveredPeer, String>?>(null) }
    var selectedDeviceForDetails by remember { mutableStateOf<org.michimusic.mobile.ui.screens.devices.DeviceUiItem?>(null) }
    var showAirCatTargetPicker by remember { mutableStateOf(false) }
    var airCatPickerForRecovery by remember { mutableStateOf(false) }
    var showNoEligibleAirCatNotice by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        if (uiState.state == SyncConnectionState.DISCONNECTED) {
            viewModel.startDiscovery()
        }
    }

    LaunchedEffect(uiState.error) {
        uiState.error?.let { err ->
            snackbarHostState.showSnackbar(err)
        }
    }

    // Discovery proves presence, pairing proves stored trust: these are different states.
    val activeDevices = uiState.unifiedDevices.filter { device ->
        device.connectionState in setOf(
            SyncConnectionState.CONNECTED, SyncConnectionState.CONNECTING, SyncConnectionState.PAIRING,
        ) || uiState.peers.any { peer ->
            matchesObservedPeer(device.id, device.ip, device.port, peer.deviceId, peer.ip, peer.port)
        }
    }
    val rememberedOfflineDevices = uiState.unifiedDevices.filterNot { it in activeDevices }

    // K48-04: Filter strictly eligible AirCat destinations using AirCatEligibilityPolicy
    val eligibleAirCatDevices = AirCatEligibilityPolicy.filterEligibleDevices(activeDevices)

    // A screen-local remember flag disappears when navigation recreates this screen.
    // Keep the UI flight gate with the *same* ViewModel across composition recreation,
    // and acquire it atomically for the card, picker and per-device actions.
    // Authoritative cross-ViewModel/multi-process locking still belongs in AirCat.
    val airCatGate = remember(viewModel) { AirCatUiGateStore.forViewModel(viewModel) }
    val airCatStartPending = airCatGate.pending
    val eligibleAirCatIds = eligibleAirCatDevices.mapTo(mutableSetOf()) { it.id }
    val performAirCatSafely: (String, Boolean) -> Unit = { targetId, recovery ->
        // A FAILED progress record may remain visible while recovery starts; a
        // running/paused transfer may not be bypassed by the retry affordance.
        val failedProgress = uiState.airCatProgress?.state ==
            org.michimusic.mobile.devices.aircat.AirCatTransferState.FAILED
        val activeTransfer = uiState.airCatProgress != null && !(recovery && failedProgress)
        val allowed = (!recovery || failedProgress) &&
            allowAirCatUiStart(activeTransfer, airCatGate.pending, eligibleAirCatIds, targetId)
        val flightToken = if (allowed) airCatGate.tryAcquire() else null
        if (flightToken == null) {
            if (activeTransfer || airCatGate.pending) {
                scope.launch { snackbarHostState.showSnackbar("Revisa la transferencia AirCat existente antes de iniciar otra") }
            } else {
                showNoEligibleAirCatNotice = true
            }
        } else {
            try {
                val completion: (Boolean, String) -> Unit = { _, message ->
                    airCatGate.release(flightToken)
                    scope.launch { snackbarHostState.showSnackbar(message) }
                }
                if (recovery) viewModel.recoverIncompleteAirCatTransfers(targetId, completion)
                else viewModel.startAirCatTransfer(targetDeviceId = targetId, onResult = completion)
            } catch (_: Exception) {
                airCatGate.release(flightToken)
                scope.launch { snackbarHostState.showSnackbar("No se pudo iniciar la transferencia AirCat") }
            }
        }
    }
    val startAirCatSafely: (String) -> Unit = { performAirCatSafely(it, false) }
    val recoverAirCatSafely: (String) -> Unit = { performAirCatSafely(it, true) }

    val onPerformDeviceAction: (UnifiedDevice, DeviceAction) -> Unit = { device, action ->
        when (action.type) {
            org.michimusic.mobile.sync.DeviceActionType.CONNECT -> {
                if (device.isPaired) {
                    viewModel.connectToDevice(device.id)
                } else {
                    val peer = uiState.peers.firstOrNull { it.deviceId.isNotBlank() && it.deviceId == device.id }
                        ?: uiState.peers.firstOrNull {
                            matchesObservedPeer(device.id, device.ip, device.port, it.deviceId, it.ip, it.port)
                        }
                    if (peer != null) {
                        viewModel.preparePairing(peer) { strategy, serverName ->
                            when (strategy) {
                                org.michimusic.link.dto.PairingStrategy.SERVER_CODE -> {
                                    pendingPeerForPin = peer to serverName
                                }
                                org.michimusic.link.dto.PairingStrategy.RECEIVER_BUTTON -> {
                                    if (isProvisionedMichiStream(peer.deviceType)) {
                                        scope.launch { snackbarHostState.showSnackbar(STREAM_PROVISIONING_GUIDANCE) }
                                    } else {
                                        pendingPeerForReceiver = peer to serverName
                                    }
                                }
                                org.michimusic.link.dto.PairingStrategy.PLAYER_PASSWORD, org.michimusic.link.dto.PairingStrategy.LEGACY -> {
                                    pendingPeerForPassword = peer to serverName
                                }
                                org.michimusic.link.dto.PairingStrategy.ED25519_CHALLENGE -> {
                                    viewModel.confirmPairing(pin = "", peer = peer)
                                }
                            }
                        }
                    }
                }
            }
            org.michimusic.mobile.sync.DeviceActionType.DISCONNECT -> viewModel.disconnectDevice(device.id)
            org.michimusic.mobile.sync.DeviceActionType.SYNC_LIBRARY -> {
                onNavigateToSyncWizard(device.id)
            }
            org.michimusic.mobile.sync.DeviceActionType.AIRCAT_TRANSFER -> {
                startAirCatSafely(device.id)
            }
            org.michimusic.mobile.sync.DeviceActionType.BROWSE_LIBRARY -> onNavigateToSynced()
            org.michimusic.mobile.sync.DeviceActionType.CONTROL_PLAYBACK -> {
                val endpoint = org.michimusic.mobile.playback.PlaybackEndpoint(
                    id = device.id.ifEmpty { "${device.ip}:${device.port}" },
                    name = device.name.ifEmpty { "Michi Node" },
                    type = org.michimusic.mobile.playback.EndpointType.DESKTOP_PLAYER,
                    isLocal = false,
                    isConnected = true,
                )
                sessionManager.attachRemote(endpoint) { success, msg ->
                    scope.launch { snackbarHostState.showSnackbar(msg) }
                    if (success) {
                        onNavigateToRemote()
                    }
                }
            }
            org.michimusic.mobile.sync.DeviceActionType.CONTINUE_PLAYBACK_HERE -> {
                sessionManager.handoffTo(org.michimusic.mobile.playback.PlaybackEndpoint.LocalPhone) { success, msg ->
                    scope.launch { snackbarHostState.showSnackbar(msg) }
                }
            }
            org.michimusic.mobile.sync.DeviceActionType.PLAY_ON_DEVICE -> {
                val endpoint = org.michimusic.mobile.playback.PlaybackEndpoint(
                    id = device.id.ifEmpty { "${device.ip}:${device.port}" },
                    name = device.name.ifEmpty { "Michi Stream" },
                    type = org.michimusic.mobile.playback.EndpointType.STREAM_RECEIVER,
                    isLocal = false,
                    isConnected = true,
                )
                sessionManager.handoffTo(endpoint) { success, msg ->
                    scope.launch { snackbarHostState.showSnackbar(msg) }
                }
            }
            org.michimusic.mobile.sync.DeviceActionType.VIEW_DETAILS -> {
                selectedDeviceForDetails = org.michimusic.mobile.ui.screens.devices.DeviceUiItem.fromUnifiedDevice(device)
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(SurfaceObsidian)
            .statusBarsPadding()
            .testTag("devices_screen"),
    ) {
        AeroPremiumBackdrop(accent = TertiaryCyan)
        Column(modifier = Modifier.fillMaxSize()) {
            // Header
            DevicesTopBar(
                isSearching = isSearching,
                onRefreshClick = { viewModel.startDiscovery() },
                onQrClick = { showQrDialog = true },
                onSettingsClick = onNavigateToSettings,
                onRemoteClick = onNavigateToRemote,
            )

            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = MichiSpacing.screenHorizontal),
                contentPadding = PaddingValues(bottom = 24.dp, top = 8.dp),
                verticalArrangement = Arrangement.spacedBy(MichiSpacing.md),
            ) {
                // M60: AirCat is a first-class Devices action; reachable before the fold.
                // Destination eligibility and actual transfer callbacks remain unchanged.
                item(key = "aircat_sync_shortcut") {
                    GlassCard(
                        modifier = Modifier
                            .fillMaxWidth()
                            .semantics(mergeDescendants = true) {
                                stateDescription = when {
                                    uiState.airCatProgress != null || airCatStartPending -> "Transferencia en curso"
                                    eligibleAirCatDevices.isEmpty() -> "Sin receptores disponibles"
                                    else -> "Disponible para sincronizar"
                                }
                            }
                            .clickable(role = androidx.compose.ui.semantics.Role.Button) {
                                // K47-05: Never navigate with empty string or ineligible device
                                when {
                                    uiState.airCatProgress != null || airCatStartPending -> {
                                        scope.launch { snackbarHostState.showSnackbar("Revisa la transferencia AirCat existente antes de iniciar otra") }
                                    }
                                    eligibleAirCatDevices.isEmpty() -> {
                                        showNoEligibleAirCatNotice = true
                                    }
                                    eligibleAirCatDevices.size == 1 -> {
                                        val target = eligibleAirCatDevices.first()
                                        startAirCatSafely(target.id)
                                    }
                                    else -> {
                                        showAirCatTargetPicker = true
                                    }
                                }
                            }
                            .testTag("aircat_sync_shortcut"),
                        backgroundColor = GlassFillLow,
                        borderColor = GlassBorderLow,
                        shape = MichiShapes.md,
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(TertiaryCyan.copy(alpha = 0.12f)),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.AddLink,
                                    contentDescription = null,
                                    tint = TertiaryCyan,
                                    modifier = Modifier.size(22.dp),
                                )
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "AirCat · sincronización",
                                    style = MichiTypography.cardTitle.copy(fontSize = 15.sp),
                                    color = PureWhite,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Text(
                                    text = when {
                                        uiState.airCatProgress != null || airCatStartPending -> "Consulta el estado de transferencia a continuación"
                                        eligibleAirCatDevices.isEmpty() -> "Sin receptores disponibles · toca para ver cómo conectar"
                                        eligibleAirCatDevices.size == 1 -> "1 receptor listo para sincronizar"
                                        else -> "${eligibleAirCatDevices.size} receptores disponibles"
                                    },
                                    color = TextSecondary,
                                    style = MichiTypography.metadata,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }


                // Michi Link summary derives all values from real discovered and active states.
                if (uiState.unifiedDevices.isNotEmpty()) item(key = "michi_link_overview") {
                    AeroInfoPanel(
                        overline = "Michi Link · red local",
                        title = when {
                            isSearching -> "Actualizando equipos"
                            uiState.unifiedDevices.isEmpty() -> "Tus equipos Michi"
                            else -> "Ecosistema disponible"
                        },
                        detail = linkOverviewSummary(
                            discovered = uiState.peers.size,
                            paired = uiState.unifiedDevices.count { it.isPaired },
                            connected = uiState.unifiedDevices.count { it.connectionState == SyncConnectionState.CONNECTED },
                        ),
                        accent = TertiaryCyan,
                    ) {
                        AeroStatusPill(
                            label = "Salida: ${playbackSession.activeEndpoint.name}",
                            accent = if (playbackSession.activeEndpoint.isLocal) TertiaryCyan else PrimaryPinkContainer,
                        )
                    }
                }

                // Local Phone Card
                item {
                    LocalDeviceCard(availableStorageBytes = uiState.availableStorageBytes, isActiveOutput = playbackSession.activeEndpoint.isLocal)
                }

                // AirCat Active Transfer Card
                uiState.airCatProgress?.let { progress ->
                    item(key = "aircat_transfer_card") {
                        AirCatTransferCard(
                            progress = progress,
                            onPause = { viewModel.pauseAirCatTransfer(progress.transferId) },
                            onResume = { viewModel.resumeAirCatTransfer(progress.transferId) },
                            onCancel = { viewModel.cancelAirCatTransfer(progress.transferId) },
                            onRetry = {
                                if (progress.state == org.michimusic.mobile.devices.aircat.AirCatTransferState.FAILED) {
                                    // transferId is NOT a device ID; require explicit
                                    // source selection because progress stores no source ID.
                                    if (eligibleAirCatDevices.isEmpty()) {
                                        showNoEligibleAirCatNotice = true
                                    } else {
                                        airCatPickerForRecovery = true
                                        showAirCatTargetPicker = true
                                    }
                                } else {
                                    viewModel.resumeAirCatTransfer(progress.transferId)
                                }
                            },
                            onDismiss = { viewModel.clearAirCatProgress() },
                        )
                    }
                }

                // SyncWorker Active Transfer Cards (Multi-Device Support)
                val activeDeviceSyncs = uiState.deviceSyncProgress.filter { (_, prog) ->
                    prog is org.michimusic.mobile.sync.SyncProgress.Downloading ||
                    prog is org.michimusic.mobile.sync.SyncProgress.Cancelling
                }

                if (activeDeviceSyncs.isNotEmpty()) {
                    activeDeviceSyncs.forEach { (devId, prog) ->
                        if (prog is org.michimusic.mobile.sync.SyncProgress.Downloading) {
                            item(key = "sync_card_$devId") {
                                SyncTransferCard(
                                    completed = prog.completed,
                                    total = prog.total,
                                    isCancelling = false,
                                    onCancel = { viewModel.cancelSync(devId) },
                                )
                            }
                        } else if (prog is org.michimusic.mobile.sync.SyncProgress.Cancelling) {
                            item(key = "sync_card_$devId") {
                                SyncTransferCard(
                                    completed = prog.completed,
                                    total = prog.total,
                                    isCancelling = true,
                                    onCancel = {},
                                )
                            }
                        }
                    }
                } else {
                    val syncProg = uiState.syncProgress
                    if (syncProg is org.michimusic.mobile.sync.SyncProgress.Downloading) {
                        item {
                            SyncTransferCard(
                                completed = syncProg.completed,
                                total = syncProg.total,
                                isCancelling = false,
                                onCancel = { viewModel.cancelSync(syncProg.deviceId) },
                            )
                        }
                    } else if (syncProg is org.michimusic.mobile.sync.SyncProgress.Cancelling) {
                        item {
                            SyncTransferCard(
                                completed = syncProg.completed,
                                total = syncProg.total,
                                isCancelling = true,
                                onCancel = {},
                            )
                        }
                    }
                }

                // One discovery area when the network is empty; avoid repeating
                // 'manual connection' beside and inside the empty state.
                if (uiState.unifiedDevices.isNotEmpty()) {
                    item {
                        AddDeviceShortcutsRow(
                            onScanQr = { showQrDialog = true },
                            onManualIp = { showManualDialog = true },
                        )
                    }
                }

                // One empty state handles discovery when no devices exist.
                // Do not repeat "Explorando tu red" in section header and empty card.
                if (uiState.unifiedDevices.isNotEmpty()) item {
                    Spacer(modifier = Modifier.height(MichiSpacing.xs))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "Dispositivos Michi",
                            style = MichiTypography.sectionTitle,
                        )
                        if (isSearching) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(12.dp),
                                    color = TertiaryCyan,
                                    strokeWidth = 1.5.dp,
                                )
                                Text(
                                    text = "Buscando...",
                                    style = MichiTypography.microLabel,
                                    color = TertiaryCyan,
                                )
                            }
                        }
                    }
                }

                if (uiState.unifiedDevices.isEmpty()) {
                    item {
                        EmptyDevicesCard(
                            isSearching = isSearching,
                            onSearchAgain = { viewModel.startDiscovery() },
                            onManualConnect = { showManualDialog = true },
                        )
                    }
                } else {
                    if (activeDevices.isNotEmpty()) {
                        item(key = "discovered_section") {
                            Text("En la red o conectados", style = MichiTypography.microLabel, color = TextSecondary)
                        }
                        items(activeDevices, key = { "active_${it.id}" }) { device ->
                            val item = org.michimusic.mobile.ui.screens.devices.DeviceUiItem.fromUnifiedDevice(
                                device = device,
                                isConnecting = device.connectionState == SyncConnectionState.CONNECTING,
                                isDiscovered = true,
                            )
                            DeviceNodeCard(item, { onPerformDeviceAction(device, it) }, { selectedDeviceForDetails = item })
                        }
                    }
                    if (rememberedOfflineDevices.isNotEmpty()) {
                        item(key = "offline_section") {
                            Text("Guardados · fuera de línea", style = MichiTypography.microLabel, color = TextSecondary)
                        }
                        items(rememberedOfflineDevices, key = { "offline_${it.id}" }) { device ->
                            val item = org.michimusic.mobile.ui.screens.devices.DeviceUiItem.fromUnifiedDevice(
                                device = device, isDiscovered = false,
                            )
                            DeviceNodeCard(item, { onPerformDeviceAction(device, it) }, { selectedDeviceForDetails = item })
                        }
                    }
                }

                item {
                    Spacer(Modifier.height(24.dp))
                }
            }
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 72.dp),
        )

        // QR Scanner Dialog
        if (showQrDialog) {
            QrScannerDialog(
                onScanSuccess = { code ->
                    showQrDialog = false
                    pendingQrForPin = code
                },
                onDismiss = { showQrDialog = false },
            )
        }

        if (pendingQrForPin != null) {
            val qrCode = pendingQrForPin!!
            PinPairingDialog(
                deviceName = "Dispositivo QR",
                onConfirm = { pin ->
                    viewModel.pairWithQr(qrCode, pin) { success, msg ->
                        scope.launch {
                            snackbarHostState.showSnackbar(msg)
                        }
                    }
                    pendingQrForPin = null
                },
                onDismiss = { pendingQrForPin = null },
            )
        }

        if (pendingPeerForPin != null) {
            val (peer, serverName) = pendingPeerForPin!!
            PinPairingDialog(
                deviceName = serverName,
                onConfirm = { pin ->
                    viewModel.confirmPairing(pin = pin, peer = peer)
                    pendingPeerForPin = null
                },
                onDismiss = { pendingPeerForPin = null },
            )
        }

        if (pendingPeerForReceiver != null) {
            val (peer, serverName) = pendingPeerForReceiver!!
            ReceiverButtonPairingDialog(
                deviceName = serverName,
                onConfirm = { pin ->
                    viewModel.confirmPairing(pin = pin, peer = peer)
                    pendingPeerForReceiver = null
                },
                onDismiss = { pendingPeerForReceiver = null },
            )
        }

        if (pendingPeerForPassword != null) {
            val (peer, serverName) = pendingPeerForPassword!!
            org.michimusic.mobile.ui.components.PlayerPasswordPairingDialog(
                deviceName = serverName,
                onConfirm = { user, pass ->
                    viewModel.confirmPairing(username = user, password = pass, peer = peer)
                    pendingPeerForPassword = null
                },
                onDismiss = { pendingPeerForPassword = null },
            )
        }

        if (showUnverifiedReceiverNotice) {
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { showUnverifiedReceiverNotice = false },
                title = { androidx.compose.material3.Text("Receptor no identificado") },
                text = { androidx.compose.material3.Text(UNVERIFIED_RECEIVER_GUIDANCE) },
                confirmButton = {
                    androidx.compose.material3.TextButton(onClick = {
                        showUnverifiedReceiverNotice = false
                        viewModel.startDiscovery()
                    }) { androidx.compose.material3.Text("Buscar dispositivos") }
                },
                dismissButton = {
                    androidx.compose.material3.TextButton(onClick = { showUnverifiedReceiverNotice = false }) {
                        androidx.compose.material3.Text("Cerrar")
                    }
                },
            )
        }

        if (showNoEligibleAirCatNotice) {
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { showNoEligibleAirCatNotice = false },
                title = { androidx.compose.material3.Text("Sin destinos AirCat elegibles") },
                text = {
                    androidx.compose.material3.Text(
                        "No se encontraron servidores o escritorios Michi activos con soporte de biblioteca en la red local. Asegúrate de encender y vincular un servidor Michi compatible."
                    )
                },
                confirmButton = {
                    androidx.compose.material3.TextButton(onClick = {
                        showNoEligibleAirCatNotice = false
                        viewModel.startDiscovery()
                    }) { androidx.compose.material3.Text("Buscar dispositivos") }
                },
                dismissButton = {
                    androidx.compose.material3.TextButton(onClick = { showNoEligibleAirCatNotice = false }) {
                        androidx.compose.material3.Text("Cerrar")
                    }
                },
            )
        }

        if (showAirCatTargetPicker) {
            androidx.compose.material3.AlertDialog(
                onDismissRequest = {
                    showAirCatTargetPicker = false
                    airCatPickerForRecovery = false
                },
                title = { androidx.compose.material3.Text(
                    if (airCatPickerForRecovery) "Recuperar transferencia AirCat" else "Seleccionar destino AirCat"
                ) },
                text = {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        androidx.compose.material3.Text(
                            text = if (airCatPickerForRecovery)
                                "Selecciona el equipo de origen para recuperar la transferencia fallida."
                            else "Selecciona el nodo con el que deseas sincronizar:",
                            style = MichiTypography.metadata,
                            color = TextMuted,
                        )
                        Spacer(Modifier.height(4.dp))
                        eligibleAirCatDevices.forEach { dev ->
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(GlassFillLow)
                                    .border(0.85.dp, GlassBorderHigh.copy(alpha = 0.68f), RoundedCornerShape(12.dp))
                                    .heightIn(min = 56.dp)
                                    .clickable(role = androidx.compose.ui.semantics.Role.Button) {
                                        showAirCatTargetPicker = false
                                        val recovery = airCatPickerForRecovery
                                        airCatPickerForRecovery = false
                                        if (recovery) recoverAirCatSafely(dev.id)
                                        else startAirCatSafely(dev.id)
                                    }
                                    .padding(horizontal = 14.dp, vertical = 12.dp)
                                    .testTag("aircat_target_${dev.id}"),
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    Icon(
                                        imageVector = getNodeIcon(dev.deviceType),
                                        contentDescription = null,
                                        tint = TertiaryCyan,
                                        modifier = Modifier.size(20.dp),
                                    )
                                    Column {
                                        androidx.compose.material3.Text(
                                            text = dev.name.ifEmpty { "Dispositivo Michi" },
                                            style = MichiTypography.cardTitle.copy(fontSize = 14.sp),
                                            color = PureWhite,
                                            fontWeight = FontWeight.SemiBold,
                                        )
                                        androidx.compose.material3.Text(
                                            text = "${formatDeviceTypeLabel(dev.deviceType)} · ${dev.ip}",
                                            style = MichiTypography.metadata,
                                            color = TextMuted,
                                        )
                                    }
                                }
                            }
                        }
                    }
                },
                confirmButton = {},
                dismissButton = {
                    androidx.compose.material3.TextButton(onClick = {
                        showAirCatTargetPicker = false
                        airCatPickerForRecovery = false
                    }) {
                        androidx.compose.material3.Text("Cancelar", color = PureWhite)
                    }
                },
                // Smoked obsidian surface, not the platform's default opaque dialog.
                containerColor = org.michimusic.mobile.ui.theme.aeroGradientStops(
                    org.michimusic.mobile.ui.theme.AeroGradientRole.Inset,
                    LocalVisualPreferences.current.highContrast,
                ).first(),
                titleContentColor = PureWhite,
                textContentColor = PureWhite,
                tonalElevation = 0.dp,
                shape = MichiShapes.lg,
            )
        }

        // Manual Connection Dialog
        if (showManualDialog) {
            ManualConnectionDialog(
                onConnect = { name, ipStr ->
                    showManualDialog = false
                    val endpoint = parseManualEndpoint(ipStr)
                    if (endpoint == null) {
                        showUnverifiedReceiverNotice = true
                    } else {
                    val discovered = uiState.peers.firstOrNull {
                        sameDiscoveredEndpoint(endpoint.host, endpoint.port, it.ip, it.port)
                    }
                    val peer = discovered ?: org.michimusic.core.models.DiscoveredPeer(
                        alias = name.ifBlank { "Michi Node" },
                        ip = endpoint.host,
                        port = endpoint.port,
                    )
                    viewModel.preparePairing(peer) { strategy, serverName ->
                        when (strategy) {
                            org.michimusic.link.dto.PairingStrategy.SERVER_CODE -> pendingPeerForPin = peer to serverName
                            org.michimusic.link.dto.PairingStrategy.RECEIVER_BUTTON -> {
                                when {
                                    isProvisionedMichiStream(peer.deviceType) -> scope.launch { snackbarHostState.showSnackbar(STREAM_PROVISIONING_GUIDANCE) }
                                    !allowLegacyReceiverButton(peer.deviceType, discovered != null) -> showUnverifiedReceiverNotice = true
                                    else -> pendingPeerForReceiver = peer to serverName
                                }
                            }
                            org.michimusic.link.dto.PairingStrategy.PLAYER_PASSWORD, org.michimusic.link.dto.PairingStrategy.LEGACY -> pendingPeerForPassword = peer to serverName
                            org.michimusic.link.dto.PairingStrategy.ED25519_CHALLENGE -> viewModel.confirmPairing(pin = "", peer = peer)
                        }
                    }
                    }
                },
                onDismiss = { showManualDialog = false },
            )
        }

        // Device Details Sheet (M18)
        selectedDeviceForDetails?.let { deviceItem ->
            org.michimusic.mobile.ui.screens.devices.DeviceDetailSheet(
                deviceItem = deviceItem,
                onDismiss = { selectedDeviceForDetails = null },
                onPrimaryAction = { action ->
                    deviceItem.underlyingDevice?.let { dev ->
                        onPerformDeviceAction(dev, action)
                    }
                },
                onSecondaryAction = { action ->
                    deviceItem.underlyingDevice?.let { dev ->
                        onPerformDeviceAction(dev, action)
                    }
                },
                onForgetDevice = {
                    viewModel.forgetDevice(deviceItem.id)
                    selectedDeviceForDetails = null
                    scope.launch {
                        snackbarHostState.showSnackbar("Dispositivo olvidado")
                    }
                },
            )
        }
    }
}

@Composable
private fun DevicesTopBar(
    isSearching: Boolean,
    onRefreshClick: () -> Unit,
    onQrClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onRemoteClick: () -> Unit = {},
) {
    var showMenu by remember { mutableStateOf(false) }
    val configuration = androidx.compose.ui.platform.LocalConfiguration.current
    val compactHeader = configuration.screenWidthDp < 430 || configuration.fontScale > 1.15f

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = MichiSpacing.screenHorizontal, vertical = 6.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Dispositivos",
                    style = MichiTypography.screenTitle.copy(fontSize = if (compactHeader) 25.sp else 28.sp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontWeight = FontWeight.Bold,
                    color = PureWhite,
                )
                Text(
                    text = "Michi Link",
                    style = MichiTypography.metadata.copy(fontSize = 14.sp),
                    color = TertiaryCyan,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (isSearching) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        color = TertiaryCyan,
                        strokeWidth = 2.dp,
                    )
                }
                if (!compactHeader) {
                    MichiIconAction(
                        icon = Icons.Filled.Refresh,
                        contentDescription = "Buscar dispositivos",
                        onClick = onRefreshClick,
                        tint = if (isSearching) TertiaryCyan else PureWhite,
                        iconSize = 22.dp,
                        minTargetSize = MichiSpacing.minTouchTarget,
                        testTag = "devices_refresh_button",
                    )
                    MichiIconAction(
                        icon = Icons.Filled.QrCodeScanner,
                        contentDescription = "Escanear QR",
                        onClick = onQrClick,
                        tint = PureWhite,
                        iconSize = 22.dp,
                        minTargetSize = MichiSpacing.minTouchTarget,
                        testTag = "devices_qr_button",
                    )
                    MichiIconAction(
                        icon = Icons.Filled.Settings,
                        contentDescription = "Ajustes",
                        onClick = onSettingsClick,
                        tint = PureWhite,
                        iconSize = 22.dp,
                        minTargetSize = MichiSpacing.minTouchTarget,
                        testTag = "devices_settings_button",
                    )
                }
                Box {
                    MichiIconAction(
                        icon = Icons.Filled.MoreVert,
                        contentDescription = "Más opciones de dispositivos",
                        onClick = { showMenu = true },
                        tint = PureWhite,
                        iconSize = 22.dp,
                        minTargetSize = MichiSpacing.minTouchTarget,
                        testTag = "devices_more_button",
                    )
                    androidx.compose.material3.DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false },
                    ) {
                        // Compact layout exposes the same actions via overflow.
                        if (compactHeader) {
                            androidx.compose.material3.DropdownMenuItem(
                                text = { Text("Buscar dispositivos") },
                                onClick = { showMenu = false; onRefreshClick() },
                                leadingIcon = { Icon(Icons.Filled.Refresh, null, tint = TertiaryCyan) }
                            )
                            androidx.compose.material3.DropdownMenuItem(
                                text = { Text("Escanear código QR") },
                                onClick = { showMenu = false; onQrClick() },
                                leadingIcon = { Icon(Icons.Filled.QrCodeScanner, null, tint = TertiaryCyan) }
                            )
                            androidx.compose.material3.DropdownMenuItem(
                                text = { Text("Ajustes de conexión") },
                                onClick = { showMenu = false; onSettingsClick() },
                                leadingIcon = { Icon(Icons.Filled.Settings, null, tint = TertiaryCyan) }
                            )
                        }
                        androidx.compose.material3.DropdownMenuItem(
                            text = { Text("Control Remoto") },
                            onClick = { showMenu = false; onRemoteClick() },
                            leadingIcon = { Icon(Icons.Filled.WifiTethering, null, tint = TertiaryCyan) },
                            modifier = Modifier.testTag("menu_remote_control"),
                        )
                    }
                }
            }
        }

    }
}

@Composable
private fun LocalDeviceCard(
    availableStorageBytes: Long = 0L,
    isActiveOutput: Boolean = true,
) {
    // The phone is the physical reference device: one restrained
    // anodized plate; status and transport icons remain unchanged.
    AeroSurface(
        role = AeroSurfaceRole.Raised,
        finish = AeroFinish.Anodized,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("local_device_card"),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(MichiSpacing.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MichiSpacing.md),
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(MichiShapes.sm)
                    .background(PrimaryPinkContainer.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.PhoneAndroid,
                    contentDescription = null,
                    tint = PrimaryPinkContainer,
                    modifier = Modifier.size(24.dp),
                )
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Este teléfono",
                    style = MichiTypography.cardTitle,
                )
                val storageStr = if (availableStorageBytes > 0) {
                    val gb = availableStorageBytes / (1024.0 * 1024.0 * 1024.0)
                    String.format(java.util.Locale.US, "%.1f GB libres", gb)
                } else {
                    ""
                }
                val subtext = buildString {
                    append("Reproducción en este teléfono")
                    if (storageStr.isNotEmpty()) {
                        append(" · ")
                        append(storageStr)
                    }
                }
                Text(
                    text = subtext,
                    style = MichiTypography.metadata,
                )
            }

            Box(
                modifier = Modifier
                    .clip(MichiShapes.pill)
                    .background((if (isActiveOutput) TertiaryCyan else TextSecondary).copy(alpha = 0.12f))
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            ) {
                Text(
                    text = if (isActiveOutput) "● Salida local" else "Disponible",
                    style = MichiTypography.microLabel,
                    color = if (isActiveOutput) TertiaryCyan else TextSecondary,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

@Composable
private fun AirCatTransferCard(
    progress: org.michimusic.mobile.devices.aircat.AirCatProgressEvent,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit,
    onRetry: () -> Unit = onResume,
    onDismiss: () -> Unit = onCancel,
) {
    org.michimusic.mobile.ui.screens.sync.AirCatTransferSafeLayout(
        progress = progress,
        onPause = onPause,
        onResume = onResume,
        onCancel = onCancel,
        onRetry = onRetry,
        onDismiss = onDismiss,
    )
}

@Composable
private fun SyncTransferCard(
    completed: Int,
    total: Int,
    isCancelling: Boolean = false,
    onCancel: () -> Unit = {},
) {
    GlassCard(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("sync_transfer_card"),
        backgroundColor = SurfaceContainer.copy(alpha = 0.85f),
        borderColor = AccentCoral.copy(alpha = 0.3f),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(MichiSpacing.md),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Refresh,
                        contentDescription = null,
                        tint = if (isCancelling) ErrorColor else AccentCoral,
                        modifier = Modifier.size(20.dp),
                    )
                    Text(
                        text = if (isCancelling) "Cancelando sincronización" else "Sincronización en curso",
                        style = MichiTypography.cardTitle,
                        color = PureWhite,
                    )
                }

                Text(
                    text = if (isCancelling) "Cancelando..." else "Descargando...",
                    style = MichiTypography.microLabel,
                    color = if (isCancelling) ErrorColor else AccentCoral,
                    fontWeight = FontWeight.Bold,
                )
            }

            val progress = syncProgressPresentation(completed, total)
            org.michimusic.mobile.ui.motion.AeroTruthfulProgress(
                fraction = progress.fraction,
                modifier = Modifier.fillMaxWidth().height(6.dp).clip(CircleShape),
                color = if (isCancelling) ErrorColor else AccentCoral,
                trackColor = SurfaceObsidian,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = progress.detail,
                    style = MichiTypography.metadata,
                    color = TextMuted,
                )

                if (!isCancelling) {
                    OutlinedButton(
                        onClick = onCancel,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) {
                        Text(text = "Cancelar", style = MichiTypography.microLabel, color = ErrorColor)
                    }
                }
            }
        }
    }
}


@Composable
private fun AddDeviceShortcutsRow(
    onScanQr: () -> Unit,
    onManualIp: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(MichiSpacing.md),
    ) {
        AeroActionButton(label = "Escanear QR", icon = Icons.Filled.QrCodeScanner,
            onClick = onScanQr, kind = AeroActionKind.Secondary,
            modifier = Modifier.weight(1f), testTag = "scan_qr_shortcut")

        AeroActionButton(label = "Conexión manual", icon = Icons.Filled.AddLink,
            onClick = onManualIp, kind = AeroActionKind.Quiet,
            modifier = Modifier.weight(1f), testTag = "manual_ip_shortcut")
    }
}

@Composable
internal fun DeviceNodeCard(
    item: org.michimusic.mobile.ui.screens.devices.DeviceUiItem,
    onActionClick: (DeviceAction) -> Unit,
    onInfoClick: () -> Unit,
) {
    val connected = item.connectionStatus == org.michimusic.mobile.ui.screens.devices.DeviceConnectionStatus.CONNECTED
    var showAllActions by rememberSaveable(item.id) { mutableStateOf(false) }
    val icon = getNodeIcon(item.deviceType)
    val (connectionColor, connectionLabel) = when (item.connectionStatus) {
        org.michimusic.mobile.ui.screens.devices.DeviceConnectionStatus.CONNECTED -> TertiaryCyan to "Conectado"
        org.michimusic.mobile.ui.screens.devices.DeviceConnectionStatus.CONNECTING -> PrimaryPinkContainer to "Conectando"
        org.michimusic.mobile.ui.screens.devices.DeviceConnectionStatus.OFFLINE -> TextSecondary to "Sin conexión"
        org.michimusic.mobile.ui.screens.devices.DeviceConnectionStatus.ERROR -> ErrorColor to "Error"
        else -> Color(0xFF4CAF50) to "Disponible"
    }
    val (trustColor, trustLabel) = when (item.trustStatus) {
        org.michimusic.mobile.ui.screens.devices.DeviceTrustStatus.TRUSTED -> TertiaryCyan to "Vinculado"
        org.michimusic.mobile.ui.screens.devices.DeviceTrustStatus.REVOKED -> ErrorColor to "Confianza revocada"
        org.michimusic.mobile.ui.screens.devices.DeviceTrustStatus.IDENTITY_MISMATCH -> ErrorColor to "Identidad no coincide"
        else -> Color(0xFFE5A93C) to "Identidad no verificada"
    }

    val iconBoxColor = when {
        connected -> TertiaryCyan
        item.trustStatus != org.michimusic.mobile.ui.screens.devices.DeviceTrustStatus.TRUSTED -> Color(0xFFE5A93C)
        else -> Color(0xFF4CAF50)
    }

    // Prominence is reserved for a connected unit. Discovered != paired != active.
    AeroSurface(
        role = AeroSurfaceRole.Raised,
        finish = if (connected) AeroFinish.Anodized else AeroFinish.Plain,
        modifier = Modifier.fillMaxWidth().testTag("device_node_${item.ip}"),
        borderStroke = BorderStroke(
            width = if (LocalVisualPreferences.current.highContrast) 1.2.dp else 0.75.dp,
            color = if (LocalVisualPreferences.current.highContrast) Color.White.copy(alpha = 0.85f)
                else if (connected) TertiaryCyan.copy(alpha = 0.35f)
                else GlassBorderLow,
        ),
        shape = MichiShapes.md,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(iconBoxColor.copy(alpha = 0.12f))
                        .border(1.dp, iconBoxColor.copy(alpha = 0.25f), RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        icon,
                        contentDescription = null,
                        tint = iconBoxColor,
                        modifier = Modifier.size(24.dp),
                    )
                }
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(
                        item.name,
                        style = MichiTypography.cardTitle.copy(fontSize = 16.sp),
                        fontWeight = FontWeight.Bold,
                        color = PureWhite,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        AeroStatusPill(connectionLabel, connectionColor)
                        AeroStatusPill(trustLabel, trustColor)
                    }
                    val kind = formatDeviceTypeLabel(item.deviceType)
                    Text(
                        kind,
                        style = MichiTypography.metadata,
                        color = TextSecondary,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    )
                    if (item.capabilities.isNotEmpty()) {
                        Text(
                            item.capabilities.sorted().joinToString(" · "),
                            style = MichiTypography.metadata,
                            color = TextSecondary,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        )
                    }
                }
                MichiIconAction(
                    icon = Icons.Filled.MoreVert,
                    contentDescription = "Detalles de ${item.name}",
                    onClick = onInfoClick,
                    tint = PureWhite,
                    iconSize = 22.dp,
                    minTargetSize = MichiSpacing.minTouchTarget,
                    testTag = "device_info_${item.ip}",
                )
            }

            // Primary action CTA button
            item.primaryAction?.let { action ->
                Button(
                    onClick = { onActionClick(action) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        .testTag("device_primary_action_${item.ip}"),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (action.isDestructive) ErrorColor else PrimaryPinkContainer,
                    ),
                    shape = MichiShapes.sm,
                ) {
                    Text(
                        action.label,
                        color = PureWhite,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        maxLines = 1,
                    )
                }
            }

            // Secondary actions if expanded
            if (showAllActions) {
                item.secondaryActions.forEach { action ->
                    OutlinedButton(
                        onClick = { onActionClick(action) },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        shape = MichiShapes.sm,
                    ) {
                        Text(
                            action.label,
                            color = if (action.isDestructive) ErrorColor else PureWhite,
                            fontSize = 13.sp,
                            maxLines = 1,
                        )
                    }
                }
            }

            if (item.secondaryActions.isNotEmpty()) {
                TextButton(
                    onClick = { showAllActions = !showAllActions },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 40.dp),
                ) {
                    Text(
                        if (showAllActions) "Mostrar menos" else "Ver ${item.secondaryActions.size} acciones más",
                        color = TertiaryCyan,
                        fontSize = 12.sp,
                    )
                }
            }
        }
    }
}

@Composable
private fun DeviceDetailsDialog(
    device: UnifiedDevice,
    onDismiss: () -> Unit,
    onForget: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        GlassCard(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            backgroundColor = SurfaceContainer,
            borderColor = GlassBorderHigh,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Detalles del Dispositivo",
                        color = PureWhite,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    MichiIconAction(
                        icon = Icons.Filled.Close,
                        contentDescription = "Cerrar",
                        onClick = onDismiss,
                        tint = TextSecondary,
                        iconSize = 18.dp,
                        minTargetSize = MichiSpacing.minTouchTarget,
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                DetailItem(label = "Alias", value = device.name.ifEmpty { "Michi Node" })
                DetailItem(label = "Tipo de nodo", value = formatDeviceTypeLabel(device.deviceType))
                DetailItem(label = "Dirección IP", value = device.ip)
                DetailItem(label = "Puerto", value = device.port.toString())
                if (device.id.isNotEmpty()) {
                    DetailItem(label = "ID de dispositivo", value = device.id)
                }

                Spacer(modifier = Modifier.height(18.dp))

                OutlinedButton(
                    onClick = onForget,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = MichiSpacing.minTouchTarget),
                    shape = MichiShapes.sm,
                ) {
                    Icon(
                        imageVector = Icons.Filled.DeleteOutline,
                        contentDescription = null,
                        tint = ErrorColor,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Olvidar este dispositivo", color = ErrorColor, fontSize = 13.sp)
                }
            }
        }
    }
}

@Composable
private fun DetailItem(label: String, value: String) {
    Column(modifier = Modifier.padding(vertical = 4.dp)) {
        Text(text = label, color = TextMuted, fontSize = 11.sp)
        Text(text = value, color = PureWhite, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun EmptyDevicesCard(
    isSearching: Boolean,
    onSearchAgain: () -> Unit,
    onManualConnect: () -> Unit,
) {
    AeroEmptyEditorial(
        eyebrow = "MICHI LINK · RED LOCAL",
        title = if (isSearching) "Explorando tu red" else "Conecta tu ecosistema",
        description = if (isSearching)
            "Buscamos equipos Michi cercanos. Desktop, Micro Server y Stream deben compartir la misma red local."
        else "No hay otros equipos disponibles. Puedes reintentar la búsqueda o conectarte mediante una dirección IP.",
        icon = Icons.Filled.WifiTethering,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(top = 9.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            AeroActionButton(
                label = if (isSearching) "Explorando..." else "Buscar dispositivos",
                onClick = onSearchAgain,
                enabled = !isSearching,
                kind = AeroActionKind.Primary,
                modifier = Modifier.fillMaxWidth(),
                icon = Icons.Filled.Refresh,
            )
            AeroActionButton(
                label = "Conexión manual por IP",
                onClick = onManualConnect,
                kind = AeroActionKind.Secondary,
                modifier = Modifier.fillMaxWidth(),
                icon = Icons.Filled.AddLink,
            )
        }
    }
}

private fun getNodeIcon(deviceType: String): ImageVector {
    if (isProvisionedMichiStream(deviceType)) return Icons.Filled.Speaker
    return when (deviceType.lowercase()) {
        "server" -> Icons.Filled.Dns
        "desktop", "player", "pc" -> Icons.Filled.LaptopMac
        "stream", "receiver", "speaker" -> Icons.Filled.Speaker
        "phone", "mobile" -> Icons.Filled.PhoneAndroid
        else -> Icons.Filled.DesktopWindows
    }
}

private fun formatDeviceTypeLabel(deviceType: String): String {
    if (isProvisionedMichiStream(deviceType)) return "Michi Stream"
    return when (deviceType.lowercase()) {
        "server" -> "Micro Server"
        "desktop", "player" -> "Michi Player"
        "stream", "receiver" -> "Michi Stream"
        "phone" -> "Teléfono"
        else -> "Michi Link"
    }
}

/** Does not trust stale UI destinations or allow a concurrent AirCat start. */
internal fun allowAirCatUiStart(
    activeTransfer: Boolean,
    startPending: Boolean,
    eligibleDeviceIds: Set<String>,
    targetId: String,
): Boolean = !activeTransfer && !startPending && targetId.isNotBlank() && targetId in eligibleDeviceIds


/**
 * Single-flight gate retained by the owning ViewModel. Tokens prevent an old
 * callback (or a duplicate callback) from unlocking a later transfer (ABA).
 * This is still not a cross-ViewModel or cross-process AirCat transaction lock.
 */
internal class AirCatUiStartGate {
    var pending by mutableStateOf(false)
        private set
    private var generation = 0L

    @Synchronized
    fun tryAcquire(): Long? {
        if (pending) return null
        generation += 1
        pending = true
        return generation
    }

    @Synchronized
    fun release(token: Long) {
        if (pending && generation == token) pending = false
    }
}

private object AirCatUiGateStore {
    // The value never references its key: destroyed ViewModels can be collected.
    private val gates = java.util.WeakHashMap<SyncViewModel, AirCatUiStartGate>()

    fun forViewModel(viewModel: SyncViewModel): AirCatUiStartGate =
        synchronized(gates) { gates.getOrPut(viewModel) { AirCatUiStartGate() } }
}
