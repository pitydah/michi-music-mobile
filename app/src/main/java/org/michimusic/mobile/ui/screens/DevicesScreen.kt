package org.michimusic.mobile.ui.screens

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
import org.michimusic.mobile.ui.components.ManualConnectionDialog
import org.michimusic.mobile.ui.components.PinPairingDialog
import org.michimusic.mobile.ui.components.QrScannerDialog
import org.michimusic.mobile.ui.components.ReceiverButtonPairingDialog
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
    viewModel: SyncViewModel = koinViewModel(),
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val isSearching = uiState.state == SyncConnectionState.DISCOVERING
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    val sessionManager: PlaybackSessionManager = koinInject()

    var showQrDialog by remember { mutableStateOf(false) }
    var showManualDialog by remember { mutableStateOf(false) }
    var pendingQrForPin by remember { mutableStateOf<String?>(null) }
    var pendingPeerForPin by remember { mutableStateOf<Pair<org.michimusic.core.models.DiscoveredPeer, String>?>(null) }
    var pendingPeerForReceiver by remember { mutableStateOf<Pair<org.michimusic.core.models.DiscoveredPeer, String>?>(null) }
    var pendingPeerForPassword by remember { mutableStateOf<Pair<org.michimusic.core.models.DiscoveredPeer, String>?>(null) }
    var selectedDeviceForDetails by remember { mutableStateOf<org.michimusic.core.models.UnifiedDevice?>(null) }

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
                contentPadding = PaddingValues(bottom = 120.dp, top = 8.dp),
                verticalArrangement = Arrangement.spacedBy(MichiSpacing.md),
            ) {
                // Local Phone Card
                item {
                    LocalDeviceCard()
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
                            text = "Dispositivos en tu red local",
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
                    items(uiState.unifiedDevices) { device ->
                        val isConnected = device.connectionState == SyncConnectionState.CONNECTED
                        val isConnecting = device.connectionState == SyncConnectionState.CONNECTING || device.connectionState == SyncConnectionState.DISCOVERING
                        
                        val actions = org.michimusic.mobile.sync.DeviceActionResolver.resolveActions(
                            device = device,
                            isConnecting = isConnecting,
                        )
                        
                        DeviceNodeCard(
                            device = device,
                            isConnected = isConnected,
                            isConnecting = isConnecting,
                            actions = actions,
                            onActionClick = { action ->
                                when (action.type) {
                                    org.michimusic.mobile.sync.DeviceActionType.CONNECT -> {
                                        if (device.isPaired) {
                                            viewModel.connectToDevice(device.id)
                                        } else {
                                            val peer = uiState.peers.find { it.ip == device.ip }
                                            if (peer != null) {
                                                viewModel.preparePairing(peer) { strategy, serverName ->
                                                    when (strategy) {
                                                        org.michimusic.link.dto.PairingStrategy.SERVER_CODE -> {
                                                            pendingPeerForPin = peer to serverName
                                                        }
                                                        org.michimusic.link.dto.PairingStrategy.RECEIVER_BUTTON -> {
                                                            pendingPeerForReceiver = peer to serverName
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
                                        viewModel.syncLibrary()
                                        scope.launch {
                                            snackbarHostState.showSnackbar("Sincronización iniciada con ${device.name}")
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
                                    org.michimusic.mobile.sync.DeviceActionType.VIEW_DETAILS -> selectedDeviceForDetails = device
                                }
                            },
                            onInfoClick = { selectedDeviceForDetails = device },
                        )
                        Spacer(modifier = Modifier.height(MichiSpacing.sm))
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

        // Manual Connection Dialog
        if (showManualDialog) {
            ManualConnectionDialog(
                onConnect = { name, ipStr ->
                    showManualDialog = false
                    val parts = ipStr.split(":")
                    val host = parts[0]
                    val port = if (parts.size > 1) parts[1].toIntOrNull() ?: 53318 else 53318
                    val peer = org.michimusic.core.models.DiscoveredPeer(
                        alias = name.ifBlank { "Michi Node" },
                        ip = host.trim(),
                        port = port,
                    )
                    viewModel.connectManual(name, ipStr) { strategy, serverName ->
                        when (strategy) {
                            org.michimusic.link.dto.PairingStrategy.SERVER_CODE -> pendingPeerForPin = peer to serverName
                            org.michimusic.link.dto.PairingStrategy.RECEIVER_BUTTON -> pendingPeerForReceiver = peer to serverName
                            org.michimusic.link.dto.PairingStrategy.PLAYER_PASSWORD, org.michimusic.link.dto.PairingStrategy.LEGACY -> pendingPeerForPassword = peer to serverName
                            org.michimusic.link.dto.PairingStrategy.ED25519_CHALLENGE -> viewModel.confirmPairing(pin = "", peer = peer)
                        }
                    }
                },
                onDismiss = { showManualDialog = false },
            )
        }

        // Device Details Dialog
        selectedDeviceForDetails?.let { device ->
            DeviceDetailsDialog(
                device = device,
                onDismiss = { selectedDeviceForDetails = null },
                onForget = {
                    viewModel.forgetDevice(device.id)
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
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .padding(horizontal = 20.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column {
            Text(
                text = "MICHI LINK",
                style = MichiTypography.screenEyebrow,
            )
            Text(
                text = "Tus Dispositivos",
                style = MichiTypography.screenTitle,
            )
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(
                onClick = onRefreshClick,
                modifier = Modifier
                    .size(MichiSpacing.minTouchTarget)
                    .clip(CircleShape)
                    .background(GlassFillLow)
                    .testTag("devices_refresh_button"),
            ) {
                Icon(
                    imageVector = Icons.Filled.Refresh,
                    contentDescription = "Buscar Dispositivos",
                    tint = if (isSearching) TertiaryCyan else PureWhite,
                    modifier = Modifier.size(20.dp),
                )
            }

            Spacer(modifier = Modifier.width(6.dp))

            IconButton(
                onClick = onQrClick,
                modifier = Modifier
                    .size(MichiSpacing.minTouchTarget)
                    .clip(CircleShape)
                    .background(GlassFillLow)
                    .testTag("devices_qr_button"),
            ) {
                Icon(
                    imageVector = Icons.Filled.QrCodeScanner,
                    contentDescription = "Escanear QR",
                    tint = PureWhite,
                    modifier = Modifier.size(20.dp),
                )
            }

            Spacer(modifier = Modifier.width(6.dp))

            IconButton(
                onClick = onSettingsClick,
                modifier = Modifier
                    .size(MichiSpacing.minTouchTarget)
                    .clip(CircleShape)
                    .background(GlassFillLow)
                    .testTag("devices_settings_button"),
            ) {
                Icon(
                    imageVector = Icons.Filled.Settings,
                    contentDescription = "Ajustes",
                    tint = PureWhite,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

@Composable
private fun LocalDeviceCard() {
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
                    .size(44.dp)
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
                Text(
                    text = "Reproducción local · ${android.os.Build.MODEL}",
                    style = MichiTypography.metadata,
                )
            }

            Box(
                modifier = Modifier
                    .clip(MichiShapes.pill)
                    .background(TertiaryCyan.copy(alpha = 0.15f))
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            ) {
                Text(
                    text = "● Activo",
                    style = MichiTypography.microLabel,
                    color = TertiaryCyan,
                    fontWeight = FontWeight.Bold,
                )
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
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = ripple(color = PrimaryPinkContainer),
                    onClick = onScanQr,
                )
                .testTag("scan_qr_shortcut"),
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
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = ripple(color = TertiaryCyan),
                    onClick = onManualIp,
                )
                .testTag("manual_ip_shortcut"),
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
private fun DeviceNodeCard(
    device: UnifiedDevice,
    isConnected: Boolean,
    isConnecting: Boolean,
    actions: List<DeviceAction>,
    onActionClick: (DeviceAction) -> Unit,
    onInfoClick: () -> Unit,
) {
    val icon = getNodeIcon(device.deviceType)

    GlassCard(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("device_node_${device.ip}"),
        backgroundColor = if (isConnected) GlassFillHigh else GlassFillLow,
        borderColor = if (isConnected) TertiaryCyan.copy(alpha = 0.4f) else GlassBorderLow,
    ) {
        Column(modifier = Modifier.padding(MichiSpacing.md)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(MichiSpacing.md),
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(MichiShapes.sm)
                        .background(
                            if (isConnected) TertiaryCyan.copy(alpha = 0.15f)
                            else GlassBorderLow,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = if (isConnected) TertiaryCyan else PureWhite,
                        modifier = Modifier.size(24.dp),
                    )
                }

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = device.name.ifEmpty { "Michi Node" },
                        style = MichiTypography.cardTitle,
                    )
                    Text(
                        text = "${formatDeviceTypeLabel(device.deviceType)} · ${device.ip}",
                        style = MichiTypography.metadata,
                        color = if (device.connectionState == SyncConnectionState.OFFLINE) GlassBorderLow else TextSecondary,
                    )
                }

                IconButton(
                    onClick = onInfoClick,
                    modifier = Modifier.size(36.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Info,
                        contentDescription = "Detalles",
                        tint = TextSecondary,
                        modifier = Modifier.size(18.dp),
                    )
                }

                Box(
                    modifier = Modifier
                        .clip(MichiShapes.pill)
                        .background(
                            if (isConnected) TertiaryCyan.copy(alpha = 0.15f)
                            else Color.White.copy(alpha = 0.08f),
                        )
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                ) {
                    Text(
                        text = if (isConnected) "● Conectado" else if (isConnecting) "Conectando..." else "Disponible",
                        style = MichiTypography.microLabel,
                        color = if (isConnected) TertiaryCyan else TextMuted,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }

            Spacer(modifier = Modifier.height(MichiSpacing.sm))

            // Action Buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                actions.forEachIndexed { index, action ->
                    if (index > 0) {
                        Spacer(modifier = Modifier.width(8.dp))
                    }

                    OutlinedButton(
                        onClick = { onActionClick(action) },
                        modifier = Modifier.height(40.dp),
                        shape = MichiShapes.sm,
                    ) {
                        Text(action.label, fontSize = 12.sp)
                    }
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
                    IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
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
                        .height(44.dp),
                    shape = RoundedCornerShape(10.dp),
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
            Row(horizontalArrangement = Arrangement.spacedBy(MichiSpacing.md)) {
                OutlinedButton(
                    onClick = onSearchAgain,
                    shape = MichiShapes.sm,
                    modifier = Modifier.height(44.dp),
                ) {
                    Text("Buscar de nuevo", color = PureWhite, fontSize = 12.sp)
                }
                Button(
                    onClick = onManualConnect,
                    colors = ButtonDefaults.buttonColors(containerColor = PrimaryPinkContainer),
                    shape = MichiShapes.sm,
                    modifier = Modifier.height(44.dp),
                ) {
                    Text("Conexión manual", color = PureWhite, fontSize = 12.sp)
                }
            }
        }
    }
}

private fun getNodeIcon(deviceType: String): ImageVector {
    return when (deviceType.lowercase()) {
        "server" -> Icons.Filled.Dns
        "desktop", "player", "pc" -> Icons.Filled.LaptopMac
        "stream", "receiver", "speaker" -> Icons.Filled.Speaker
        "phone", "mobile" -> Icons.Filled.PhoneAndroid
        else -> Icons.Filled.DesktopWindows
    }
}

private fun formatDeviceTypeLabel(deviceType: String): String {
    return when (deviceType.lowercase()) {
        "server" -> "Micro Server"
        "desktop", "player" -> "Michi Player"
        "stream", "receiver" -> "Michi Stream"
        "phone" -> "Teléfono"
        else -> "Michi Link"
    }
}
