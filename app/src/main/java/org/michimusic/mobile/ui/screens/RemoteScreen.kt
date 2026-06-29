@file:Suppress("DEPRECATION")
package org.michimusic.mobile.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CastConnected
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import org.koin.androidx.compose.koinViewModel
import org.michimusic.mobile.remote.RemoteSourceMode
import org.michimusic.mobile.remote.RemoteUiState
import org.michimusic.mobile.remote.RemoteViewModel
import org.michimusic.mobile.ui.theme.AccentPink
import org.michimusic.mobile.ui.theme.SurfaceDark
import org.michimusic.mobile.ui.theme.SurfaceElevated
import org.michimusic.mobile.ui.theme.TextDim
import org.michimusic.mobile.ui.theme.TextMuted
import org.michimusic.mobile.ui.theme.TextPrimary
import org.michimusic.mobile.ui.theme.TextSecondary

@Composable
fun RemoteScreen(
    onNavigateToSync: () -> Unit = {},
    viewModel: RemoteViewModel = koinViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        viewModel.connectIfNeeded()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(SurfaceDark)
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Selector de modo
        ModeSelector(
            mode = uiState.mode,
            sourceName = uiState.sourceName,
            onConnectToSync = onNavigateToSync,
        )

        Spacer(Modifier.height(16.dp))

        if (!uiState.connected && uiState.mode == RemoteSourceMode.REMOTE) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.Sync, null, tint = TextDim, modifier = Modifier.size(64.dp))
                    Spacer(Modifier.height(16.dp))
                    Text("Conecta con un servidor desde Sync", color = TextSecondary)
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = onNavigateToSync) { Text("Ir a Sync") }
                }
            }
            return
        }

        if (uiState.mode == RemoteSourceMode.LOCAL) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.PhoneAndroid, null, tint = AccentPink, modifier = Modifier.size(64.dp))
                    Spacer(Modifier.height(16.dp))
                    Text("Reproduciendo en este teléfono", color = TextPrimary, style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.height(8.dp))
                    Text("Abre NowPlaying para controlar", color = TextSecondary)
                }
            }
            return
        }

        val state = uiState.playerState
        val effState = state.effectiveState
        val effTitle = state.effectiveTitle
        val effArtist = state.effectiveArtist
        val effPosition = state.effectivePosition
        val effDuration = state.effectiveDuration

        // Carátula
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .aspectRatio(1f)
                .clip(RoundedCornerShape(12.dp))
                .background(SurfaceElevated),
            contentAlignment = Alignment.Center,
        ) {
            if (state.coverUrl.isNotEmpty()) {
                AsyncImage(
                    model = state.coverUrl,
                    contentDescription = state.title,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            } else {
                Icon(Icons.Default.CastConnected, null, tint = AccentPink, modifier = Modifier.size(80.dp))
            }
        }

        Spacer(Modifier.height(16.dp))

        // Info del tema
        Text(
            text = effTitle.ifEmpty { "Sin reproducción" },
            style = MaterialTheme.typography.titleLarge,
            color = TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = effArtist.ifEmpty { "" },
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )

        Spacer(Modifier.height(8.dp))

        // Salida
        if (state.outputName.isNotEmpty()) {
            Text(
                text = "Sonando en: ${state.outputName}",
                style = MaterialTheme.typography.bodySmall,
                color = TextMuted,
            )
        }

        Spacer(Modifier.height(12.dp))

        // Progreso
        val duration = if (effDuration > 0) effDuration else 1L
        val progress = (effPosition.toFloat() / duration).coerceIn(0f, 1f)
        var dragProgress by remember { mutableFloatStateOf(progress) }

        Slider(
            value = dragProgress,
            onValueChange = { dragProgress = it },
            onValueChangeFinished = {
                val seekPos = (dragProgress * effDuration).toLong()
                viewModel.seek(seekPos)
            },
            modifier = Modifier.fillMaxWidth(),
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(formatTime(effPosition), color = TextSecondary, style = MaterialTheme.typography.bodySmall)
            Text(formatTime(effDuration), color = TextSecondary, style = MaterialTheme.typography.bodySmall)
        }

        Spacer(Modifier.height(16.dp))

        // Controles
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = viewModel::previous) {
                Icon(Icons.Default.SkipPrevious, "Anterior", tint = TextPrimary, modifier = Modifier.size(32.dp))
            }

            Box(
                modifier = Modifier
                    .size(72.dp)
                    .clip(CircleShape)
                    .background(AccentPink),
                contentAlignment = Alignment.Center,
            ) {
                IconButton(onClick = viewModel::togglePlayPause) {
                    Icon(
                        if (effState == "playing") Icons.Default.Pause else Icons.Default.PlayArrow,
                        if (effState == "playing") "Pausar" else "Reproducir",
                        tint = Color.White,
                        modifier = Modifier.size(36.dp),
                    )
                }
            }

            IconButton(onClick = viewModel::next) {
                Icon(Icons.Default.SkipNext, "Siguiente", tint = TextPrimary, modifier = Modifier.size(32.dp))
            }
        }

        Spacer(Modifier.height(8.dp))

        // Volumen
        if (state.volume > 0 || uiState.connState == org.michimusic.mobile.remote.RemoteConnectionState.CONNECTED) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Spacer(Modifier.height(8.dp))
                Text("Vol", color = TextSecondary, style = MaterialTheme.typography.bodySmall)
                Slider(
                    value = state.effectiveVolume / 100f,
                    onValueChange = { viewModel.setVolume((it * 100).toInt()) },
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        // Cola
        if (uiState.queue.tracks.isNotEmpty()) {
            Text("Cola", style = MaterialTheme.typography.titleMedium, color = TextPrimary)
            Spacer(Modifier.height(8.dp))

            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                itemsIndexed(uiState.queue.tracks) { index, track ->
                    val isCurrent = index == uiState.queue.currentIndex
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = if (isCurrent) AccentPink.copy(alpha = 0.15f) else SurfaceElevated,
                        ),
                        onClick = { viewModel.queueJump(index) },
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    text = track.title,
                                    color = if (isCurrent) AccentPink else TextPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                Text(
                                    text = track.artist,
                                    color = TextMuted,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            if (isCurrent) {
                                Icon(Icons.Default.PlayArrow, null, tint = AccentPink, modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                }
            }
        } else {
            Spacer(Modifier.weight(1f))
        }

        // Error
        uiState.error?.let { msg ->
            Spacer(Modifier.height(8.dp))
            Text(msg, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun ModeSelector(
    mode: RemoteSourceMode,
    sourceName: String,
    onConnectToSync: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = SurfaceElevated),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                if (mode == RemoteSourceMode.LOCAL) Icons.Default.PhoneAndroid else Icons.Default.CastConnected,
                null,
                tint = AccentPink,
                modifier = Modifier.size(24.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = if (mode == RemoteSourceMode.LOCAL) "Modo actual" else "Controlando",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextMuted,
                )
                Text(
                    text = if (mode == RemoteSourceMode.LOCAL) "Reproduciendo en este teléfono" else sourceName,
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextPrimary,
                    fontWeight = FontWeight.Medium,
                )
            }
            if (mode == RemoteSourceMode.LOCAL) {
                Button(onClick = onConnectToSync, modifier = Modifier.height(32.dp)) {
                    Text("Sync", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

private fun formatTime(ms: Long): String {
    if (ms < 0) return "0:00"
    val totalSec = ms / 1000
    val min = totalSec / 60
    val sec = totalSec % 60
    return "%d:%02d".format(min, sec)
}
