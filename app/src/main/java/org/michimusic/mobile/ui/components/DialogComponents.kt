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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Speaker
import androidx.compose.material.icons.filled.Usb
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.runtime.collectAsState
import org.michimusic.mobile.ui.theme.GlassBorderHigh
import org.michimusic.mobile.ui.theme.GlassBorderLow
import org.michimusic.mobile.ui.theme.GlassFillHigh
import org.michimusic.mobile.ui.theme.GlassFillLow
import org.michimusic.mobile.ui.theme.OnSurfaceVariant
import org.michimusic.mobile.ui.theme.PrimaryPink
import org.michimusic.mobile.ui.theme.PrimaryPinkContainer
import org.michimusic.mobile.ui.theme.PureWhite
import org.michimusic.mobile.ui.theme.SecondaryPurple
import org.michimusic.mobile.ui.theme.SurfaceContainer
import org.michimusic.mobile.ui.theme.SurfaceObsidian
import org.michimusic.mobile.ui.theme.TertiaryCyan
import org.michimusic.mobile.ui.theme.TertiaryCyanContainer
import org.michimusic.mobile.ui.theme.TextMuted
import org.michimusic.mobile.ui.theme.TextSecondary

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
                            imageVector = Icons.AutoMirrored.Filled.QueueMusic,
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
                                        imageModel = track.coverId,
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
    val audioEffects: org.michimusic.player.MichiAudioEffects? = runCatching { org.koin.compose.koinInject<org.michimusic.player.MichiAudioEffects>() }.getOrNull()
    val usbDacManager: org.michimusic.player.UsbDacManager? = runCatching { org.koin.compose.koinInject<org.michimusic.player.UsbDacManager>() }.getOrNull()
    val effectsState by (audioEffects?.effectsState?.collectAsState() ?: remember {
        mutableStateOf(org.michimusic.player.AudioEffectsState())
    })
    val dacInfo by (usbDacManager?.dacState?.collectAsState() ?: remember {
        mutableStateOf(org.michimusic.player.UsbDacInfo())
    })

    val presets = listOf("Synthwave", "Bass Boost", "Cyberpunk", "Vocal", "Acoustic", "Electronic", "Flat")

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = SurfaceObsidian,
            border = androidx.compose.foundation.BorderStroke(1.2.dp, GlassBorderHigh),
            modifier = Modifier
                .fillMaxWidth()
                .padding(4.dp)
                .testTag("equalizer_dialog"),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(TertiaryCyan.copy(alpha = 0.15f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = Icons.Filled.GraphicEq,
                                contentDescription = null,
                                tint = TertiaryCyan,
                                modifier = Modifier.size(20.dp),
                            )
                        }

                        Column {
                            Text(
                                text = "Ecualizador de Audio",
                                color = PureWhite,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                            )
                            if (dacInfo.isConnected) {
                                Text(
                                    text = "DAC USB: ${dacInfo.deviceName.take(16)}",
                                    color = TertiaryCyan,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                        }
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Switch(
                            checked = effectsState.isEnabled,
                            onCheckedChange = { audioEffects?.setEnabled(it) },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = PureWhite,
                                checkedTrackColor = TertiaryCyanContainer,
                                uncheckedThumbColor = TextMuted,
                                uncheckedTrackColor = GlassFillHigh,
                            ),
                        )

                        IconButton(
                            onClick = onDismiss,
                            modifier = Modifier.size(32.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Close,
                                contentDescription = "Cerrar",
                                tint = TextSecondary,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Frequency Curve Visualizer Canvas
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(68.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(GlassFillLow)
                        .border(1.dp, GlassBorderLow, RoundedCornerShape(12.dp))
                        .padding(8.dp),
                ) {
                    androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize()) {
                        val width = size.width
                        val height = size.height
                        val midY = height / 2f
                        val bands = effectsState.bands
                        if (bands.isNotEmpty()) {
                            val path = androidx.compose.ui.graphics.Path()
                            val points = bands.mapIndexed { idx, band ->
                                val x = (idx.toFloat() / (bands.size - 1).coerceAtLeast(1)) * width
                                val normalizedGain = (band.currentGainMilliDb.toFloat() / (band.maxGainMilliDb.toFloat().coerceAtLeast(1f))).coerceIn(-1f, 1f)
                                val y = midY - (normalizedGain * (midY * 0.8f))
                                androidx.compose.ui.geometry.Offset(x, y)
                            }

                            path.moveTo(points.first().x, points.first().y)
                            for (i in 1 until points.size) {
                                val prev = points[i - 1]
                                val cur = points[i]
                                val cX = (prev.x + cur.x) / 2f
                                path.cubicTo(cX, prev.y, cX, cur.y, cur.x, cur.y)
                            }

                            drawPath(
                                path = path,
                                brush = Brush.horizontalGradient(listOf(TertiaryCyan, PrimaryPink)),
                                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round),
                            )

                            points.forEach { pt ->
                                drawCircle(
                                    color = TertiaryCyan,
                                    radius = 4.dp.toPx(),
                                    center = pt,
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Presets Horizontal Row
                androidx.compose.foundation.lazy.LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(presets) { preset ->
                        val isSelected = effectsState.selectedPreset == preset
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (isSelected) TertiaryCyan.copy(alpha = 0.25f) else GlassFillLow)
                                .border(
                                    1.dp,
                                    if (isSelected) TertiaryCyan else GlassBorderLow,
                                    RoundedCornerShape(8.dp),
                                )
                                .clickable { audioEffects?.applyPreset(preset) }
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = preset,
                                color = if (isSelected) TertiaryCyan else PureWhite,
                                fontSize = 11.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Equalizer Bands
                Column(
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    effectsState.bands.forEach { band ->
                        val freqLabel = if (band.centerFreqHz >= 1000) "${band.centerFreqHz / 1000} kHz" else "${band.centerFreqHz} Hz"
                        val gainDb = band.currentGainMilliDb / 100f

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = freqLabel,
                                color = PureWhite,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.width(52.dp),
                            )
                            Slider(
                                value = band.currentGainMilliDb.toFloat(),
                                onValueChange = { audioEffects?.setBandGain(band.index, it.toInt()) },
                                valueRange = band.minGainMilliDb.toFloat()..band.maxGainMilliDb.toFloat(),
                                modifier = Modifier.weight(1f),
                                colors = SliderDefaults.colors(
                                    thumbColor = TertiaryCyan,
                                    activeTrackColor = TertiaryCyan,
                                    inactiveTrackColor = GlassBorderLow,
                                ),
                            )
                            Text(
                                text = "${if (gainDb > 0) "+" else ""}${String.format("%.1f", gainDb)} dB",
                                color = if (gainDb != 0f) TertiaryCyan else TextMuted,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.width(54.dp),
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // BassBoost and 3D Virtualizer Sliders
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Bass Boost (${effectsState.bassBoostStrength / 10}%)",
                            fontSize = 11.sp,
                            color = TextSecondary,
                        )
                        Slider(
                            value = effectsState.bassBoostStrength.toFloat(),
                            onValueChange = { audioEffects?.setBassBoost(it.toInt()) },
                            valueRange = 0f..1000f,
                            colors = SliderDefaults.colors(
                                thumbColor = PrimaryPink,
                                activeTrackColor = PrimaryPink,
                                inactiveTrackColor = GlassBorderLow,
                            ),
                        )
                    }

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Virtualizer 3D (${effectsState.virtualizerStrength / 10}%)",
                            fontSize = 11.sp,
                            color = TextSecondary,
                        )
                        Slider(
                            value = effectsState.virtualizerStrength.toFloat(),
                            onValueChange = { audioEffects?.setVirtualizer(it.toInt()) },
                            valueRange = 0f..1000f,
                            colors = SliderDefaults.colors(
                                thumbColor = SecondaryPurple,
                                activeTrackColor = SecondaryPurple,
                                inactiveTrackColor = GlassBorderLow,
                            ),
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                Button(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = PrimaryPinkContainer),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text("Aplicar & Guardar", color = PureWhite, fontWeight = FontWeight.Bold)
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

@Composable
fun AudioRouteDialog(
    onDismiss: () -> Unit,
) {
    val usbDacManager: org.michimusic.player.UsbDacManager? = runCatching { org.koin.compose.koinInject<org.michimusic.player.UsbDacManager>() }.getOrNull()
    val outputDevices by (usbDacManager?.outputDevices?.collectAsState() ?: remember { mutableStateOf(emptyList()) })
    val dacInfo by (usbDacManager?.dacState?.collectAsState() ?: remember { mutableStateOf(org.michimusic.player.UsbDacInfo()) })
    val selectedId by (usbDacManager?.selectedDeviceId?.collectAsState() ?: remember { mutableStateOf(null) })
    val linkDiscovery: org.michimusic.link.LinkDiscovery? = runCatching { org.koin.compose.koinInject<org.michimusic.link.LinkDiscovery>() }.getOrNull()
    val linkSession: org.michimusic.link.LinkSession? = runCatching { org.koin.compose.koinInject<org.michimusic.link.LinkSession>() }.getOrNull()
    val peersMap by (linkDiscovery?.peers?.collectAsState() ?: remember { mutableStateOf(emptyMap()) })
    val activePeer by (linkSession?.connectedPeer?.collectAsState() ?: remember { mutableStateOf(null) })
    val peersList = remember(peersMap) { peersMap.values.toList() }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = SurfaceObsidian,
            border = androidx.compose.foundation.BorderStroke(1.2.dp, GlassBorderHigh),
            modifier = Modifier
                .fillMaxWidth()
                .padding(4.dp)
                .testTag("audio_route_dialog"),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(TertiaryCyan.copy(alpha = 0.15f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Headphones,
                                contentDescription = null,
                                tint = TertiaryCyan,
                                modifier = Modifier.size(20.dp),
                            )
                        }

                        Column {
                            Text(
                                text = "Salidas de Audio & DAC",
                                color = PureWhite,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                text = "Selector de Enrutamiento Hi-Res",
                                color = TextSecondary,
                                fontSize = 11.sp,
                            )
                        }
                    }

                    IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = "Cerrar",
                            tint = TextSecondary,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // USB DAC Direct Status Banner
                if (dacInfo.isConnected) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(TertiaryCyan.copy(alpha = 0.12f))
                            .border(1.dp, TertiaryCyan.copy(alpha = 0.45f), RoundedCornerShape(14.dp))
                            .padding(12.dp),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Usb,
                                contentDescription = null,
                                tint = TertiaryCyan,
                                modifier = Modifier.size(22.dp),
                            )
                            Column {
                                Text(
                                    text = "DISPOSITIVO USB DETECTADO",
                                    color = TertiaryCyan,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 1.sp,
                                )
                                Text(
                                    text = dacInfo.deviceName,
                                    color = PureWhite,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                if (dacInfo.sampleRates.isNotEmpty()) {
                                    Text(
                                        text = "Frecuencias reportadas: ${dacInfo.sampleRates.map { "${it / 1000}kHz" }.joinToString(", ")}",
                                        color = TextMuted,
                                        fontSize = 10.sp,
                                    )
                                }
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                }

                Text(
                    text = "DISPOSITIVOS DETECTADOS",
                    color = TextSecondary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.5.sp,
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Local Device List
                LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(outputDevices) { device ->
                        val isCurrentSelected = (selectedId == device.id) || (selectedId == null && device.isSelected)
                        val icon = when {
                            device.isUsbDac -> Icons.Filled.Usb
                            device.isBluetooth -> Icons.Filled.Bluetooth
                            device.isWired -> Icons.Filled.Headphones
                            else -> Icons.Filled.Speaker
                        }

                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .background(if (isCurrentSelected) TertiaryCyan.copy(alpha = 0.15f) else GlassFillLow)
                                .border(
                                    1.dp,
                                    if (isCurrentSelected) TertiaryCyan else GlassBorderLow,
                                    RoundedCornerShape(14.dp),
                                )
                                .clickable { usbDacManager?.selectDevice(device.id) }
                                .padding(12.dp),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    modifier = Modifier.weight(1f),
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(36.dp)
                                            .clip(CircleShape)
                                            .background(if (isCurrentSelected) TertiaryCyan.copy(alpha = 0.25f) else GlassFillHigh),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Icon(
                                            imageVector = icon,
                                            contentDescription = null,
                                            tint = if (isCurrentSelected) TertiaryCyan else PureWhite,
                                            modifier = Modifier.size(20.dp),
                                        )
                                    }

                                    Column {
                                        Text(
                                            text = device.name,
                                            color = if (isCurrentSelected) TertiaryCyan else PureWhite,
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.SemiBold,
                                        )
                                        Text(
                                            text = if (device.isUsbDac) "Audio Digital USB" else if (device.isBluetooth) "Inalámbrico Bluetooth" else if (device.isWired) "Auriculares Cableados" else "Altavoz del Dispositivo",
                                            color = TextMuted,
                                            fontSize = 10.sp,
                                        )
                                    }
                                }

                                if (isCurrentSelected) {
                                    Box(
                                        modifier = Modifier
                                            .size(24.dp)
                                            .clip(CircleShape)
                                            .background(TertiaryCyan),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Icon(
                                            imageVector = Icons.Filled.Check,
                                            contentDescription = "Activo",
                                            tint = SurfaceObsidian,
                                            modifier = Modifier.size(16.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Michi Link Ecosystem Nodes Section
                    if (peersList.isNotEmpty()) {
                        item {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "NODOS MICHI LINK DISPONIBLES",
                                color = PrimaryPink,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.5.sp,
                            )
                        }

                        items(peersList) { peer ->
                            val isConnectedToPeer = activePeer?.ip == peer.ip
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(if (isConnectedToPeer) PrimaryPinkContainer.copy(alpha = 0.18f) else GlassFillLow)
                                    .border(
                                        1.dp,
                                        if (isConnectedToPeer) PrimaryPink else GlassBorderLow,
                                        RoundedCornerShape(14.dp),
                                    )
                                    .padding(12.dp),
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                                        modifier = Modifier.weight(1f),
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(36.dp)
                                                .clip(CircleShape)
                                                .background(if (isConnectedToPeer) PrimaryPink.copy(alpha = 0.25f) else GlassFillHigh),
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            Icon(
                                                imageVector = Icons.Filled.Speaker,
                                                contentDescription = null,
                                                tint = if (isConnectedToPeer) PrimaryPink else PureWhite,
                                                modifier = Modifier.size(20.dp),
                                            )
                                        }

                                        Column {
                                            Text(
                                                text = peer.alias.ifEmpty { "Nodo Michi" },
                                                color = if (isConnectedToPeer) PrimaryPink else PureWhite,
                                                fontSize = 13.sp,
                                                fontWeight = FontWeight.SemiBold,
                                            )
                                            Text(
                                                text = "${peer.deviceType.uppercase()} • ${peer.ip}",
                                                color = TextMuted,
                                                fontSize = 10.sp,
                                            )
                                        }
                                    }

                                    if (isConnectedToPeer) {
                                        Box(
                                            modifier = Modifier
                                                .size(24.dp)
                                                .clip(CircleShape)
                                                .background(PrimaryPink),
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            Icon(
                                                imageVector = Icons.Filled.Check,
                                                contentDescription = "Conectado",
                                                tint = SurfaceObsidian,
                                                modifier = Modifier.size(16.dp),
                                            )
                                        }
                                    }
                                }
                            }
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
                    Text("Listo", color = PureWhite, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

