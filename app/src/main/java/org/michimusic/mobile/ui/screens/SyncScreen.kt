@file:Suppress("DEPRECATION")
package org.michimusic.mobile.ui.screens

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Lan
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.androidx.compose.koinViewModel
import org.michimusic.core.models.DiscoveredPeer
import org.michimusic.core.models.SyncConnectionState
import org.michimusic.mobile.sync.SyncProgress
import org.michimusic.mobile.sync.SyncUiState
import org.michimusic.mobile.sync.SyncViewModel
import org.michimusic.mobile.ui.theme.SurfaceDark
import org.michimusic.mobile.ui.theme.TextMuted
import org.michimusic.mobile.ui.theme.TextPrimary
import org.michimusic.mobile.ui.components.GlassCard
import org.michimusic.mobile.ui.components.MichiActionButton
import org.michimusic.mobile.ui.components.MichiBackground
import org.michimusic.mobile.ui.components.MichiButtonStyle
import org.michimusic.mobile.ui.theme.MichiSpacing

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

    MichiBackground {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(MichiSpacing.lg),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            when (uiState.state) {
                SyncConnectionState.DISCONNECTED -> {
                    ConnectionPrompt(onStart = viewModel::startDiscovery)
                }

                SyncConnectionState.DISCOVERING -> {
                    DiscoveringState(
                        peers = uiState.peers,
                        onConnect = viewModel::connectToPeer,
                        onStop = viewModel::stopDiscovery,
                    )
                }

                SyncConnectionState.PAIRING_REQUIRED -> {
                    val peer = uiState.connectedPeer
                    if (peer != null) {
                        PairingState(
                            peer = peer,
                            onPair = { username, password ->
                                viewModel.pairWithServer(peer, username, password)
                            },
                            onCancel = viewModel::disconnect,
                        )
                    } else {
                        ErrorState(
                            message = "Emparejamiento requerido pero no hay peer",
                            onRetry = { viewModel.clearError(); viewModel.startDiscovery() },
                        )
                    }
                }

                SyncConnectionState.PAIRING -> {
                    ConnectingState()
                }

                SyncConnectionState.CONNECTING -> {
                    ConnectingState()
                }

                SyncConnectionState.CONNECTED -> {
                    ConnectedState(
                        peer = uiState.connectedPeer,
                        registration = uiState.registration,
                        syncProgress = uiState.syncProgress,
                        onSync = viewModel::syncLibrary,
                        onDisconnect = viewModel::disconnect,
                        onNavigateToSynced = onNavigateToSynced,
                    )
                }

                SyncConnectionState.SYNCING -> {
                    ConnectedState(
                        peer = uiState.connectedPeer,
                        registration = uiState.registration,
                        syncProgress = uiState.syncProgress,
                        onSync = {},
                        onDisconnect = viewModel::disconnect,
                        onNavigateToSynced = onNavigateToSynced,
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
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = "Sincronización",
            style = MaterialTheme.typography.headlineLarge,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Conecta con Michi Music Player en tu red local",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))
        MichiActionButton(
            text = "Buscar servidores",
            icon = Icons.Default.Sync,
            onClick = onStart,
            style = MichiButtonStyle.PRIMARY_GLOW,
        )
    }
}

@Composable
private fun DiscoveringState(
    peers: List<DiscoveredPeer>,
    onConnect: (DiscoveredPeer) -> Unit,
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
            MichiActionButton(
                text = "Detener",
                onClick = onStop,
                style = MichiButtonStyle.SECONDARY_GLASS,
            )
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
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(peers) { peer ->
                    PeerCard(peer = peer, onClick = { onConnect(peer) })
                }
            }
        }
    }
}

@Composable
private fun PeerCard(peer: DiscoveredPeer, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Default.Devices,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.size(12.dp))
            Column {
                Text(
                    text = peer.alias,
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = "${peer.ip}:${peer.port}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ConnectingState() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Spacer(Modifier.height(16.dp))
            Text(
                "Conectando...",
                style = MaterialTheme.typography.bodyLarge,
            )
        }
    }
}

@Composable
private fun ConnectedState(
    peer: DiscoveredPeer?,
    registration: org.michimusic.core.models.RegisterResponse?,
    syncProgress: SyncProgress,
    onSync: () -> Unit,
    onDisconnect: () -> Unit,
    onNavigateToSynced: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Text(
            text = "Conectado",
            style = MaterialTheme.typography.headlineMedium,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = peer?.let { "${it.alias} (${it.ip})" } ?: "Servidor",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        registration?.let { reg ->
            Spacer(Modifier.height(16.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        "Biblioteca: ${reg.librarySize} canciones",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        "Servidor: ${reg.serverDeviceId.take(8)}...",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        Spacer(Modifier.height(24.dp))

        when (syncProgress) {
            is SyncProgress.Idle -> {
                MichiActionButton(
                    text = "Sincronizar biblioteca",
                    icon = Icons.Default.Sync,
                    onClick = onSync,
                    style = MichiButtonStyle.PRIMARY_GLOW,
                )
            }

            is SyncProgress.Downloading -> {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
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
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            is SyncProgress.Complete -> {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                    ),
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            "Sincronización completa",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text("${syncProgress.tracks} canciones en biblioteca")
                        Text("${syncProgress.downloaded} descargadas")
                        Spacer(Modifier.height(8.dp))
                        MichiActionButton(
                            text = "Ver biblioteca",
                            icon = Icons.Default.List,
                            onClick = onNavigateToSynced,
                            style = MichiButtonStyle.SECONDARY_GLASS,
                        )
                    }
                }
            }

            is SyncProgress.Error -> {
                Text(
                    syncProgress.message,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }

        Spacer(Modifier.height(16.dp))
        MichiActionButton(
            text = "Desconectar",
            onClick = onDisconnect,
            style = MichiButtonStyle.SECONDARY_GLASS,
        )
    }
}

@Composable
private fun PairingState(
    peer: DiscoveredPeer,
    onPair: (String, String) -> Unit,
    onCancel: () -> Unit,
) {
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }

    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = Icons.Default.Devices,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = "Emparejar con ${peer.alias}",
            style = MaterialTheme.typography.headlineSmall,
            color = TextPrimary,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Ingresa las credenciales del servidor Michi",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))

        OutlinedTextField(
            value = username,
            onValueChange = { username = it },
            label = { Text("Usuario", color = TextMuted) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = TextPrimary,
                unfocusedTextColor = TextPrimary,
                cursorColor = MaterialTheme.colorScheme.primary,
            ),
        )
        Spacer(Modifier.height(12.dp))

        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("Contraseña", color = TextMuted) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = TextPrimary,
                unfocusedTextColor = TextPrimary,
                cursorColor = MaterialTheme.colorScheme.primary,
            ),
        )
        Spacer(Modifier.height(24.dp))

        MichiActionButton(
            text = "Emparejar",
            onClick = { onPair(username, password) },
            style = if (username.isNotBlank() && password.isNotBlank()) MichiButtonStyle.PRIMARY_GLOW else MichiButtonStyle.SECONDARY_GLASS,
        )
        Spacer(Modifier.height(8.dp))
        MichiActionButton(
            text = "Cancelar",
            onClick = onCancel,
            style = MichiButtonStyle.SECONDARY_GLASS,
        )
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
            tint = MaterialTheme.colorScheme.error,
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = "Error de conexión",
            style = MaterialTheme.typography.headlineSmall,
        )
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))
        MichiActionButton(
            text = "Reintentar",
            onClick = onRetry,
            style = MichiButtonStyle.PRIMARY_GLOW,
        )
    }
}
