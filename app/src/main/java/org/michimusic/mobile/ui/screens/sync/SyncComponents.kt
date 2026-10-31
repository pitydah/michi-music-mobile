package org.michimusic.mobile.ui.screens.sync

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.sp
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
import org.michimusic.mobile.ui.components.premium.MichiIconAction
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
import org.michimusic.mobile.ui.components.syncProgressCaption
import org.michimusic.mobile.ui.components.syncProgressFraction
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
                items(peers, key = { if (it.deviceId.isNotBlank()) it.deviceId else "${it.ip}:${it.port}" }) { peer ->
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
                        SyncProgressContent(syncProgress.completed, syncProgress.total)
                    }
                    is SyncProgress.Cancelling -> {
                        Column(Modifier.fillMaxWidth()) {
                            Text("Cancelando sincronización...", style = MaterialTheme.typography.bodyMedium, color = AccentPink)
                            Spacer(Modifier.height(8.dp))
                            SyncProgressContent(syncProgress.completed, syncProgress.total)
                        }
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
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = onDisconnect) { Text("Desconectar") }
            MichiIconAction(
                icon = Icons.Default.Delete,
                contentDescription = "Olvidar servidor",
                onClick = onForget,
                tint = AccentPink,
                iconSize = 22.dp,
                minTargetSize = 48.dp,
            )
        }
    }
}

@Composable
private fun SyncProgressContent(completed: Int, total: Int) {
    Column(Modifier.fillMaxWidth()) {
        val fraction = syncProgressFraction(completed, total)
        if (fraction == null) {
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                color = AccentCoral,
                trackColor = SurfaceElevated,
            )
        } else {
            LinearProgressIndicator(
                progress = { fraction },
                modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                color = AccentCoral,
                trackColor = SurfaceElevated,
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(syncProgressCaption(completed, total), style = MaterialTheme.typography.bodySmall, color = TextSecondary)
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

@Composable
fun AirCatTransferSafeLayout(
    progress: org.michimusic.mobile.devices.aircat.AirCatProgressEvent,
    onPause: () -> Unit = {},
    onResume: () -> Unit = {},
    onCancel: () -> Unit = {},
    onRetry: () -> Unit = {},
    onDismiss: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val presentation = org.michimusic.mobile.ui.components.airCatUiPresentation(progress.state.name)
    val isError = progress.state == org.michimusic.mobile.devices.aircat.AirCatTransferState.FAILED || presentation.isError
    val isCompleted = progress.state == org.michimusic.mobile.devices.aircat.AirCatTransferState.COMPLETED
    val currentStageIndex = when (progress.state) {
        org.michimusic.mobile.devices.aircat.AirCatTransferState.PENDING_AUTHORIZATION, org.michimusic.mobile.devices.aircat.AirCatTransferState.NEGOTIATING -> 0
        org.michimusic.mobile.devices.aircat.AirCatTransferState.TRANSFERRING, org.michimusic.mobile.devices.aircat.AirCatTransferState.PAUSED -> 1
        org.michimusic.mobile.devices.aircat.AirCatTransferState.VERIFYING -> 2
        org.michimusic.mobile.devices.aircat.AirCatTransferState.COMPLETED -> 3
        org.michimusic.mobile.devices.aircat.AirCatTransferState.FAILED, org.michimusic.mobile.devices.aircat.AirCatTransferState.CANCELLED -> 1
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(org.michimusic.mobile.ui.theme.AeroGraphiteRaised)
            .border(
                width = 0.75.dp,
                color = if (isError) org.michimusic.mobile.ui.theme.ErrorColor.copy(alpha = 0.6f) else org.michimusic.mobile.ui.theme.AeroHairline,
                shape = RoundedCornerShape(20.dp)
            )
            .padding(18.dp)
            .testTag("aircat_transfer_card"),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // 4-Stage Stepper: Preparación -> Transferencia -> Verificando -> Completado
            AirCatStepperHeader(currentStage = currentStageIndex, isError = isError)

            if (isError) {
                // V13_04_AIRCAT_ERROR layout
                AirCatErrorView(
                    errorMessage = progress.error ?: "Se interrumpió la conexión durante la transferencia",
                    onRetry = onRetry,
                    onDismiss = onDismiss,
                )
            } else if (isCompleted) {
                // Completed layout
                AirCatCompletedView(
                    totalTracks = progress.totalTracks,
                    totalBytes = progress.totalBytes,
                    onDismiss = onDismiss,
                )
            } else {
                // V13_03_AIRCAT_SAFE_LAYOUT active layout
                AirCatActiveTransferView(
                    progress = progress,
                    presentation = presentation,
                    onPause = onPause,
                    onResume = onResume,
                    onCancel = onCancel,
                )
            }
        }
    }
}

@Composable
private fun AirCatStepperHeader(currentStage: Int, isError: Boolean) {
    val stages = listOf("Preparación", "Transferencia", "Verificando", "Completado")
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        stages.forEachIndexed { index, title ->
            val isDone = index < currentStage || (index == 3 && currentStage == 3 && !isError)
            val isActive = index == currentStage
            val isFailed = isActive && isError

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.weight(1f),
            ) {
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .clip(androidx.compose.foundation.shape.CircleShape)
                        .background(
                            when {
                                isFailed -> org.michimusic.mobile.ui.theme.ErrorColor.copy(alpha = 0.2f)
                                isDone -> org.michimusic.mobile.ui.theme.TertiaryCyan.copy(alpha = 0.2f)
                                isActive -> org.michimusic.mobile.ui.theme.TertiaryCyan
                                else -> org.michimusic.mobile.ui.theme.SurfaceObsidian
                            }
                        )
                        .border(
                            0.75.dp,
                            when {
                                isFailed -> org.michimusic.mobile.ui.theme.ErrorColor
                                isDone || isActive -> org.michimusic.mobile.ui.theme.TertiaryCyan
                                else -> org.michimusic.mobile.ui.theme.TextMuted.copy(alpha = 0.4f)
                            },
                            androidx.compose.foundation.shape.CircleShape
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    when {
                        isFailed -> Icon(Icons.Filled.Close, contentDescription = null, tint = org.michimusic.mobile.ui.theme.ErrorColor, modifier = Modifier.size(14.dp))
                        isDone -> Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = org.michimusic.mobile.ui.theme.TertiaryCyan, modifier = Modifier.size(14.dp))
                        isActive -> Box(modifier = Modifier.size(8.dp).clip(androidx.compose.foundation.shape.CircleShape).background(org.michimusic.mobile.ui.theme.SurfaceObsidian))
                        else -> Text("${index + 1}", color = org.michimusic.mobile.ui.theme.TextMuted, fontSize = 11.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                    }
                }
                Text(
                    text = title,
                    fontSize = 11.sp,
                    fontWeight = if (isActive) androidx.compose.ui.text.font.FontWeight.Bold else androidx.compose.ui.text.font.FontWeight.Medium,
                    color = when {
                        isFailed -> org.michimusic.mobile.ui.theme.ErrorColor
                        isActive -> org.michimusic.mobile.ui.theme.TertiaryCyan
                        isDone -> org.michimusic.mobile.ui.theme.PureWhite
                        else -> org.michimusic.mobile.ui.theme.TextMuted
                    },
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun AirCatErrorView(
    errorMessage: String,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(org.michimusic.mobile.ui.theme.ErrorColor.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.Error, contentDescription = null, tint = org.michimusic.mobile.ui.theme.ErrorColor, modifier = Modifier.size(22.dp))
            }
            Column {
                Text(
                    text = "Error de transferencia",
                    style = org.michimusic.mobile.ui.theme.MichiTypography.cardTitle,
                    color = org.michimusic.mobile.ui.theme.PureWhite,
                    fontSize = 17.sp,
                )
                Text(
                    text = errorMessage,
                    style = org.michimusic.mobile.ui.theme.MichiTypography.metadata,
                    color = org.michimusic.mobile.ui.theme.TextSecondary,
                    maxLines = 2,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
            }
        }

        // Retained validated files badge / callout matching V13_04_AIRCAT_ERROR.png
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(androidx.compose.ui.graphics.Color(0x1F00E5FF))
                .border(0.75.dp, androidx.compose.ui.graphics.Color(0x6600E5FF), RoundedCornerShape(12.dp))
                .padding(12.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.CheckCircle,
                    contentDescription = null,
                    tint = org.michimusic.mobile.ui.theme.TertiaryCyan,
                    modifier = Modifier.size(18.dp),
                )
                Column {
                    Text(
                        text = "Se conservaron los archivos validados",
                        color = org.michimusic.mobile.ui.theme.TertiaryCyan,
                        fontSize = 13.sp,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                    )
                    Text(
                        text = "Los fragmentos descargados y las pistas verificadas permanecen guardados intactos.",
                        color = org.michimusic.mobile.ui.theme.TextMuted,
                        fontSize = 11.sp,
                    )
                }
            }
        }

        // Action CTAs
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Button(
                onClick = onRetry,
                colors = ButtonDefaults.buttonColors(containerColor = org.michimusic.mobile.ui.theme.PrimaryPinkContainer),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp)
                    .testTag("aircat_retry_button"),
            ) {
                Text(
                    text = "Reintentar",
                    color = org.michimusic.mobile.ui.theme.PureWhite,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                    fontSize = 14.sp,
                )
            }

            OutlinedButton(
                onClick = onDismiss,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp)
                    .testTag("aircat_dismiss_button"),
            ) {
                Text(
                    text = "Cerrar",
                    color = org.michimusic.mobile.ui.theme.TextSecondary,
                    fontSize = 14.sp,
                )
            }
        }
    }
}

@Composable
private fun AirCatCompletedView(
    totalTracks: Int,
    totalBytes: Long,
    onDismiss: () -> Unit,
) {
    val totalMb = totalBytes / (1024.0 * 1024.0)
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(org.michimusic.mobile.ui.theme.TertiaryCyan.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = org.michimusic.mobile.ui.theme.TertiaryCyan, modifier = Modifier.size(24.dp))
            }
            Column {
                Text(
                    text = "Transferencia completada",
                    style = org.michimusic.mobile.ui.theme.MichiTypography.cardTitle,
                    color = org.michimusic.mobile.ui.theme.PureWhite,
                    fontSize = 17.sp,
                )
                Text(
                    text = "$totalTracks pistas sincronizadas · ${String.format(java.util.Locale.US, "%.1f MB", totalMb)}",
                    style = org.michimusic.mobile.ui.theme.MichiTypography.metadata,
                    color = org.michimusic.mobile.ui.theme.TertiaryCyan,
                )
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(androidx.compose.ui.graphics.Color(0x1F00E5FF))
                .border(0.75.dp, androidx.compose.ui.graphics.Color(0x6600E5FF), RoundedCornerShape(12.dp))
                .padding(12.dp),
        ) {
            Text(
                text = "Todos los archivos pasaron la verificación SHA-256 e ingresaron a la biblioteca.",
                color = org.michimusic.mobile.ui.theme.TextSecondary,
                fontSize = 12.sp,
            )
        }

        Button(
            onClick = onDismiss,
            colors = ButtonDefaults.buttonColors(containerColor = org.michimusic.mobile.ui.theme.PrimaryPinkContainer),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .testTag("aircat_complete_button"),
        ) {
            Text(
                text = "Aceptar",
                color = org.michimusic.mobile.ui.theme.PureWhite,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                fontSize = 14.sp,
            )
        }
    }
}

@Composable
private fun AirCatActiveTransferView(
    progress: org.michimusic.mobile.devices.aircat.AirCatProgressEvent,
    presentation: org.michimusic.mobile.ui.components.AirCatUiPresentation,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit,
) {
    val transferredMb = progress.bytesTransferred / (1024.0 * 1024.0)
    val totalMb = progress.totalBytes / (1024.0 * 1024.0)
    val pct = if (progress.totalBytes > 0) ((progress.bytesTransferred.toDouble() / progress.totalBytes.toDouble()) * 100.0).toInt().coerceIn(0, 100) else null
    val fraction = if (progress.totalBytes > 0) (progress.bytesTransferred.toFloat() / progress.totalBytes.toFloat()).coerceIn(0f, 1f) else 0f

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (progress.state == org.michimusic.mobile.devices.aircat.AirCatTransferState.VERIFYING) "Verificando integridad SHA-256..." else progress.activeTrackTitle.ifBlank { "Transfiriendo música..." },
                    style = org.michimusic.mobile.ui.theme.MichiTypography.trackTitle,
                    color = org.michimusic.mobile.ui.theme.PureWhite,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
                Text(
                    text = "${progress.currentTrackIndex.coerceAtLeast(1)} de ${progress.totalTracks} pistas",
                    style = org.michimusic.mobile.ui.theme.MichiTypography.metadata,
                    color = org.michimusic.mobile.ui.theme.TextSecondary,
                )
            }

            org.michimusic.mobile.ui.components.AeroStatusPill(
                label = presentation.label,
                accent = if (progress.state == org.michimusic.mobile.devices.aircat.AirCatTransferState.PAUSED) androidx.compose.ui.graphics.Color(0xFFE5A93C) else org.michimusic.mobile.ui.theme.TertiaryCyan,
            )
        }

        LinearProgressIndicator(
            progress = { fraction },
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(androidx.compose.foundation.shape.CircleShape),
            color = AccentCoral,
            trackColor = org.michimusic.mobile.ui.theme.SurfaceObsidian,
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = if (pct != null) {
                    String.format(java.util.Locale.US, "%.1f / %.1f MB (%d%%)", transferredMb, totalMb, pct)
                } else {
                    String.format(java.util.Locale.US, "%.1f MB transferidos", transferredMb)
                },
                style = org.michimusic.mobile.ui.theme.MichiTypography.metadata,
                color = org.michimusic.mobile.ui.theme.TextMuted,
            )
            Text(
                text = "Checkpoint activo",
                color = org.michimusic.mobile.ui.theme.TertiaryCyan,
                fontSize = 11.sp,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
            )
        }

        Text(
            text = "Transferencia atómica: si se interrumpe, se conservan los archivos validados.",
            style = org.michimusic.mobile.ui.theme.MichiTypography.metadata,
            color = org.michimusic.mobile.ui.theme.TextMuted,
            fontSize = 11.sp,
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (presentation.showPause) {
                OutlinedButton(
                    onClick = onPause,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .testTag("aircat_pause_button"),
                ) {
                    Text(text = "Pausar", style = org.michimusic.mobile.ui.theme.MichiTypography.microLabel, color = org.michimusic.mobile.ui.theme.PureWhite)
                }
            } else if (presentation.showResume) {
                Button(
                    onClick = onResume,
                    colors = ButtonDefaults.buttonColors(containerColor = org.michimusic.mobile.ui.theme.TertiaryCyan),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .testTag("aircat_resume_button"),
                ) {
                    Text(text = "Reanudar", style = org.michimusic.mobile.ui.theme.MichiTypography.microLabel, color = org.michimusic.mobile.ui.theme.SurfaceObsidian, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                }
            }

            if (presentation.showCancel) {
                OutlinedButton(
                    onClick = onCancel,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .testTag("aircat_cancel_button"),
                ) {
                    Text(text = "Cancelar", style = org.michimusic.mobile.ui.theme.MichiTypography.microLabel, color = org.michimusic.mobile.ui.theme.ErrorColor)
                }
            }
        }
    }
}
