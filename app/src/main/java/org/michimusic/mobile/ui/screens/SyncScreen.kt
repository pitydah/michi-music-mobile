@file:Suppress("DEPRECATION")

package org.michimusic.mobile.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.androidx.compose.koinViewModel
import org.michimusic.core.models.SyncConnectionState
import org.michimusic.mobile.sync.SyncViewModel
import org.michimusic.mobile.sync.SyncProgress
import org.michimusic.mobile.sync.SyncUiState
import org.michimusic.mobile.ui.screens.sync.CodePairingForm
import org.michimusic.mobile.ui.screens.sync.ConnectedState
import org.michimusic.mobile.ui.screens.sync.ConnectingState
import org.michimusic.mobile.ui.screens.sync.ConnectionPrompt
import org.michimusic.mobile.ui.screens.sync.DiscoveringState
import org.michimusic.mobile.ui.screens.sync.ErrorState
import org.michimusic.mobile.ui.screens.sync.PairingForm
import org.michimusic.mobile.ui.theme.AccentCoral
import org.michimusic.mobile.ui.theme.SurfaceDark

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
                .background(SurfaceDark)
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
                    when (uiState.pairingStrategy) {
                        org.michimusic.link.dto.PairingStrategy.SERVER_CODE -> {
                            CodePairingForm(
                                peer = uiState.connectedPeer,
                                onPair = { code ->
                                    uiState.connectedPeer?.let { peer ->
                                        viewModel.startPairing(peer, "", code)
                                    }
                                },
                                onBack = { viewModel.disconnect() },
                            )
                        }
                        else -> {
                            PairingForm(
                                peer = uiState.connectedPeer,
                                onPair = { username, password ->
                                    uiState.connectedPeer?.let { peer ->
                                        viewModel.startPairing(peer, username, password)
                                    }
                                },
                                onBack = { viewModel.disconnect() },
                            )
                        }
                    }
                }

                SyncConnectionState.PAIRING -> {
                    ConnectingState(message = "Emparejando...")
                }

                SyncConnectionState.CONNECTING -> {
                    ConnectingState(message = "Conectando...")
                }

                SyncConnectionState.PAIRED, SyncConnectionState.CONNECTED -> {
                    ConnectedState(
                        name = uiState.connectedPeer?.alias ?: "Servidor",
                        syncProgress = uiState.syncProgress,
                        onSync = { viewModel.syncLibrary() },
                        onDisconnect = { viewModel.disconnect() },
                        onForget = { viewModel.forgetServer() },
                    )
                }

                SyncConnectionState.AUTH_ERROR -> {
                    ErrorState(
                        message = "Error de autenticación. Intenta emparejar de nuevo.",
                        onRetry = { viewModel.startDiscovery() },
                    )
                }

                SyncConnectionState.REVOKED -> {
                    ErrorState(
                        message = "Dispositivo revocado desde Michi Music Player. Vuelve a emparejar.",
                        onRetry = { viewModel.startDiscovery() },
                    )
                }

                SyncConnectionState.ERROR -> {
                    ErrorState(
                        message = uiState.error ?: "Error desconocido",
                        onRetry = { viewModel.startDiscovery() },
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
                    containerColor = AccentCoral,
                    shape = RoundedCornerShape(12.dp),
                    action = {
                        TextButton(onClick = { viewModel.clearError() }) {
                            Text("OK", color = SurfaceDark)
                        }
                    },
                ) {
                    Text(msg, color = SurfaceDark)
                }
            }
        }
    }
}
