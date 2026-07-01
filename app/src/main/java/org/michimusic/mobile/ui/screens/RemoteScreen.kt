@file:Suppress("DEPRECATION")
package org.michimusic.mobile.ui.screens

import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import org.koin.androidx.compose.koinViewModel
import org.michimusic.mobile.remote.RemoteViewModel
import org.michimusic.mobile.ui.theme.AccentPink
import org.michimusic.mobile.ui.theme.SurfaceDark
import org.michimusic.mobile.ui.theme.TextDim
import org.michimusic.mobile.ui.theme.TextPrimary
import org.michimusic.mobile.ui.theme.TextSecondary
import org.michimusic.mobile.ui.components.MichiActionButton
import org.michimusic.mobile.ui.components.MichiButtonStyle
import org.michimusic.mobile.ui.theme.MichiSpacing

@Composable
fun RemoteScreen(
    onNavigateToSync: () -> Unit = {},
    viewModel: RemoteViewModel = koinViewModel(),
) {
    val playerState by viewModel.playerState.collectAsStateWithLifecycle()
    val connected by viewModel.connected.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        viewModel.connectIfNeeded()
    }

    Box(modifier = Modifier.fillMaxSize().background(SurfaceDark)) {
        if (!connected) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(
                    imageVector = Icons.Default.Error,
                    contentDescription = null,
                    modifier = Modifier.size(64.dp),
                    tint = TextDim,
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    "No hay conexión remota",
                    style = MaterialTheme.typography.headlineSmall,
                    color = TextDim,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Conéctate a un servidor Michi para controlar la reproducción desde tu teléfono",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(24.dp))
                MichiActionButton(
                    text = "Ir a Sync",
                    icon = Icons.Default.PlayArrow,
                    onClick = onNavigateToSync,
                    style = MichiButtonStyle.PRIMARY_GLOW,
                )
            }
        } else {
            var expanded by remember { mutableStateOf(false) }
            var currentVolume by remember(playerState.volume) {
                mutableFloatStateOf(playerState.volume.toFloat())
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 24.dp, vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    TextButton(onClick = { expanded = true }) {
                        Text(
                            text = "Michi Desktop (KDE)",
                            style = MaterialTheme.typography.titleSmall,
                            color = TextSecondary,
                        )
                        Icon(
                            imageVector = Icons.Default.ArrowDropDown,
                            contentDescription = null,
                            tint = TextSecondary,
                        )
                    }
                    DropdownMenu(
                        expanded = expanded,
                        onDismissRequest = { expanded = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text("Michi Desktop (KDE)", color = TextPrimary) },
                            onClick = { expanded = false },
                        )
                        DropdownMenuItem(
                            text = { Text("Reproductor Local", color = TextPrimary) },
                            onClick = { expanded = false },
                        )
                    }
                }

                Spacer(Modifier.weight(0.5f))

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .aspectRatio(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center,
                ) {
                    if (playerState.coverUrl.isNotEmpty()) {
                        AsyncImage(
                            model = playerState.coverUrl,
                            contentDescription = playerState.title,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop,
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.Error,
                            contentDescription = null,
                            modifier = Modifier.size(48.dp),
                            tint = TextDim,
                        )
                    }
                }

                Spacer(Modifier.height(16.dp))

                Text(
                    text = playerState.title.ifEmpty { "Sin reproducción" },
                    style = MaterialTheme.typography.titleLarge,
                    color = TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (playerState.artist.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = playerState.artist,
                        style = MaterialTheme.typography.bodyLarge,
                        color = TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                Spacer(Modifier.weight(0.5f))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    IconButton(
                        onClick = viewModel::previous,
                        modifier = Modifier.size(56.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Default.SkipPrevious,
                            contentDescription = "Anterior",
                            modifier = Modifier.size(36.dp),
                            tint = TextPrimary,
                        )
                    }

                    Spacer(Modifier.width(24.dp))

                    IconButton(
                        onClick = viewModel::togglePlayPause,
                        modifier = Modifier.size(80.dp),
                    ) {
                        Icon(
                            imageVector = if (playerState.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = if (playerState.isPlaying) "Pausar" else "Reproducir",
                            modifier = Modifier.size(64.dp),
                            tint = AccentPink,
                        )
                    }

                    Spacer(Modifier.width(24.dp))

                    IconButton(
                        onClick = viewModel::next,
                        modifier = Modifier.size(56.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Default.SkipNext,
                            contentDescription = "Siguiente",
                            modifier = Modifier.size(36.dp),
                            tint = TextPrimary,
                        )
                    }
                }

                Spacer(Modifier.height(24.dp))

                Slider(
                    value = currentVolume,
                    onValueChange = { currentVolume = it },
                    onValueChangeFinished = {
                        viewModel.setVolume(currentVolume.toInt())
                    },
                    valueRange = 0f..100f,
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(Modifier.height(8.dp))
            }
        }

        error?.let { msg ->
            Snackbar(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(16.dp),
                action = {
                    TextButton(onClick = { viewModel.clearError() }) {
                        Text("OK")
                    }
                },
            ) {
                Text(msg)
            }
        }
    }
}
