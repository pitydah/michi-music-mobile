package org.michimusic.mobile.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddLink
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.DesktopWindows
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.ErrorOutline
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
import androidx.compose.material3.Text
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel
import org.michimusic.core.models.DiscoveredPeer
import org.michimusic.core.models.SyncConnectionState
import org.michimusic.mobile.sync.SyncViewModel
import org.michimusic.mobile.ui.components.GlassCard
import org.michimusic.mobile.ui.components.ManualConnectionDialog
import org.michimusic.mobile.ui.components.QrScannerDialog
import org.michimusic.mobile.ui.theme.ErrorColor
import org.michimusic.mobile.ui.theme.GlassBorderHigh
import org.michimusic.mobile.ui.theme.GlassBorderLow
import org.michimusic.mobile.ui.theme.GlassFillHigh
import org.michimusic.mobile.ui.theme.GlassFillLow
import org.michimusic.mobile.ui.theme.MichiShapes
import org.michimusic.mobile.ui.theme.MichiSpacing
import org.michimusic.mobile.ui.theme.MichiTypography
import org.michimusic.mobile.ui.theme.OnSurfaceVariant
import org.michimusic.mobile.ui.theme.PrimaryPinkContainer
import org.michimusic.mobile.ui.theme.PureWhite
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

    var showQrDialog by remember { mutableStateOf(false) }
    var showManualDialog by remember { mutableStateOf(false) }

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
                contentPadding = PaddingValues(bottom = 100.dp, top = 8.dp),
                verticalArrangement = Arrangement.spacedBy(MichiSpacing.md),
            ) {
                // Local Phone Card (Current Node)
                item {
                    LocalDeviceCard()
                }

                // Discovered Nodes Section
                item {
                    Spacer(modifier = Modifier.height(MichiSpacing.sm))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "Nodos del Ecosistema Michi",
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

                if (uiState.peers.isEmpty()) {
                    item {
                        EmptyDevicesCard(
                            isSearching = isSearching,
                            onSearchAgain = { viewModel.startDiscovery() },
                            onManualConnect = { showManualDialog = true },
                        )
                    }
                } else {
                    items(uiState.peers) { peer ->
                        val isConnected = uiState.connectedPeer?.ip == peer.ip &&
                            uiState.state == SyncConnectionState.PAIRED
                        val isConnecting = uiState.connectedPeer?.ip == peer.ip &&
                            (uiState.state == SyncConnectionState.CONNECTING || uiState.state == SyncConnectionState.PAIRING)

                        DeviceNodeCard(
                            peer = peer,
                            isConnected = isConnected,
                            isConnecting = isConnecting,
                            onConnect = { viewModel.selectPeer(peer) },
                            onDisconnect = { viewModel.disconnect() },
                            onSync = {
                                viewModel.syncLibrary()
                                scope.launch {
                                    snackbarHostState.showSnackbar("Sincronización iniciada con ${peer.alias}")
                                }
                            },
                        )
                    }
                }

                // Quick Tools
                item {
                    Spacer(modifier = Modifier.height(MichiSpacing.sm))
                    Text(
                        text = "Herramientas de Conexión",
                        style = MichiTypography.sectionTitle,
                    )
                }

                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(MichiSpacing.md),
                    ) {
                        GlassCard(
                            modifier = Modifier
                                .weight(1f)
                                .clickable { showQrDialog = true },
                            backgroundColor = GlassFillLow,
                            borderColor = GlassBorderLow,
                        ) {
                            Column(
                                modifier = Modifier.padding(MichiSpacing.md),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.QrCodeScanner,
                                    contentDescription = null,
                                    tint = TertiaryCyan,
                                    modifier = Modifier.size(24.dp),
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "Escanear QR",
                                    style = MichiTypography.cardTitle,
                                    fontSize = 13.sp,
                                )
                            }
                        }

                        GlassCard(
                            modifier = Modifier
                                .weight(1f)
                                .clickable { showManualDialog = true },
                            backgroundColor = GlassFillLow,
                            borderColor = GlassBorderLow,
                        ) {
                            Column(
                                modifier = Modifier.padding(MichiSpacing.md),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.AddLink,
                                    contentDescription = null,
                                    tint = PrimaryPinkContainer,
                                    modifier = Modifier.size(24.dp),
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "Manual IP",
                                    style = MichiTypography.cardTitle,
                                    fontSize = 13.sp,
                                )
                            }
                        }

                        GlassCard(
                            modifier = Modifier
                                .weight(1f)
                                .clickable { onNavigateToSynced() },
                            backgroundColor = GlassFillLow,
                            borderColor = GlassBorderLow,
                        ) {
                            Column(
                                modifier = Modifier.padding(MichiSpacing.md),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.CloudDownload,
                                    contentDescription = null,
                                    tint = PureWhite,
                                    modifier = Modifier.size(24.dp),
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "Descargas",
                                    style = MichiTypography.cardTitle,
                                    fontSize = 13.sp,
                                )
                            }
                        }
                    }
                }
            }
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter),
        )

        if (showQrDialog) {
            QrScannerDialog(
                onScanSuccess = { token ->
                    viewModel.startDiscovery()
                    scope.launch {
                        snackbarHostState.showSnackbar("Procesando vinculación: $token")
                    }
                },
                onDismiss = { showQrDialog = false },
            )
        }

        if (showManualDialog) {
            ManualConnectionDialog(
                onConnect = { name, ip ->
                    val parts = ip.split(":")
                    val host = parts[0]
                    val port = parts.getOrNull(1)?.toIntOrNull() ?: 7331
                    viewModel.connectManual(name, host, port)
                    scope.launch {
                        snackbarHostState.showSnackbar("Conectando a $name ($host:$port)...")
                    }
                },
                onDismiss = { showManualDialog = false },
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
            .padding(horizontal = MichiSpacing.screenHorizontal, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column {
            Text(
                text = "ECOSISTEMA",
                style = MichiTypography.screenEyebrow,
            )
            Text(
                text = "Dispositivos",
                style = MichiTypography.screenTitle,
            )
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(
                onClick = onRefreshClick,
                modifier = Modifier.size(MichiSpacing.minTouchTarget),
            ) {
                Icon(
                    imageVector = Icons.Filled.Refresh,
                    contentDescription = "Buscar dispositivos",
                    tint = if (isSearching) TertiaryCyan else PureWhite,
                )
            }
            IconButton(
                onClick = onQrClick,
                modifier = Modifier.size(MichiSpacing.minTouchTarget),
            ) {
                Icon(
                    imageVector = Icons.Filled.QrCodeScanner,
                    contentDescription = "Escanear QR",
                    tint = PureWhite,
                )
            }
            IconButton(
                onClick = onSettingsClick,
                modifier = Modifier.size(MichiSpacing.minTouchTarget),
            ) {
                Icon(
                    imageVector = Icons.Filled.Settings,
                    contentDescription = "Ajustes",
                    tint = PureWhite,
                )
            }
        }
    }
}

@Composable
private fun LocalDeviceCard() {
    GlassCard(
        modifier = Modifier.fillMaxWidth(),
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
                    text = "Este teléfono (${android.os.Build.MODEL})",
                    style = MichiTypography.cardTitle,
                )
                Text(
                    text = "Nodo local · Media3 / ExoPlayer",
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
                    text = "Activo",
                    style = MichiTypography.microLabel,
                    color = TertiaryCyan,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

@Composable
private fun DeviceNodeCard(
    peer: DiscoveredPeer,
    isConnected: Boolean,
    isConnecting: Boolean,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onSync: () -> Unit,
) {
    val icon = getNodeIcon(peer.alias, peer.deviceType)

    GlassCard(
        modifier = Modifier.fillMaxWidth(),
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
                        text = peer.alias.ifEmpty { "Michi Node" },
                        style = MichiTypography.cardTitle,
                    )
                    Text(
                        text = "${peer.ip}:${peer.port} · ${peer.deviceType.ifEmpty { "Michi Link" }}",
                        style = MichiTypography.metadata,
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

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (isConnected) {
                    OutlinedButton(
                        onClick = onSync,
                        modifier = Modifier.height(36.dp),
                        shape = MichiShapes.sm,
                    ) {
                        Text("Sincronizar", color = PureWhite, fontSize = 12.sp)
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = onDisconnect,
                        modifier = Modifier.height(36.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = ErrorColor.copy(alpha = 0.2f)),
                        shape = MichiShapes.sm,
                    ) {
                        Text("Desconectar", color = ErrorColor, fontSize = 12.sp)
                    }
                } else {
                    Button(
                        onClick = onConnect,
                        enabled = !isConnecting,
                        modifier = Modifier.height(36.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = PrimaryPinkContainer),
                        shape = MichiShapes.sm,
                    ) {
                        if (isConnecting) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(14.dp),
                                color = PureWhite,
                                strokeWidth = 1.5.dp,
                            )
                        } else {
                            Text("Conectar", color = PureWhite, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
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
                text = if (isSearching) "Buscando nodos Michi en tu red Wi-Fi..." else "No se encontraron otros dispositivos",
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
                ) {
                    Text("Buscar de nuevo", color = PureWhite, fontSize = 12.sp)
                }
                Button(
                    onClick = onManualConnect,
                    colors = ButtonDefaults.buttonColors(containerColor = PrimaryPinkContainer),
                    shape = MichiShapes.sm,
                ) {
                    Text("IP Manual", color = PureWhite, fontSize = 12.sp)
                }
            }
        }
    }
}

private fun getNodeIcon(name: String, model: String): ImageVector {
    val lower = "$name $model".lowercase()
    return when {
        lower.contains("desktop") || lower.contains("linux") || lower.contains("windows") || lower.contains("mac") || lower.contains("player") -> Icons.Filled.LaptopMac
        lower.contains("server") || lower.contains("micro") || lower.contains("big") -> Icons.Filled.Dns
        lower.contains("stream") || lower.contains("living") || lower.contains("room") || lower.contains("speaker") -> Icons.Filled.Speaker
        else -> Icons.Filled.DesktopWindows
    }
}
