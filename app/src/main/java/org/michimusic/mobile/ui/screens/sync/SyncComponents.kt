package org.michimusic.mobile.ui.screens.sync

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Lan
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import org.michimusic.core.models.DiscoveredPeer
import org.michimusic.mobile.sync.SyncProgress
import org.michimusic.mobile.ui.components.GlassCard
import org.michimusic.mobile.ui.theme.AccentCoral
import org.michimusic.mobile.ui.theme.AccentPink
import org.michimusic.mobile.ui.theme.SurfaceDark
import org.michimusic.mobile.ui.theme.SurfaceElevated
import org.michimusic.mobile.ui.theme.TextMuted
import org.michimusic.mobile.ui.theme.TextPrimary
import org.michimusic.mobile.ui.theme.TextSecondary

@Composable
fun ConnectionPrompt(onStart: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        GlassCard(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    modifier = Modifier
                        .size(72.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .background(AccentCoral.copy(alpha = 0.16f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Default.Lan, contentDescription = null, modifier = Modifier.size(36.dp), tint = AccentCoral)
                }
                Spacer(Modifier.height(16.dp))
                Text("Sincronización", style = MaterialTheme.typography.headlineLarge, color = TextPrimary)
                Spacer(Modifier.height(8.dp))
                Text("Conecta con Michi Music Player en tu red local", style = MaterialTheme.typography.bodyLarge, color = TextSecondary)
                Spacer(Modifier.height(24.dp))
                Button(
                    onClick = onStart,
                    colors = ButtonDefaults.buttonColors(containerColor = AccentCoral, contentColor = SurfaceDark),
                    shape = RoundedCornerShape(8.dp),
                ) {
                    Icon(Icons.Default.Sync, contentDescription = null)
                    Spacer(Modifier.size(8.dp))
                    Text("Buscar servidores")
                }
            }
        }
    }
}

@Composable
fun DiscoveringState(
    peers: List<DiscoveredPeer>,
    onSelect: (DiscoveredPeer) -> Unit,
    onStop: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        Text("Servidores en la red", style = MaterialTheme.typography.titleLarge, color = TextPrimary)
        Spacer(Modifier.height(16.dp))
        if (peers.isEmpty()) {
            Column(
                Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                CircularProgressIndicator(color = AccentCoral)
                Spacer(Modifier.height(12.dp))
                Text("Buscando servidores Michi...", style = MaterialTheme.typography.bodyLarge, color = TextSecondary)
                Spacer(Modifier.height(16.dp))
                OutlinedButton(onClick = onStop) { Text("Detener búsqueda") }
            }
        } else {
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(peers, key = { "${it.ip}:${it.port}" }) { peer ->
                    PeerCard(peer = peer, onClick = { onSelect(peer) })
                }
            }
            Spacer(Modifier.height(12.dp))
            OutlinedButton(onClick = onStop, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Close, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Detener búsqueda")
            }
        }
    }
}

@Composable
fun PeerCard(
    peer: DiscoveredPeer,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = SurfaceElevated),
        shape = RoundedCornerShape(12.dp),
    ) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Devices, contentDescription = null, tint = AccentCoral)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(peer.alias, style = MaterialTheme.typography.bodyLarge, color = TextPrimary)
                Text("${peer.ip}:${peer.port}", style = MaterialTheme.typography.bodySmall, color = TextMuted)
            }
            Text("Emparejar", color = AccentCoral, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
fun PairingForm(
    peer: DiscoveredPeer?,
    onPair: (username: String, password: String) -> Unit,
    onBack: () -> Unit,
) {
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        GlassCard(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth()) {
                Text("Emparejar con ${peer?.alias ?: "servidor"}", style = MaterialTheme.typography.titleMedium, color = TextPrimary)
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(value = username, onValueChange = { username = it }, label = { Text("Usuario") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(value = password, onValueChange = { password = it }, label = { Text("Contraseña") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(16.dp))
                Button(onClick = { onPair(username, password) }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = AccentCoral, contentColor = SurfaceDark)) { Text("Emparejar") }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("Cancelar") }
            }
        }
    }
}

@Composable
fun CodePairingForm(
    peer: DiscoveredPeer?,
    onPair: (code: String) -> Unit,
    onBack: () -> Unit,
) {
    var code by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        GlassCard(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth()) {
                Text("Emparejar con ${peer?.alias ?: "servidor"}", style = MaterialTheme.typography.titleMedium, color = TextPrimary)
                Spacer(Modifier.height(8.dp))
                Text("Ingresa el código mostrado en el servidor", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(value = code, onValueChange = { code = it }, label = { Text("Código") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(16.dp))
                Button(onClick = { onPair(code) }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = AccentCoral, contentColor = SurfaceDark)) { Text("Emparejar") }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("Cancelar") }
            }
        }
    }
}

@Composable
fun ConnectingState(message: String) {
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        CircularProgressIndicator(color = AccentCoral)
        Spacer(Modifier.height(12.dp))
        Text(message, style = MaterialTheme.typography.bodyLarge, color = TextSecondary)
    }
}

@Composable
fun ConnectedState(
    name: String,
    onSync: () -> Unit,
    onDisconnect: () -> Unit,
    onForget: () -> Unit,
    syncProgress: SyncProgress = SyncProgress.Idle,
) {
    Column(Modifier.fillMaxSize()) {
        GlassCard(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Default.CheckCircle, contentDescription = null, tint = AccentCoral, modifier = Modifier.size(48.dp))
                Spacer(Modifier.height(12.dp))
                Text("Conectado a $name", style = MaterialTheme.typography.titleMedium, color = TextPrimary)
                Spacer(Modifier.height(8.dp))
                Text("Listo para sincronizar", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                Spacer(Modifier.height(16.dp))
                when (syncProgress) {
                    is SyncProgress.Idle -> {
                        Button(onClick = onSync, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = AccentCoral, contentColor = SurfaceDark)) {
                            Icon(Icons.Default.Sync, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("Sincronizar ahora")
                        }
                    }
                    is SyncProgress.Downloading -> {
                        SyncProgressContent(syncProgress)
                    }
                    is SyncProgress.Complete -> {
                        Text("${syncProgress.downloaded} canciones sincronizadas", style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(onClick = { onSync() }) { Text("Sincronizar de nuevo") }
                    }
                    is SyncProgress.Error -> {
                        Text(syncProgress.message, style = MaterialTheme.typography.bodyMedium, color = AccentPink)
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(onClick = { onSync() }) { Text("Reintentar") }
                    }
                }
            }
        }
        Spacer(Modifier.weight(1f))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            OutlinedButton(onClick = onDisconnect) { Text("Desconectar") }
            IconButton(onClick = onForget) { Icon(Icons.Default.Delete, contentDescription = "Olvidar servidor", tint = AccentPink) }
        }
    }
}

@Composable
private fun SyncProgressContent(progress: SyncProgress.Downloading) {
    Column(Modifier.fillMaxWidth()) {
        LinearProgressIndicator(
            progress = { if (progress.total > 0) progress.completed.toFloat() / progress.total else 0f },
            modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
            color = AccentCoral,
            trackColor = SurfaceElevated,
        )
        Spacer(Modifier.height(8.dp))
        Text("${progress.completed} / ${progress.total}", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
    }
}

@Composable
fun ErrorState(message: String, onRetry: () -> Unit) {
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Icon(Icons.Default.Error, contentDescription = null, tint = AccentCoral, modifier = Modifier.size(48.dp))
        Spacer(Modifier.height(12.dp))
        Text(message, style = MaterialTheme.typography.bodyLarge, color = TextPrimary)
        Spacer(Modifier.height(16.dp))
        OutlinedButton(onClick = onRetry) { Text("Reintentar") }
    }
}
