package org.michimusic.mobile.ui.screens

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddLink
import androidx.compose.material.icons.filled.DesktopWindows
import androidx.compose.material.icons.filled.FolderSpecial
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LaptopMac
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.WifiTethering
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.androidx.compose.koinViewModel
import org.michimusic.core.models.DiscoveredPeer
import org.michimusic.core.models.SyncConnectionState
import org.michimusic.mobile.sync.SyncProgress
import org.michimusic.mobile.sync.SyncViewModel
import org.michimusic.mobile.ui.components.GlassCard
import org.michimusic.mobile.ui.components.ManualConnectionDialog
import org.michimusic.mobile.ui.components.PulsingRadarRing
import org.michimusic.mobile.ui.components.QrScannerDialog
import org.michimusic.mobile.ui.theme.GlassBorderHigh
import org.michimusic.mobile.ui.theme.GlassBorderLow
import org.michimusic.mobile.ui.theme.GlassFillHigh
import org.michimusic.mobile.ui.theme.GlassFillLow
import org.michimusic.mobile.ui.theme.OnSurfaceVariant
import org.michimusic.mobile.ui.theme.PrimaryPink
import org.michimusic.mobile.ui.theme.PrimaryPinkContainer
import org.michimusic.mobile.ui.theme.PureWhite
import org.michimusic.mobile.ui.theme.SurfaceObsidian
import org.michimusic.mobile.ui.theme.TertiaryCyan
import org.michimusic.mobile.ui.theme.TertiaryCyanContainer

@Composable
fun SyncScreen(
    onNavigateToSynced: () -> Unit = {},
    viewModel: SyncViewModel = koinViewModel(),
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val isSearching = uiState.state == SyncConnectionState.DISCOVERING

    var showQrDialog by remember { mutableStateOf(false) }
    var showManualDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        if (uiState.state == SyncConnectionState.DISCONNECTED) {
            viewModel.startDiscovery()
        }
    }

    val refreshTransition = rememberInfiniteTransition(label = "refresh_spin")
    val spinAngle by refreshTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "spin",
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(SurfaceObsidian),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding(),
        ) {
            // Top App Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp)
                    .padding(horizontal = 20.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = onNavigateToSynced,
                    modifier = Modifier.size(40.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.FolderSpecial,
                        contentDescription = "Pistas Sincronizadas",
                        tint = PrimaryPink,
                        modifier = Modifier.size(24.dp),
                    )
                }

                Text(
                    text = "Sincronización",
                    color = PrimaryPink,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.testTag("sync_title"),
                )

                IconButton(
                    onClick = { viewModel.startDiscovery() },
                    modifier = Modifier.size(40.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Refresh,
                        contentDescription = "Refrescar",
                        tint = PrimaryPink,
                        modifier = Modifier
                            .size(24.dp)
                            .then(if (isSearching) Modifier.rotate(spinAngle) else Modifier),
                    )
                }
            }

            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("sync_scroll_list"),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                // Connection Status Search Card
                item {
                    GlassCard(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("sync_search_card"),
                        backgroundColor = GlassFillLow,
                        borderColor = GlassBorderLow,
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            Box(
                                modifier = Modifier.size(48.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                PulsingRadarRing(color = TertiaryCyan, size = 48.dp)
                                Icon(
                                    imageVector = Icons.Filled.WifiTethering,
                                    contentDescription = null,
                                    tint = TertiaryCyan,
                                    modifier = Modifier.size(24.dp),
                                )
                            }

                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = if (isSearching) "Buscando servidor Michi Desktop..." else "Red local preparada",
                                    color = PureWhite,
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = "Asegúrate de que ambos dispositivos estén conectados a la misma red Wi-Fi.",
                                    color = OnSurfaceVariant.copy(alpha = 0.8f),
                                    fontSize = 13.sp,
                                )
                            }
                        }
                    }
                }

                // Pair via QR Card
                item {
                    GlassCard(
                        modifier = Modifier.fillMaxWidth(),
                        backgroundColor = GlassFillLow,
                        borderColor = GlassBorderLow,
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Info,
                                    contentDescription = null,
                                    tint = TertiaryCyan,
                                    modifier = Modifier.size(18.dp),
                                )
                                Text(
                                    text = "EMPAREJAR VÍA QR",
                                    color = TertiaryCyan,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 1.2.sp,
                                )
                            }
                            Text(
                                text = "Escanea el código QR que se muestra en tu aplicación de escritorio Michi Music para sincronizar.",
                                color = OnSurfaceVariant.copy(alpha = 0.8f),
                                fontSize = 13.sp,
                            )
                        }
                    }
                }

                // Scan QR Button
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(
                            text = "SERVIDORES DESCUBIERTOS (${uiState.peers.size})",
                            color = OnSurfaceVariant,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.5.sp,
                            modifier = Modifier.padding(start = 4.dp),
                        )

                        GlassCard(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(56.dp)
                                .testTag("scan_qr_button"),
                            backgroundColor = GlassFillHigh,
                            borderColor = PrimaryPinkContainer.copy(alpha = 0.5f),
                            glowColor = PrimaryPink,
                            onClick = { showQrDialog = true },
                        ) {
                            Row(
                                modifier = Modifier.fillMaxSize(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center,
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.QrCodeScanner,
                                    contentDescription = null,
                                    tint = PrimaryPink,
                                    modifier = Modifier.size(22.dp),
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Text(
                                    text = "Escanear Código QR",
                                    color = PrimaryPink,
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }
                        }
                    }
                }

                // Discovered Devices list
                if (uiState.peers.isNotEmpty()) {
                    items(uiState.peers) { peer ->
                        DiscoveredPeerViewItem(
                            peer = peer,
                            onConnect = { viewModel.selectPeer(peer) },
                        )
                    }
                }

                // Active Sync Progress Card
                val progress = uiState.syncProgress
                if (progress is SyncProgress.Downloading) {
                    item {
                        SyncProgressCard(
                            progress = progress,
                            onCancel = { /* cancel */ },
                        )
                    }
                }

                // Manual Connection Link
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 12.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Row(
                            modifier = Modifier
                                .clickable { showManualDialog = true }
                                .padding(8.dp)
                                .testTag("manual_connection_link"),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Filled.AddLink,
                                contentDescription = null,
                                tint = OnSurfaceVariant,
                                modifier = Modifier.size(18.dp),
                            )
                            Text(
                                text = "Conexión Manual por IP",
                                color = OnSurfaceVariant,
                                fontSize = 13.sp,
                                textDecoration = TextDecoration.Underline,
                            )
                        }
                    }
                }
            }
        }

        if (showQrDialog) {
            QrScannerDialog(
                onScanSuccess = { code ->
                    showQrDialog = false
                    // QR pairing handled via viewmodel if supported
                },
                onDismiss = { showQrDialog = false },
            )
        }

        if (showManualDialog) {
            ManualConnectionDialog(
                onConnect = { name, ipStr ->
                    showManualDialog = false
                    val parts = ipStr.split(":")
                    val host = parts[0]
                    val port = if (parts.size > 1) parts[1].toIntOrNull() ?: 7331 else 7331
                    val manualPeer = DiscoveredPeer(
                        alias = name,
                        ip = host,
                        port = port,
                        authRequired = false,
                    )
                    viewModel.selectPeer(manualPeer)
                },
                onDismiss = { showManualDialog = false },
            )
        }
    }
}

@Composable
private fun DiscoveredPeerViewItem(
    peer: DiscoveredPeer,
    onConnect: () -> Unit,
) {
    GlassCard(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("device_item_${peer.ip}"),
        backgroundColor = GlassFillLow,
        borderColor = GlassBorderLow,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                modifier = Modifier.weight(1f),
            ) {
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(0x1AFFFFFF))
                        .border(1.dp, GlassBorderLow, RoundedCornerShape(10.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = if (peer.alias.contains("Mac", ignoreCase = true)) Icons.Filled.LaptopMac else Icons.Filled.DesktopWindows,
                        contentDescription = null,
                        tint = PureWhite,
                        modifier = Modifier.size(22.dp),
                    )
                }

                Column {
                    Text(
                        text = peer.alias,
                        color = PureWhite,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "${peer.ip}:${peer.port}",
                        color = OnSurfaceVariant.copy(alpha = 0.6f),
                        fontSize = 12.sp,
                    )
                }
            }

            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color(0x1AFF5167))
                    .border(
                        1.dp,
                        PrimaryPinkContainer,
                        RoundedCornerShape(20.dp),
                    )
                    .clickable { onConnect() }
                    .padding(horizontal = 18.dp, vertical = 6.dp),
            ) {
                Text(
                    text = "Conectar",
                    color = PrimaryPink,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

@Composable
private fun SyncProgressCard(
    progress: SyncProgress.Downloading,
    onCancel: () -> Unit,
) {
    val fraction = if (progress.total > 0) {
        (progress.completed.toFloat() / progress.total.toFloat()).coerceIn(0.05f, 1f)
    } else {
        0.05f
    }

    GlassCard(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("sync_progress_card"),
        backgroundColor = GlassFillHigh,
        borderColor = GlassBorderHigh,
        glowColor = PrimaryPink,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom,
            ) {
                Column {
                    Text(
                        text = "Sincronizando Biblioteca...",
                        color = PureWhite,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "Descargando pistas...",
                        color = OnSurfaceVariant,
                        fontSize = 13.sp,
                        maxLines = 1,
                    )
                }

                Text(
                    text = "${(fraction * 100).toInt()}%",
                    color = TertiaryCyan,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                )
            }

            // Progress Bar
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(CircleShape)
                    .background(Color(0x22FFFFFF)),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(fraction = fraction)
                        .height(8.dp)
                        .clip(CircleShape)
                        .background(
                            Brush.horizontalGradient(
                                listOf(PrimaryPink, TertiaryCyan),
                            ),
                        ),
                )
            }

            // Stats footer
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = "${progress.completed} / ${progress.total} canciones transferidas",
                    color = OnSurfaceVariant,
                    fontSize = 11.sp,
                )
                Text(
                    text = "Detener",
                    color = PrimaryPink,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clickable { onCancel() },
                )
            }
        }
    }
}
