package org.michimusic.mobile.ui.screens

import org.michimusic.mobile.ui.components.sameDiscoveredEndpoint
import org.michimusic.mobile.ui.components.matchesObservedPeer
import org.michimusic.mobile.ui.components.parseManualEndpoint
import org.michimusic.mobile.ui.components.syncProgressPresentation
import org.michimusic.mobile.ui.components.allowLegacyReceiverButton
import org.michimusic.mobile.ui.components.UNVERIFIED_RECEIVER_GUIDANCE

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
import org.michimusic.mobile.playback.PlaybackEndpoint
import org.michimusic.mobile.playback.PlaybackSessionManager
import org.michimusic.mobile.sync.DeviceAction
import org.michimusic.mobile.sync.DeviceActionResolver
import org.michimusic.mobile.sync.DeviceActionType
import org.michimusic.mobile.sync.SyncViewModel
import org.michimusic.mobile.ui.components.GlassCard
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
                viewModel.startAirCatTransfer(targetDeviceId = device.id) { success, msg ->
                    scope.launch { snackbarHostState.showSnackbar(msg) }
                }
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
        Column(modifier = Modifier.fillMaxSize()) {
            // Header
            DevicesTopBar(
                isSearching = isSearching,
                onRefreshClick = { viewModel.startDiscovery() },
                onQrClick = { showQrDialog = true },
                onSettingsClick = onNavigateToSettings,
            )

            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = MichiSpacing.screenHorizontal),
                contentPadding = PaddingValues(bottom = 24.dp, top = 8.dp),
                verticalArrangement = Arrangement.spacedBy(MichiSpacing.md),
            ) {
                // Michi Link summary derives all values from real discovered and active states.
                item(key = "michi_link_overview") {
                    AeroInfoPanel(
                        overline = "Michi Link · red local",
                        title = when {
                            isSearching -> "Explorando tu red"
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
                    if (progress.state != org.michimusic.mobile.devices.aircat.AirCatTransferState.COMPLETED) {
                        item {
                            AirCatTransferCard(
                                progress = progress,
                                onPause = { viewModel.pauseAirCatTransfer(progress.transferId) },
                                onResume = { viewModel.resumeAirCatTransfer(progress.transferId) },
                                onCancel = { viewModel.cancelAirCatTransfer(progress.transferId) },
                            )
                        }
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

                // Add Device Actions Row
                item {
                    AddDeviceShortcutsRow(
                        onScanQr = { showQrDialog = true },
                        onManualIp = { showManualDialog = true },
                    )
                }

                // Discovered Nodes Section
                item {
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
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = MichiSpacing.screenHorizontal, vertical = 4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("MICHI LINK", style = MichiTypography.screenEyebrow)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                IconButton(
                    onClick = onRefreshClick,
                    modifier = Modifier
                        .size(MichiSpacing.minTouchTarget)
                        .clip(CircleShape)
                        .background(AeroGraphiteRaised)
                        .border(0.75.dp, if (isSearching) TertiaryCyan else AeroHairline, CircleShape)
                        .testTag("devices_refresh_button"),
                ) {
                    Icon(
                        Icons.Filled.Refresh,
                        contentDescription = "Buscar dispositivos",
                        tint = if (isSearching) TertiaryCyan else PureWhite,
                        modifier = Modifier.size(20.dp),
                    )
                }
                IconButton(
                    onClick = onQrClick,
                    modifier = Modifier
                        .size(MichiSpacing.minTouchTarget)
                        .clip(CircleShape)
                        .background(AeroGraphiteRaised)
                        .border(0.75.dp, AeroHairline, CircleShape)
                        .testTag("devices_qr_button"),
                ) {
                    Icon(
                        Icons.Filled.QrCodeScanner,
                        contentDescription = "Escanear QR",
                        tint = PureWhite,
                        modifier = Modifier.size(20.dp),
                    )
                }
                IconButton(
                    onClick = onSettingsClick,
                    modifier = Modifier
                        .size(MichiSpacing.minTouchTarget)
                        .clip(CircleShape)
                        .background(AeroGraphiteRaised)
                        .border(0.75.dp, AeroHairline, CircleShape)
                        .testTag("devices_settings_button"),
                ) {
                    Icon(
                        Icons.Filled.Settings,
                        contentDescription = "Ajustes",
                        tint = PureWhite,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
        Text("Tus dispositivos", style = MichiTypography.screenTitle,
            modifier = Modifier.padding(start = 4.dp, bottom = 8.dp))
    }
}

@Composable
private fun LocalDeviceCard(
    availableStorageBytes: Long = 0L,
    isActiveOutput: Boolean = true,
) {
    GlassCard(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("local_device_card"),
        backgroundColor = GlassFillLow,
        borderColor = GlassBorderLow,
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
) {
    val presentation = airCatUiPresentation(progress.state.name)
    GlassCard(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("aircat_transfer_card"),
        backgroundColor = SurfaceContainer.copy(alpha = 0.85f),
        borderColor = TertiaryCyan.copy(alpha = 0.3f),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(MichiSpacing.md),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.WifiTethering,
                        contentDescription = null,
                        tint = TertiaryCyan,
                        modifier = Modifier.size(20.dp),
                    )
                    Text(
                        text = "Transferencia AirCat",
                        style = MichiTypography.cardTitle,
                        color = PureWhite,
                    )
                }

                AeroStatusPill(
                    label = presentation.label,
                    accent = when {
                        presentation.isError -> ErrorColor
                        presentation.isTerminal -> TextSecondary
                        else -> TertiaryCyan
                    },
                )
            }

            if (presentation.isError) {
                Text(
                    text = "La transferencia no se completó. Revisa la conexión antes de volver a iniciarla.",
                    style = MichiTypography.metadata,
                    color = ErrorColor,
                )
            }
            Text(
                text = progress.activeTrackTitle.ifBlank { "Archivo de audio" },
                style = MichiTypography.trackTitle,
                color = PureWhite,
                maxLines = 1,
            )

            // Progress bar. A missing total is indeterminate, never zero percent.
            val fraction = if (progress.totalBytes > 0) {
                (progress.bytesTransferred.toFloat() / progress.totalBytes.toFloat()).coerceIn(0f, 1f)
            } else {
                0f
            }

            if (progress.totalBytes > 0L) {
                androidx.compose.material3.LinearProgressIndicator(
                    progress = { fraction },
                    modifier = Modifier.fillMaxWidth().height(6.dp).clip(CircleShape),
                    color = TertiaryCyan,
                    trackColor = SurfaceObsidian,
                )
            } else {
                androidx.compose.material3.LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth().height(6.dp).clip(CircleShape),
                    color = TertiaryCyan,
                    trackColor = SurfaceObsidian,
                )
            }

            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val transferredMb = progress.bytesTransferred / (1024.0 * 1024.0)
                val totalMb = progress.totalBytes / (1024.0 * 1024.0)
                val pct = transferProgressPercent(progress.bytesTransferred, progress.totalBytes)

                Text(
                    text = if (pct != null) {
                        String.format(java.util.Locale.US, "%.1f / %.1f MB (%d%%)", transferredMb, totalMb, pct)
                    } else {
                        String.format(java.util.Locale.US, "%.1f MB transferidos · calculando total", transferredMb)
                    },
                    style = MichiTypography.metadata,
                    color = TextMuted,
                )

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (presentation.showPause) {
                        OutlinedButton(
                            onClick = onPause,
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                            modifier = Modifier.heightIn(min = 48.dp),
                        ) {
                            Text(text = "Pausar", style = MichiTypography.microLabel)
                        }
                    } else if (presentation.showResume) {
                        Button(
                            onClick = onResume,
                            colors = ButtonDefaults.buttonColors(containerColor = TertiaryCyan),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                            modifier = Modifier.heightIn(min = 48.dp),
                        ) {
                            Text(text = "Reanudar", style = MichiTypography.microLabel, color = SurfaceObsidian)
                        }
                    }

                    if (presentation.showCancel) {
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
            if (progress.fraction == null) {
                androidx.compose.material3.LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth().height(6.dp).clip(CircleShape),
                    color = if (isCancelling) ErrorColor else AccentCoral,
                    trackColor = SurfaceObsidian,
                )
            } else {
                androidx.compose.material3.LinearProgressIndicator(
                    progress = { progress.fraction },
                    modifier = Modifier.fillMaxWidth().height(6.dp).clip(CircleShape),
                    color = if (isCancelling) ErrorColor else AccentCoral,
                    trackColor = SurfaceObsidian,
                )
            }

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
        GlassCard(
            modifier = Modifier
                .weight(1f)
                .height(48.dp)
                .testTag("scan_qr_shortcut"),
            onClick = onScanQr,
            backgroundColor = GlassFillLow,
            borderColor = GlassBorderLow,
            shape = MichiShapes.sm,
        ) {
            Row(
                modifier = Modifier.fillMaxSize(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.QrCodeScanner,
                    contentDescription = null,
                    tint = PrimaryPinkContainer,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Escanear QR",
                    color = PureWhite,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }

        GlassCard(
            modifier = Modifier
                .weight(1f)
                .height(48.dp)
                .testTag("manual_ip_shortcut"),
            onClick = onManualIp,
            backgroundColor = GlassFillLow,
            borderColor = GlassBorderLow,
            shape = MichiShapes.sm,
        ) {
            Row(
                modifier = Modifier.fillMaxSize(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.AddLink,
                    contentDescription = null,
                    tint = TertiaryCyan,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Conexión manual",
                    color = PureWhite,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
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
        else -> TextSecondary to "Disponible"
    }
    val (trustColor, trustLabel) = when (item.trustStatus) {
        org.michimusic.mobile.ui.screens.devices.DeviceTrustStatus.TRUSTED -> TertiaryCyan to "Vinculado"
        org.michimusic.mobile.ui.screens.devices.DeviceTrustStatus.REVOKED -> ErrorColor to "Confianza revocada"
        org.michimusic.mobile.ui.screens.devices.DeviceTrustStatus.IDENTITY_MISMATCH -> ErrorColor to "Identidad no coincide"
        else -> TextSecondary to "Identidad no verificada"
    }
    GlassCard(
        modifier = Modifier.fillMaxWidth().testTag("device_node_${item.ip}"),
        backgroundColor = if (connected) GlassFillHigh else GlassFillLow,
        borderColor = GlassBorderLow,
        shape = MichiShapes.md,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(
                    modifier = Modifier.size(48.dp).clip(MichiShapes.sm)
                        .background(connectionColor.copy(alpha = 0.12f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(icon, contentDescription = null, tint = connectionColor,
                        modifier = Modifier.size(24.dp))
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(item.name, style = MichiTypography.cardTitle,
                        maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    val kind = formatDeviceTypeLabel(item.deviceType)
                    Text(kind, style = MichiTypography.metadata, color = TextSecondary,
                        maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                }
                IconButton(
                    onClick = onInfoClick,
                    modifier = Modifier
                        .size(MichiSpacing.minTouchTarget)
                        .clip(CircleShape)
                        .background(AeroGraphiteRaised)
                        .border(0.75.dp, AeroHairline, CircleShape)
                        .testTag("device_info_${item.ip}"),
                ) {
                    Icon(Icons.Filled.Info, contentDescription = "Detalles de ${item.name}",
                        tint = TextSecondary, modifier = Modifier.size(20.dp))
                }
            }
            // Avoid compressing connection and trust badges into the title row.
            Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AeroStatusPill(connectionLabel, connectionColor)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AeroStatusPill(trustLabel, trustColor)
                }
                if (item.capabilities.isNotEmpty()) {
                    Text(item.capabilities.sorted().joinToString(" · "),
                        style = MichiTypography.metadata, color = TextSecondary,
                        maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                }
            }
            // One action per row: long localized labels remain readable at 320dp.
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
                    Text(action.label, color = PureWhite, fontWeight = FontWeight.Bold,
                        fontSize = 14.sp, maxLines = 2)
                }
            }
            // Keep every domain-provided action reachable. M25 displayed only
            // firstOrNull(), silently hiding transfer, remote and other actions.
            (if (showAllActions) item.secondaryActions else item.secondaryActions.take(1))
                .forEach { action ->
                    OutlinedButton(
                        onClick = { onActionClick(action) },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        shape = MichiShapes.sm,
                    ) {
                        Text(action.label,
                            color = if (action.isDestructive) ErrorColor else PureWhite,
                            fontSize = 13.sp, maxLines = 2)
                    }
                }
            if (item.secondaryActions.size > 1) {
                TextButton(
                    onClick = { showAllActions = !showAllActions },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                ) {
                    Text(
                        if (showAllActions) "Mostrar menos" else "Ver ${item.secondaryActions.size - 1} acciones más",
                        color = TertiaryCyan,
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
                    IconButton(onClick = onDismiss, modifier = Modifier.size(MichiSpacing.minTouchTarget)) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = "Cerrar",
                            tint = TextSecondary,
                            modifier = Modifier.size(18.dp),
                        )
                    }
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
    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        backgroundColor = GlassFillLow,
        borderColor = GlassBorderLow,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(MichiSpacing.sm),
        ) {
            Icon(
                imageVector = Icons.Filled.WifiTethering,
                contentDescription = null,
                tint = TextSecondary,
                modifier = Modifier.size(40.dp),
            )
            Text(
                text = if (isSearching) "Buscando dispositivos en tu red Wi-Fi..." else "No se encontraron otros dispositivos",
                style = MichiTypography.cardTitle,
                color = PureWhite,
            )
            Text(
                text = "Asegúrate de que Michi Music Desktop, Micro Server o Stream estén ejecutándose en la misma red local.",
                style = MichiTypography.metadata,
                color = TextSecondary,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
            Spacer(modifier = Modifier.height(MichiSpacing.xs))
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = onSearchAgain,
                    shape = MichiShapes.sm,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                ) {
                    Text("Buscar de nuevo", color = PureWhite, fontSize = 12.sp)
                }
                Button(
                    onClick = onManualConnect,
                    colors = ButtonDefaults.buttonColors(containerColor = PrimaryPinkContainer),
                    shape = MichiShapes.sm,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                ) {
                    Text("Conexión manual", color = PureWhite, fontSize = 13.sp)
                }
            }
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
