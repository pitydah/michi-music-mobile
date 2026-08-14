package org.michimusic.mobile.ui.components

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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import org.michimusic.core.models.Track
import org.michimusic.mobile.ui.theme.GlassBorderHigh
import org.michimusic.mobile.ui.theme.GlassBorderLow
import org.michimusic.mobile.ui.theme.GlassFillHigh
import org.michimusic.mobile.ui.theme.GlassFillLow
import org.michimusic.mobile.ui.theme.OnSurfaceVariant
import org.michimusic.mobile.ui.theme.PrimaryPink
import org.michimusic.mobile.ui.theme.PrimaryPinkContainer
import org.michimusic.mobile.ui.theme.PureWhite
import org.michimusic.mobile.ui.theme.SurfaceContainer
import org.michimusic.mobile.ui.theme.SurfaceObsidian
import org.michimusic.mobile.ui.theme.TertiaryCyan
import org.michimusic.mobile.ui.theme.TertiaryCyanContainer

data class EqBand(val frequencyLabel: String, var gainDb: Float)

@Composable
fun QueueDialog(
    queue: List<Track>,
    currentTrack: Track?,
    onTrackSelect: (Track) -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = SurfaceContainer,
            border = androidx.compose.foundation.BorderStroke(1.dp, GlassBorderHigh),
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp)
                .testTag("queue_dialog"),
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
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Filled.QueueMusic,
                            contentDescription = null,
                            tint = TertiaryCyan,
                            modifier = Modifier.size(24.dp),
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Cola de Reproducción",
                            color = PureWhite,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = "Cerrar",
                            tint = OnSurfaceVariant,
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                if (queue.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(160.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("No hay canciones en cola", color = OnSurfaceVariant, fontSize = 14.sp)
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(320.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(queue) { track ->
                            val isCurrent = track.id == currentTrack?.id
                            GlassCard(
                                modifier = Modifier.fillMaxWidth(),
                                backgroundColor = if (isCurrent) GlassFillHigh else GlassFillLow,
                                borderColor = if (isCurrent) TertiaryCyan else GlassBorderLow,
                                onClick = {
                                    onTrackSelect(track)
                                    onDismiss()
                                },
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    AlbumArtView(
                                        coverStyle = coverStyleFor(track.coverId.ifEmpty { track.title }),
                                        imageModel = track.filepath.ifEmpty { track.coverId },
                                        modifier = Modifier.size(40.dp),
                                        cornerRadius = 8.dp,
                                    )
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = track.title,
                                            color = if (isCurrent) TertiaryCyan else PureWhite,
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.SemiBold,
                                        )
                                        Text(
                                            text = track.artist,
                                            color = OnSurfaceVariant,
                                            fontSize = 12.sp,
                                        )
                                    }
                                    if (isCurrent) {
                                        EqualizerWaveBars(isPlaying = true, color = TertiaryCyan)
                                    } else {
                                        Text(
                                            text = formatTimeMillis(track.duration),
                                            color = OnSurfaceVariant,
                                            fontSize = 11.sp,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun EqualizerDialog(
    onDismiss: () -> Unit,
) {
    var selectedPreset by remember { mutableStateOf("Synthwave") }
    val presets = listOf("Flat", "Synthwave", "Bass Boost", "Vocal", "Cyberpunk")
    val bands = remember {
        mutableStateListOf(
            EqBand("60 Hz", 4.0f),
            EqBand("230 Hz", 2.5f),
            EqBand("910 Hz", -1.0f),
            EqBand("4 kHz", 3.0f),
            EqBand("14 kHz", 5.5f),
        )
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = SurfaceContainer,
            border = androidx.compose.foundation.BorderStroke(1.dp, GlassBorderHigh),
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp)
                .testTag("equalizer_dialog"),
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
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Filled.GraphicEq,
                            contentDescription = null,
                            tint = TertiaryCyan,
                            modifier = Modifier.size(24.dp),
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Ecualizador de Audio",
                            color = PureWhite,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = "Cerrar",
                            tint = OnSurfaceVariant,
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Preset Pills
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    presets.take(4).forEach { preset ->
                        val isSelected = selectedPreset == preset
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (isSelected) TertiaryCyanContainer else GlassFillLow)
                                .border(
                                    1.dp,
                                    if (isSelected) TertiaryCyan else GlassBorderLow,
                                    RoundedCornerShape(8.dp),
                                )
                                .clickable {
                                    selectedPreset = preset
                                    when (preset) {
                                        "Flat" -> bands.forEach { it.gainDb = 0f }
                                        "Synthwave" -> {
                                            bands[0].gainDb = 4f; bands[1].gainDb = 2f; bands[2].gainDb = -1f; bands[3].gainDb = 3f; bands[4].gainDb = 6f
                                        }
                                        "Bass Boost" -> {
                                            bands[0].gainDb = 7f; bands[1].gainDb = 5f; bands[2].gainDb = 1f; bands[3].gainDb = 0f; bands[4].gainDb = 0f
                                        }
                                        "Vocal" -> {
                                            bands[0].gainDb = -2f; bands[1].gainDb = 1f; bands[2].gainDb = 4f; bands[3].gainDb = 3f; bands[4].gainDb = 1f
                                        }
                                    }
                                }
                                .padding(vertical = 6.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = preset,
                                color = if (isSelected) SurfaceObsidian else PureWhite,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Bands sliders
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    bands.forEachIndexed { index, band ->
                        var gain by remember(band.gainDb) { mutableFloatStateOf(band.gainDb) }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = band.frequencyLabel,
                                color = PureWhite,
                                fontSize = 12.sp,
                                modifier = Modifier.width(54.dp),
                            )
                            Slider(
                                value = gain,
                                onValueChange = {
                                    gain = it
                                    band.gainDb = it
                                },
                                valueRange = -12f..12f,
                                modifier = Modifier.weight(1f),
                                colors = SliderDefaults.colors(
                                    thumbColor = TertiaryCyan,
                                    activeTrackColor = TertiaryCyanContainer,
                                    inactiveTrackColor = Color(0x33FFFFFF),
                                ),
                            )
                            Text(
                                text = "${if (gain > 0) "+" else ""}${String.format("%.1f", gain)} dB",
                                color = TertiaryCyan,
                                fontSize = 11.sp,
                                modifier = Modifier.width(54.dp),
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Button(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = PrimaryPinkContainer),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text("Aplicar Configuración", color = PureWhite, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
fun QrScannerDialog(
    onScanSuccess: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val transition = rememberInfiniteTransition(label = "scanner_laser")
    val laserY by transition.animateFloat(
        initialValue = 0.1f,
        targetValue = 0.9f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "laser_pos",
    )

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = SurfaceContainer,
            border = androidx.compose.foundation.BorderStroke(1.dp, GlassBorderHigh),
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp)
                .testTag("qr_scanner_dialog"),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Escanear QR de Escritorio",
                        color = PureWhite,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = "Cerrar",
                            tint = OnSurfaceVariant,
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Apunta la cámara al código QR mostrado en Michi Music Desktop para emparejar.",
                    color = OnSurfaceVariant,
                    fontSize = 13.sp,
                )

                Spacer(modifier = Modifier.height(20.dp))

                Box(
                    modifier = Modifier
                        .size(200.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(SurfaceObsidian)
                        .border(2.dp, TertiaryCyan, RoundedCornerShape(16.dp))
                        .padding(16.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Filled.QrCodeScanner,
                        contentDescription = null,
                        tint = Color(0x337DF4FF),
                        modifier = Modifier.size(100.dp),
                    )

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(2.dp)
                            .align(Alignment.TopCenter)
                            .padding(top = (160 * laserY).dp)
                            .background(
                                Brush.horizontalGradient(
                                    listOf(Color.Transparent, TertiaryCyan, Color.Transparent),
                                ),
                            ),
                    )
                }

                Spacer(modifier = Modifier.height(20.dp))

                Button(
                    onClick = {
                        onScanSuccess("Michi-Desktop-AutoPaired")
                        onDismiss()
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = TertiaryCyanContainer),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text("Emparejar Automáticamente", color = SurfaceObsidian, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
fun ManualConnectionDialog(
    onConnect: (name: String, ip: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var serverName by remember { mutableStateOf("Desktop-Linux") }
    var ipAddress by remember { mutableStateOf("192.168.1.50:7331") }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = SurfaceContainer,
            border = androidx.compose.foundation.BorderStroke(1.dp, GlassBorderHigh),
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp)
                .testTag("manual_connection_dialog"),
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
                        text = "Conexión Manual",
                        color = PureWhite,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = "Cerrar",
                            tint = OnSurfaceVariant,
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                OutlinedTextField(
                    value = serverName,
                    onValueChange = { serverName = it },
                    label = { Text("Nombre del Servidor") },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = PureWhite,
                        unfocusedTextColor = PureWhite,
                        focusedBorderColor = TertiaryCyan,
                        unfocusedBorderColor = GlassBorderLow,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(modifier = Modifier.height(8.dp))

                OutlinedTextField(
                    value = ipAddress,
                    onValueChange = { ipAddress = it },
                    label = { Text("Dirección IP y Puerto") },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = PureWhite,
                        unfocusedTextColor = PureWhite,
                        focusedBorderColor = TertiaryCyan,
                        unfocusedBorderColor = GlassBorderLow,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(modifier = Modifier.height(16.dp))

                Button(
                    onClick = {
                        onConnect(serverName, ipAddress)
                        onDismiss()
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = PrimaryPinkContainer),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text("Conectar Ahora", color = PureWhite, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
fun CreatePlaylistDialog(
    onCreate: (name: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var playlistName by remember { mutableStateOf("") }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = SurfaceContainer,
            border = androidx.compose.foundation.BorderStroke(1.dp, GlassBorderHigh),
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp)
                .testTag("create_playlist_dialog"),
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
                        text = "Nueva Playlist",
                        color = PureWhite,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = "Cerrar",
                            tint = OnSurfaceVariant,
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                OutlinedTextField(
                    value = playlistName,
                    onValueChange = { playlistName = it },
                    placeholder = { Text("Ej. Cyberpunk Chill", color = OnSurfaceVariant) },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = PureWhite,
                        unfocusedTextColor = PureWhite,
                        focusedBorderColor = PrimaryPink,
                        unfocusedBorderColor = GlassBorderLow,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(modifier = Modifier.height(16.dp))

                Button(
                    onClick = {
                        if (playlistName.isNotBlank()) {
                            onCreate(playlistName)
                            onDismiss()
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = PrimaryPinkContainer),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text("Crear Playlist", color = PureWhite, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}
