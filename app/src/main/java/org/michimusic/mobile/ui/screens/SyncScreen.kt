@file:Suppress("DEPRECATION")
package org.michimusic.mobile.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Lan
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.androidx.compose.koinViewModel
import org.michimusic.core.models.DiscoveredPeer
import org.michimusic.core.models.SyncConnectionState
import org.michimusic.mobile.sync.SyncProgress
import org.michimusic.mobile.sync.SyncUiState
import org.michimusic.mobile.sync.SyncViewModel
import org.michimusic.mobile.ui.theme.AccentCoral
import org.michimusic.mobile.ui.theme.AccentPink
import org.michimusic.mobile.ui.theme.SurfaceDark
import org.michimusic.mobile.ui.theme.SurfaceElevated
import org.michimusic.mobile.ui.theme.TextDim
import org.michimusic.mobile.ui.theme.TextMuted
import org.michimusic.mobile.ui.theme.TextPrimary
import org.michimusic.mobile.ui.theme.TextSecondary

@Composable
fun SyncScreen(
    onNavigateToSynced: () -> Unit = {},
    viewModel: SyncViewModel = koinViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        if (uiState.state == SyncConnectionState.DISCONNECTED) {
            viewModel.startDiscovery()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            when (uiState.state) {
                SyncConnectionState.DISCONNECTED -> {
                    ConnectionPrompt(onStart = viewModel::startDiscovery)
                }

                SyncConnectionState.DISCOVERING -> {
                    DiscoveringState(
                        peers = uiState.peers,
                        onSelect = viewModel::selectPeer,
                        onStop = viewModel::stopDiscovery,
                    )
                }

                SyncConnectionState.PAIRING_REQUIRED -> {
                    PairingForm(
                        peer = uiState.connectedPeer,
                        onPair = { username, password ->
                            uiState.connectedPeer?.let { peer ->
                                viewModel.startPairing(peer, username, password)
                            }
                        },
                        onBack = viewModel::disconnect,
                    )
                }

                SyncConnectionState.PAIRING -> {
                    ConnectingState("Emparejando...")
                }

                SyncConnectionState.PAIRED -> {
                    ConnectedState(
                        peer = uiState.connectedPeer,
                        pairingConfirm = uiState.pairingConfirm,
                        syncProgress = uiState.syncProgress,
                        onSync = viewModel::syncLibrary,
                        onDisconnect = viewModel::disconnect,
                        onForget = viewModel::forgetServer,
                        onNavigateToSynced = onNavigateToSynced,
                    )
                }

                SyncConnectionState.CONNECTING -> {
                    ConnectingState("Conectando...")
                }

                SyncConnectionState.CONNECTED -> {
                    ConnectedState(
                        peer = uiState.connectedPeer,
                        pairingConfirm = null,
                        syncProgress = uiState.syncProgress,
                        onSync = viewModel::syncLibrary,
                        onDisconnect = viewModel::disconnect,
                        onForget = viewModel::forgetServer,
                        onNavigateToSynced = onNavigateToSynced,
                    )
                }

                SyncConnectionState.AUTH_ERROR -> {
                    ErrorState(
                        message = "Error de autenticación. Intenta emparejar de nuevo.",
                        onRetry = viewModel::disconnect,
                    )
                }

                SyncConnectionState.REVOKED -> {
                    ErrorState(
                        message = "Dispositivo revocado desde Michi Music Player. Olvida el servidor y empareja de nuevo.",
                        onRetry = viewModel::forgetServer,
                    )
                }

                SyncConnectionState.ERROR -> {
                    ErrorState(
                        message = uiState.error ?: "Error desconocido",
                        onRetry = {
                            viewModel.clearError()
                            viewModel.startDiscovery()
                        },
                    )
                }
            }
        }

        uiState.error?.let { msg ->
            if (uiState.state != SyncConnectionState.PAIRING_REQUIRED) {
                Snackbar(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(16.dp),
                    action = {
                        TextButton(onClick = viewModel::clearError) {
                            Text("OK")
                        }
                    },
                ) {
                    Text(msg)
                }
            }
        }
    }
}

@Composable
private fun ConnectionPrompt(onStart: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = Icons.Default.Lan,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = AccentPink,
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = "Sincronización",
            style = MaterialTheme.typography.headlineLarge,
            color = TextPrimary,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Conecta con Michi Music Player en tu red local",
            style = MaterialTheme.typography.bodyLarge,
            color = TextSecondary,
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onStart) {
            Icon(Icons.Default.Sync, contentDescription = null)
            Spacer(Modifier.size(8.dp))
            Text("Buscar servidores")
        }
    }
}

@Composable
private fun DiscoveringState(
    peers: List<DiscoveredPeer>,
    onSelect: (DiscoveredPeer) -> Unit,
    onStop: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Servidores encontrados",
                style = MaterialTheme.typography.titleLarge,
            )
            OutlinedButton(onClick = onStop) {
                Text("Detener")
            }
        }

        Spacer(Modifier.height(16.dp))

        if (peers.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(16.dp))
                    Text(
                        "Buscando servidores...",
                        style = MaterialTheme.typography.bodyLarge,
                        color = TextSecondary,
                    )
                }
            }
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(peers) { peer ->
                    PeerCard(
                        peer = peer,
                        authRequired = peer.authRequired,
                        onClick = { onSelect(peer) },
                    )
                }
            }
        }
    }
}

@Composable
private fun PeerCard(
    peer: DiscoveredPeer,
    authRequired: Boolean,
    onClick: () -> Unit,
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = SurfaceElevated,
        ),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = if (authRequired) Icons.Default.Lock else Icons.Default.Devices,
                contentDescription = null,
                tint = if (authRequired) AccentCoral else AccentPink,
            )
            Spacer(Modifier.size(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = peer.alias,
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = "${peer.ip}:${peer.port}",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                )
            }
            if (authRequired) {
                Text(
                    text = "Requiere emparejamiento",
                    style = MaterialTheme.typography.labelSmall,
                    color = AccentCoral,
                )
            }
        }
    }
}

@Composable
private fun PairingForm(
    peer: DiscoveredPeer?,
    onPair: (String, String) -> Unit,
    onBack: () -> Unit,
) {
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }

    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = Icons.Default.Lock,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = AccentPink,
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = "Emparejar dispositivo",
            style = MaterialTheme.typography.headlineMedium,
        )
        if (peer != null) {
            Text(
                text = "Conectar a ${peer.alias} (${peer.ip})",
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary,
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Ingresa usuario y contraseña local del servidor Michi",
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary,
        )

        Spacer(Modifier.height(24.dp))

        OutlinedTextField(
            value = username,
            onValueChange = { username = it },
            label = { Text("Usuario") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("Contraseña") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
        )
        Spacer(Modifier.height(24.dp))
        Button(
            onClick = { onPair(username, password) },
            modifier = Modifier.fillMaxWidth(),
            enabled = username.isNotBlank() && password.isNotBlank(),
        ) {
            Text("Emparejar")
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = onBack) {
            Text("Cancelar")
        }
    }
}

@Composable
private fun ConnectingState(message: String) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Spacer(Modifier.height(16.dp))
            Text(
                message,
                style = MaterialTheme.typography.bodyLarge,
            )
        }
    }
}

@Composable
private fun ConnectedState(
    peer: DiscoveredPeer?,
    pairingConfirm: org.michimusic.link.dto.PairConfirmResponseDto?,
    syncProgress: SyncProgress,
    onSync: () -> Unit,
    onDisconnect: () -> Unit,
    onForget: () -> Unit,
    onNavigateToSynced: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Default.CheckCircle,
                contentDescription = null,
                tint = AccentPink,
                modifier = Modifier.size(24.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = if (pairingConfirm != null) "Emparejado y autorizado" else "Conectado",
                style = MaterialTheme.typography.headlineMedium,
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = peer?.let { "${it.alias} (${it.ip})" } ?: "Servidor",
            style = MaterialTheme.typography.bodyLarge,
            color = TextSecondary,
        )

        pairingConfirm?.let { confirm ->
            if (confirm.permissions.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Permisos: ${confirm.permissions.joinToString(", ")}",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                )
            }
        }

        pairingConfirm?.let { confirm ->
            Spacer(Modifier.height(16.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = SurfaceElevated,
                ),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        "Permisos: ${confirm.permissions.joinToString(", ").ifEmpty { "Ninguno" }}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        "Servidor: ${confirm.serverDeviceId.take(8)}...",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary,
                    )
                }
            }
        }

        Spacer(Modifier.height(24.dp))

        when (syncProgress) {
            is SyncProgress.Idle -> {
                Button(
                    onClick = onSync,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Default.Sync, contentDescription = null)
                    Spacer(Modifier.size(8.dp))
                    Text("Sincronizar biblioteca")
                }
            }

            is SyncProgress.Downloading -> {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = SurfaceElevated,
                    ),
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            "Descargando biblioteca...",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Spacer(Modifier.height(8.dp))
                        LinearProgressIndicator(
                            progress = {
                                if (syncProgress.total > 0) {
                                    syncProgress.completed.toFloat() / syncProgress.total.toFloat()
                                } else 0f
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "${syncProgress.completed} / ${syncProgress.total} canciones",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary,
                        )
                    }
                }
            }

            is SyncProgress.Complete -> {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = AccentPink.copy(alpha = 0.15f),
                    ),
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            "Sincronización completa",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text("${syncProgress.tracks} canciones en biblioteca")
                        Text("${syncProgress.downloaded} descargadas")
                        if (syncProgress.errors > 0) {
                            Text(
                                "${syncProgress.errors} errores",
                                color = AccentCoral,
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(onClick = onNavigateToSynced) {
                            Icon(Icons.Default.List, contentDescription = null)
                            Spacer(Modifier.size(4.dp))
                            Text("Ver biblioteca")
                        }
                    }
                }
            }

            is SyncProgress.Error -> {
                Text(
                    syncProgress.message,
                    color = AccentCoral,
                )
            }
        }

        Spacer(Modifier.height(16.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(
                onClick = onDisconnect,
                modifier = Modifier.weight(1f),
            ) {
                Text("Desconectar")
            }
            OutlinedButton(
                onClick = onForget,
                modifier = Modifier.weight(1f),
            ) {
                Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("Olvidar")
            }
        }
    }
}

@Composable
private fun ErrorState(message: String, onRetry: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = Icons.Default.Error,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = AccentCoral,
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = "Error",
            style = MaterialTheme.typography.headlineSmall,
        )
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary,
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onRetry) {
            Text("Reintentar")
        }
    }
}
