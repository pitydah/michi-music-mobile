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
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.LaptopMac
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Speaker
import androidx.compose.material.icons.filled.SurroundSound
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material3.OutlinedButton
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import org.michimusic.core.models.Track
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.runtime.collectAsState
import org.michimusic.mobile.ui.theme.*

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

    var selectedTab by remember { mutableStateOf(0) }

    val presets = listOf(
        "Michi Signature",
        "Flat / Plano",
        "Bass Punch",
        "Treble Clarity",
        "Vocal Presence",
        "Cyberpunk EDM",
        "Acoustic Hi-Fi",
        "Rock & Metal",
    )

    Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = SurfaceObsidian,
            border = androidx.compose.foundation.BorderStroke(1.dp, GlassBorderHigh),
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .padding(vertical = 12.dp)
                .testTag("equalizer_dialog"),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
            ) {
                // Header Bar
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (effectsState.isEnabled) TertiaryCyan.copy(alpha = 0.2f) else GlassFillLow)
                                .border(
                                    1.dp,
                                    if (effectsState.isEnabled) TertiaryCyan.copy(alpha = 0.6f) else GlassBorderLow,
                                    RoundedCornerShape(12.dp),
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = Icons.Filled.GraphicEq,
                                contentDescription = null,
                                tint = if (effectsState.isEnabled) TertiaryCyan else TextMuted,
                                modifier = Modifier.size(22.dp),
                            )
                        }

                        Column {
                            Text(
                                text = "Ecualizador & DSP Studio",
                                color = PureWhite,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                            )
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Text(
                                    text = if (effectsState.isEnabled) "DSP ACTIVO" else "BYPASS",
                                    color = if (effectsState.isEnabled) TertiaryCyan else TextMuted,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                )
                                if (dacInfo.isConnected) {
                                    Text(text = "•", color = TextMuted, fontSize = 10.sp)
                                    Text(
                                        text = "DAC: ${dacInfo.deviceName.take(14)}",
                                        color = PrimaryPink,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                    )
                                }
                            }
                        }
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        IconButton(
                            onClick = { audioEffects?.resetToFlat() },
                            modifier = Modifier.size(48.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Filled.RestartAlt,
                                contentDescription = "Restablecer a Plano",
                                tint = OnSurfaceVariant,
                                modifier = Modifier.size(20.dp),
                            )
                        }

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
                            modifier = Modifier.size(48.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Close,
                                contentDescription = "Cerrar",
                                tint = OnSurfaceVariant,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // High-End Frequency Response Curve Visualizer
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(84.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(SurfaceContainerLowest)
                        .border(1.dp, GlassBorderLow, RoundedCornerShape(16.dp))
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize()) {
                        val width = size.width
                        val height = size.height
                        val midY = height / 2f

                        val gridColor = Color(0x22FFFFFF)
                        val centerLineColor = if (effectsState.isEnabled) TertiaryCyan.copy(alpha = 0.35f) else Color(0x33FFFFFF)

                        drawLine(color = gridColor, start = androidx.compose.ui.geometry.Offset(0f, height * 0.15f), end = androidx.compose.ui.geometry.Offset(width, height * 0.15f), strokeWidth = 1f)
                        drawLine(color = centerLineColor, start = androidx.compose.ui.geometry.Offset(0f, midY), end = androidx.compose.ui.geometry.Offset(width, midY), strokeWidth = 1.5f)
                        drawLine(color = gridColor, start = androidx.compose.ui.geometry.Offset(0f, height * 0.85f), end = androidx.compose.ui.geometry.Offset(width, height * 0.85f), strokeWidth = 1f)

                        val bands = effectsState.bands
                        if (bands.isNotEmpty()) {
                            val path = androidx.compose.ui.graphics.Path()
                            val fillPath = androidx.compose.ui.graphics.Path()

                            val points = bands.mapIndexed { idx, band ->
                                val x = (idx.toFloat() / (bands.size - 1).coerceAtLeast(1)) * width
                                val rawGain = if (effectsState.isEnabled) band.currentGainMilliDb.toFloat() else 0f
                                val normalizedGain = (rawGain / (band.maxGainMilliDb.toFloat().coerceAtLeast(1f))).coerceIn(-1f, 1f)
                                val y = midY - (normalizedGain * (midY * 0.75f))
                                androidx.compose.ui.geometry.Offset(x, y)
                            }

                            path.moveTo(points.first().x, points.first().y)
                            fillPath.moveTo(points.first().x, midY)
                            fillPath.lineTo(points.first().x, points.first().y)

                            for (i in 1 until points.size) {
                                val prev = points[i - 1]
                                val cur = points[i]
                                val cX = (prev.x + cur.x) / 2f
                                path.cubicTo(cX, prev.y, cX, cur.y, cur.x, cur.y)
                                fillPath.cubicTo(cX, prev.y, cX, cur.y, cur.x, cur.y)
                            }

                            fillPath.lineTo(points.last().x, midY)
                            fillPath.close()

                            if (effectsState.isEnabled) {
                                drawPath(
                                    path = fillPath,
                                    brush = Brush.verticalGradient(
                                        colors = listOf(
                                            PrimaryPink.copy(alpha = 0.25f),
                                            TertiaryCyan.copy(alpha = 0.08f),
                                            Color.Transparent,
                                        ),
                                    ),
                                )
                            }

                            val curveBrush = if (effectsState.isEnabled) {
                                Brush.horizontalGradient(listOf(TertiaryCyan, PrimaryPink))
                            } else {
                                Brush.horizontalGradient(listOf(TextMuted, TextMuted))
                            }

                            drawPath(
                                path = path,
                                brush = curveBrush,
                                style = androidx.compose.ui.graphics.drawscope.Stroke(
                                    width = 2.5.dp.toPx(),
                                    cap = androidx.compose.ui.graphics.StrokeCap.Round,
                                ),
                            )

                            points.forEach { pt ->
                                drawCircle(
                                    color = if (effectsState.isEnabled) TertiaryCyan else TextMuted,
                                    radius = 3.5.dp.toPx(),
                                    center = pt,
                                )
                                if (effectsState.isEnabled) {
                                    drawCircle(
                                        color = PureWhite,
                                        radius = 1.8.dp.toPx(),
                                        center = pt,
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Presets Carousel
                androidx.compose.foundation.lazy.LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(presets) { preset ->
                        val isSelected = effectsState.selectedPreset == preset
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(20.dp))
                                .background(if (isSelected) PrimaryPinkContainer else GlassFillLow)
                                .border(
                                    1.dp,
                                    if (isSelected) PrimaryPink else GlassBorderLow,
                                    RoundedCornerShape(20.dp),
                                )
                                .clickable { audioEffects?.applyPreset(preset) }
                                .padding(horizontal = 14.dp, vertical = 7.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = preset,
                                color = if (isSelected) PureWhite else OnSurfaceVariant,
                                fontSize = 11.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Tab Selector
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(SurfaceContainerLowest)
                        .padding(3.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (selectedTab == 0) SurfaceContainer else Color.Transparent)
                            .clickable { selectedTab = 0 }
                            .padding(vertical = 8.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "Bandas Paramétricas",
                            color = if (selectedTab == 0) PureWhite else TextMuted,
                            fontSize = 12.sp,
                            fontWeight = if (selectedTab == 0) FontWeight.Bold else FontWeight.Normal,
                        )
                    }

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (selectedTab == 1) SurfaceContainer else Color.Transparent)
                            .clickable { selectedTab = 1 }
                            .padding(vertical = 8.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "DSP & Dinámica",
                            color = if (selectedTab == 1) PureWhite else TextMuted,
                            fontSize = 12.sp,
                            fontWeight = if (selectedTab == 1) FontWeight.Bold else FontWeight.Normal,
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                if (selectedTab == 0) {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        effectsState.bands.forEach { band ->
                            val freqLabel = if (band.centerFreqHz >= 1000) "${band.centerFreqHz / 1000} kHz" else "${band.centerFreqHz} Hz"
                            val gainDb = band.currentGainMilliDb / 100f

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(GlassFillLow)
                                    .padding(horizontal = 10.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Box(
                                    modifier = Modifier
                                        .width(58.dp)
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(SurfaceContainerLowest)
                                        .padding(vertical = 4.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        text = freqLabel,
                                        color = PureWhite,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                    )
                                }

                                Spacer(modifier = Modifier.width(8.dp))

                                MichiFineSlider(
                                    value = band.currentGainMilliDb.toFloat(),
                                    onValueChange = { audioEffects?.setBandGain(band.index, it.toInt()) },
                                    valueRange = band.minGainMilliDb.toFloat()..band.maxGainMilliDb.toFloat(),
                                    enabled = effectsState.isEnabled,
                                    accentColor = TertiaryCyan,
                                    modifier = Modifier.weight(1f),
                                )

                                Spacer(modifier = Modifier.width(8.dp))

                                Box(
                                    modifier = Modifier.width(54.dp),
                                    contentAlignment = Alignment.CenterEnd,
                                ) {
                                    Text(
                                        text = "${if (gainDb > 0) "+" else ""}${String.format("%.1f", gainDb)} dB",
                                        color = if (gainDb > 0.05f) TertiaryCyan else if (gainDb < -0.05f) PrimaryPink else TextMuted,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                    )
                                }
                            }
                        }
                    }
                } else {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        // Preamp Slider
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(GlassFillLow)
                                .padding(12.dp),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(text = "Preamplificador Hi-Fi", fontSize = 12.sp, color = PureWhite, fontWeight = FontWeight.SemiBold)
                                Text(
                                    text = "${if (effectsState.preampGainDb > 0) "+" else ""}${String.format("%.1f", effectsState.preampGainDb)} dB",
                                    fontSize = 12.sp,
                                    color = if (effectsState.preampGainDb != 0f) TertiaryCyan else TextMuted,
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                            MichiFineSlider(
                                value = effectsState.preampGainDb,
                                onValueChange = { audioEffects?.setPreamp(it) },
                                valueRange = -12f..12f,
                                enabled = effectsState.isEnabled,
                                accentColor = TertiaryCyan,
                            )
                        }

                        // Bass Boost & Virtualizer Row
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            // Bass Boost
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(GlassFillLow)
                                    .padding(12.dp),
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    Text(text = "Bass Boost", fontSize = 11.sp, color = PureWhite, fontWeight = FontWeight.SemiBold)
                                    Text(
                                        text = "${effectsState.bassBoostStrength / 10}%",
                                        fontSize = 11.sp,
                                        color = PrimaryPink,
                                        fontWeight = FontWeight.Bold,
                                    )
                                }
                                MichiFineSlider(
                                    value = effectsState.bassBoostStrength.toFloat(),
                                    onValueChange = { audioEffects?.setBassBoost(it.toInt()) },
                                    valueRange = 0f..1000f,
                                    enabled = effectsState.isEnabled,
                                    accentColor = PrimaryPink,
                                )
                            }

                            // Virtualizer 3D
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(GlassFillLow)
                                    .padding(12.dp),
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    Text(text = "Espacio 3D", fontSize = 11.sp, color = PureWhite, fontWeight = FontWeight.SemiBold)
                                    Text(
                                        text = "${effectsState.virtualizerStrength / 10}%",
                                        fontSize = 11.sp,
                                        color = SecondaryPurple,
                                        fontWeight = FontWeight.Bold,
                                    )
                                }
                                MichiFineSlider(
                                    value = effectsState.virtualizerStrength.toFloat(),
                                    onValueChange = { audioEffects?.setVirtualizer(it.toInt()) },
                                    valueRange = 0f..1000f,
                                    enabled = effectsState.isEnabled,
                                    accentColor = SecondaryPurple,
                                )
                            }
                        }

                        // Loudness Enhancer
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(GlassFillLow)
                                .padding(12.dp),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(text = "Loudness Enhancer (Nivelador Dinámico)", fontSize = 12.sp, color = PureWhite, fontWeight = FontWeight.SemiBold)
                                Text(
                                    text = "+${String.format("%.1f", effectsState.loudnessGainMb / 100f)} dB",
                                    fontSize = 12.sp,
                                    color = if (effectsState.loudnessGainMb > 0) TertiaryCyan else TextMuted,
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                            MichiFineSlider(
                                value = effectsState.loudnessGainMb.toFloat(),
                                onValueChange = { audioEffects?.setLoudness(it.toInt()) },
                                valueRange = 0f..1000f,
                                enabled = effectsState.isEnabled,
                                accentColor = TertiaryCyan,
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Footer Apply Button
                Button(
                    onClick = onDismiss,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = PrimaryPinkContainer),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text(
                        text = "Guardar y Cerrar",
                        color = PureWhite,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                    )
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

    var manualCode by remember { mutableStateOf("") }
    var scanError by remember { mutableStateOf<String?>(null) }

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
                        text = "Vincular Dispositivo",
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
                    text = "Apunta la cámara al código QR de Michi Link o ingresa el código de vinculación.",
                    color = OnSurfaceVariant,
                    fontSize = 13.sp,
                )

                Spacer(modifier = Modifier.height(16.dp))

                Box(
                    modifier = Modifier
                        .size(180.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(SurfaceObsidian)
                        .border(1.5.dp, TertiaryCyan, RoundedCornerShape(16.dp))
                        .padding(16.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Filled.QrCodeScanner,
                        contentDescription = null,
                        tint = Color(0x337DF4FF),
                        modifier = Modifier.size(90.dp),
                    )

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(2.dp)
                            .align(Alignment.TopCenter)
                            .padding(top = (140 * laserY).dp)
                            .background(
                                Brush.horizontalGradient(
                                    listOf(Color.Transparent, TertiaryCyan, Color.Transparent),
                                ),
                            ),
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                OutlinedTextField(
                    value = manualCode,
                    onValueChange = {
                        manualCode = it
                        scanError = null
                    },
                    label = { Text("Código de vinculación / Token") },
                    placeholder = { Text("Ej. MLINK-9281 / JSON", color = OnSurfaceVariant) },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = PureWhite,
                        unfocusedTextColor = PureWhite,
                        focusedBorderColor = TertiaryCyan,
                        unfocusedBorderColor = GlassBorderLow,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )

                if (scanError != null) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = scanError!!,
                        color = ErrorColor,
                        fontSize = 12.sp,
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                Button(
                    onClick = {
                        val trimmed = manualCode.trim()
                        if (trimmed.isNotEmpty()) {
                            onScanSuccess(trimmed)
                            onDismiss()
                        } else {
                            scanError = "Por favor ingresa un código o token válido"
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = PrimaryPinkContainer),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text("Vincular", color = PureWhite, fontWeight = FontWeight.Bold)
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
    var serverName by remember { mutableStateOf("") }
    var ipAddress by remember { mutableStateOf("") }
    var connectionError by remember { mutableStateOf<String?>(null) }

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
                    onValueChange = {
                        serverName = it
                        connectionError = null
                    },
                    label = { Text("Nombre del Dispositivo") },
                    placeholder = { Text("Ej. Michi Server", color = OnSurfaceVariant) },
                    singleLine = true,
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
                    onValueChange = {
                        ipAddress = it
                        connectionError = null
                    },
                    label = { Text("Dirección IP y Puerto") },
                    placeholder = { Text("192.168.1.X:7331", color = OnSurfaceVariant) },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = PureWhite,
                        unfocusedTextColor = PureWhite,
                        focusedBorderColor = TertiaryCyan,
                        unfocusedBorderColor = GlassBorderLow,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )

                if (connectionError != null) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = connectionError!!,
                        color = ErrorColor,
                        fontSize = 12.sp,
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                Button(
                    onClick = {
                        val trimmedIp = ipAddress.trim()
                        val trimmedName = serverName.trim().ifEmpty { "Michi Node" }
                        if (trimmedIp.isNotEmpty()) {
                            onConnect(trimmedName, trimmedIp)
                            onDismiss()
                        } else {
                            connectionError = "Por favor ingresa la IP y puerto del dispositivo"
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = PrimaryPinkContainer),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text("Conectar", color = PureWhite, fontWeight = FontWeight.Bold)
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
    val sessionManager: org.michimusic.mobile.playback.PlaybackSessionManager? = runCatching {
        org.koin.compose.koinInject<org.michimusic.mobile.playback.PlaybackSessionManager>()
    }.getOrNull()
    val sessionState by (sessionManager?.sessionState?.collectAsState() ?: remember {
        mutableStateOf(org.michimusic.mobile.playback.PlaybackSessionState())
    })

    val usbDacManager: org.michimusic.player.UsbDacManager? = runCatching {
        org.koin.compose.koinInject<org.michimusic.player.UsbDacManager>()
    }.getOrNull()
    val outputDevices by (usbDacManager?.outputDevices?.collectAsState() ?: remember { mutableStateOf(emptyList()) })
    val dacInfo by (usbDacManager?.dacState?.collectAsState() ?: remember { mutableStateOf(org.michimusic.player.UsbDacInfo()) })
    val selectedId by (usbDacManager?.selectedDeviceId?.collectAsState() ?: remember { mutableStateOf(null) })

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
                                text = "Enrutamiento de Audio",
                                color = PureWhite,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                text = "Endpoints Michi & Salidas Locales",
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

                LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    // Section 1: Endpoints Michi
                    item {
                        Text(
                            text = "REPRODUCIR EN",
                            color = PrimaryPink,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.5.sp,
                        )
                    }

                    items(sessionState.availableEndpoints) { endpoint ->
                        val isCurrent = sessionState.activeEndpoint.id == endpoint.id
                        val icon = when (endpoint.type) {
                            org.michimusic.mobile.playback.EndpointType.LOCAL_PHONE -> Icons.Filled.Headphones
                            org.michimusic.mobile.playback.EndpointType.DESKTOP_PLAYER -> Icons.Filled.LaptopMac
                            org.michimusic.mobile.playback.EndpointType.SERVER -> Icons.Filled.Dns
                            org.michimusic.mobile.playback.EndpointType.STREAM_RECEIVER, org.michimusic.mobile.playback.EndpointType.ROOM -> Icons.Filled.Speaker
                            org.michimusic.mobile.playback.EndpointType.UNKNOWN -> Icons.Filled.Speaker
                        }

                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .background(if (isCurrent) PrimaryPinkContainer.copy(alpha = 0.15f) else GlassFillLow)
                                .border(
                                    1.dp,
                                    if (isCurrent) PrimaryPink else GlassBorderLow,
                                    RoundedCornerShape(14.dp),
                                )
                                .clickable {
                                    sessionManager?.handoffTo(endpoint) { _, _ -> }
                                }
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
                                            .background(if (isCurrent) PrimaryPink.copy(alpha = 0.25f) else GlassFillHigh),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Icon(
                                            imageVector = icon,
                                            contentDescription = null,
                                            tint = if (isCurrent) PrimaryPink else PureWhite,
                                            modifier = Modifier.size(20.dp),
                                        )
                                    }

                                    Column {
                                        Text(
                                            text = endpoint.name,
                                            color = if (isCurrent) PrimaryPink else PureWhite,
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.SemiBold,
                                        )
                                        Text(
                                            text = if (endpoint.isLocal) "Reproductor local de este teléfono" else "Nodo remoto en red local",
                                            color = TextMuted,
                                            fontSize = 10.sp,
                                        )
                                    }
                                }

                                if (isCurrent) {
                                    Box(
                                        modifier = Modifier
                                            .size(24.dp)
                                            .clip(CircleShape)
                                            .background(PrimaryPink),
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

                    // Section 2: Salida Física de Audio de Este Teléfono (si está activo localmente)
                    if (sessionState.activeEndpoint.isLocal) {
                        item {
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "SALIDA DE AUDIO DE ESTE TELÉFONO",
                                color = TertiaryCyan,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.5.sp,
                            )
                        }

                        if (dacInfo.isConnected) {
                            item {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(TertiaryCyan.copy(alpha = 0.12f))
                                        .border(1.dp, TertiaryCyan.copy(alpha = 0.45f), RoundedCornerShape(12.dp))
                                        .padding(10.dp),
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        Icon(
                                            imageVector = Icons.Filled.Usb,
                                            contentDescription = null,
                                            tint = TertiaryCyan,
                                            modifier = Modifier.size(18.dp),
                                        )
                                        Column {
                                            Text(
                                                text = "DAC USB DIRECTO: ${dacInfo.deviceName}",
                                                color = TertiaryCyan,
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Bold,
                                            )
                                        }
                                    }
                                }
                            }
                        }

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
                                                .size(34.dp)
                                                .clip(CircleShape)
                                                .background(if (isCurrentSelected) TertiaryCyan.copy(alpha = 0.25f) else GlassFillHigh),
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            Icon(
                                                imageVector = icon,
                                                contentDescription = null,
                                                tint = if (isCurrentSelected) TertiaryCyan else PureWhite,
                                                modifier = Modifier.size(18.dp),
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
                                                text = if (device.isUsbDac) "Audio Digital USB" else if (device.isBluetooth) "Bluetooth" else if (device.isWired) "Auriculares" else "Altavoz Integrado",
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

@Composable
fun PinPairingDialog(
    deviceName: String,
    onConfirm: (pin: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = SurfaceObsidian,
            border = androidx.compose.foundation.BorderStroke(1.2.dp, GlassBorderHigh),
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp)
                .testTag("pin_pairing_dialog"),
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
                                imageVector = Icons.Filled.Key,
                                contentDescription = null,
                                tint = TertiaryCyan,
                                modifier = Modifier.size(20.dp),
                            )
                        }

                        Column {
                            Text(
                                text = "Código PIN",
                                color = PureWhite,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                text = deviceName,
                                color = TextSecondary,
                                fontSize = 11.sp,
                            )
                        }
                    }

                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = "Cerrar",
                            tint = OnSurfaceVariant,
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = "Ingresa el código PIN de 6 dígitos mostrado en la pantalla de $deviceName",
                    color = TextSecondary,
                    fontSize = 13.sp,
                )

                Spacer(modifier = Modifier.height(12.dp))

                OutlinedTextField(
                    value = pin,
                    onValueChange = {
                        if (it.length <= 8) {
                            pin = it
                            error = null
                        }
                    },
                    placeholder = { Text("000000", color = OnSurfaceVariant) },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = PureWhite,
                        unfocusedTextColor = PureWhite,
                        focusedBorderColor = TertiaryCyan,
                        unfocusedBorderColor = GlassBorderLow,
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("pin_input_field"),
                )

                if (error != null) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(text = error!!, color = ErrorColor, fontSize = 12.sp)
                }

                Spacer(modifier = Modifier.height(16.dp))

                Button(
                    onClick = {
                        if (pin.trim().isNotEmpty()) {
                            onConfirm(pin.trim())
                            onDismiss()
                        } else {
                            error = "Ingresa el código PIN"
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .testTag("confirm_pin_button"),
                    colors = ButtonDefaults.buttonColors(containerColor = PrimaryPinkContainer),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text("Vincular", color = PureWhite, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
fun ReceiverButtonPairingDialog(
    deviceName: String,
    onConfirm: (pin: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = SurfaceObsidian,
            border = androidx.compose.foundation.BorderStroke(1.2.dp, GlassBorderHigh),
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp)
                .testTag("receiver_button_pairing_dialog"),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(CircleShape)
                        .background(TertiaryCyan.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Filled.Speaker,
                        contentDescription = null,
                        tint = TertiaryCyan,
                        modifier = Modifier.size(32.dp),
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = "Emparejamiento Físico",
                    color = PureWhite,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                )

                Spacer(modifier = Modifier.height(6.dp))

                Text(
                    text = "Presiona el botón físico en $deviceName e ingresa el código PIN mostrado en el dispositivo para autorizar la conexión:",
                    color = TextSecondary,
                    fontSize = 13.sp,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )

                Spacer(modifier = Modifier.height(12.dp))

                OutlinedTextField(
                    value = pin,
                    onValueChange = {
                        if (it.length <= 8) {
                            pin = it
                            error = null
                        }
                    },
                    placeholder = { Text("Código PIN (ej. 123456)", color = OnSurfaceVariant) },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = PureWhite,
                        unfocusedTextColor = PureWhite,
                        focusedBorderColor = TertiaryCyan,
                        unfocusedBorderColor = GlassBorderLow,
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("receiver_pin_input_field"),
                )

                if (error != null) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(text = error!!, color = ErrorColor, fontSize = 12.sp)
                }

                Spacer(modifier = Modifier.height(16.dp))

                Button(
                    onClick = {
                        if (pin.trim().isEmpty()) {
                            error = "Debes ingresar el código PIN mostrado por el dispositivo"
                            return@Button
                        }
                        onConfirm(pin.trim())
                        onDismiss()
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .testTag("confirm_receiver_button"),
                    colors = ButtonDefaults.buttonColors(containerColor = PrimaryPinkContainer),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text("Confirmar Emparejamiento", color = PureWhite, fontWeight = FontWeight.Bold)
                }

                Spacer(modifier = Modifier.height(8.dp))

                OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, GlassBorderLow),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text("Cancelar", color = PureWhite)
                }
            }
        }
    }
}

@Composable
fun PlayerPasswordPairingDialog(
    deviceName: String,
    onConfirm: (username: String, password: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = SurfaceObsidian,
            border = androidx.compose.foundation.BorderStroke(1.2.dp, GlassBorderHigh),
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp)
                .testTag("player_password_pairing_dialog"),
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
                                imageVector = Icons.Filled.LaptopMac,
                                contentDescription = null,
                                tint = TertiaryCyan,
                                modifier = Modifier.size(20.dp),
                            )
                        }

                        Column {
                            Text(
                                text = "Autenticación",
                                color = PureWhite,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                text = deviceName,
                                color = TextSecondary,
                                fontSize = 11.sp,
                            )
                        }
                    }

                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = "Cerrar",
                            tint = OnSurfaceVariant,
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = "Ingresa las credenciales configuradas en $deviceName:",
                    color = TextSecondary,
                    fontSize = 13.sp,
                )

                Spacer(modifier = Modifier.height(12.dp))

                OutlinedTextField(
                    value = username,
                    onValueChange = {
                        username = it
                        error = null
                    },
                    placeholder = { Text("Usuario (opcional)", color = OnSurfaceVariant) },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = PureWhite,
                        unfocusedTextColor = PureWhite,
                        focusedBorderColor = TertiaryCyan,
                        unfocusedBorderColor = GlassBorderLow,
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("player_username_input_field"),
                )

                Spacer(modifier = Modifier.height(8.dp))

                OutlinedTextField(
                    value = password,
                    onValueChange = {
                        password = it
                        error = null
                    },
                    placeholder = { Text("Contraseña", color = OnSurfaceVariant) },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = PureWhite,
                        unfocusedTextColor = PureWhite,
                        focusedBorderColor = TertiaryCyan,
                        unfocusedBorderColor = GlassBorderLow,
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("player_password_input_field"),
                )

                if (error != null) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(text = error!!, color = ErrorColor, fontSize = 12.sp)
                }

                Spacer(modifier = Modifier.height(16.dp))

                Button(
                    onClick = {
                        if (password.trim().isEmpty()) {
                            error = "Ingresa la contraseña del reproductor"
                            return@Button
                        }
                        onConfirm(username.trim(), password.trim())
                        onDismiss()
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .testTag("confirm_player_password_button"),
                    colors = ButtonDefaults.buttonColors(containerColor = PrimaryPinkContainer),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text("Emparejar", color = PureWhite, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
fun AddTracksDialog(
    availableTracks: List<Track>,
    isLoadingTracks: Boolean,
    isSaving: Boolean,
    onConfirm: (List<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    // Ordered, not a Set: preserves the order the user tapped tracks in, which becomes
    // the order they're appended to the playlist.
    val selectedIds = remember { mutableStateListOf<String>() }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = SurfaceContainer,
            border = androidx.compose.foundation.BorderStroke(1.dp, GlassBorderHigh),
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp)
                .testTag("add_tracks_dialog"),
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
                    Column {
                        Text(
                            text = "Añadir Canciones",
                            color = PureWhite,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                        )
                        if (selectedIds.isNotEmpty()) {
                            Text(
                                text = "${selectedIds.size} seleccionadas",
                                color = TertiaryCyan,
                                fontSize = 12.sp,
                            )
                        }
                    }
                    IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = "Cerrar",
                            tint = OnSurfaceVariant,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                when {
                    isLoadingTracks -> {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(220.dp)
                                .testTag("add_tracks_dialog_loading"),
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularProgressIndicator(color = TertiaryCyan)
                        }
                    }

                    availableTracks.isEmpty() -> {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(220.dp)
                                .testTag("add_tracks_dialog_empty"),
                            contentAlignment = Alignment.Center,
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.QueueMusic,
                                    contentDescription = null,
                                    tint = TextMuted,
                                    modifier = Modifier.size(40.dp),
                                )
                                Text(
                                    text = "No hay canciones disponibles para añadir",
                                    color = TextSecondary,
                                    fontSize = 13.sp,
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                )
                            }
                        }
                    }

                    else -> {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 260.dp)
                                .testTag("add_tracks_dialog_list"),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            items(availableTracks, key = { it.id }) { track ->
                                val isSelected = track.id in selectedIds
                                AddTracksDialogRow(
                                    track = track,
                                    isSelected = isSelected,
                                    onToggle = {
                                        if (isSelected) selectedIds.remove(track.id) else selectedIds.add(track.id)
                                    },
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                val canConfirm = selectedIds.isNotEmpty() && !isSaving
                Button(
                    onClick = { if (canConfirm) onConfirm(selectedIds.toList()) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .testTag("add_tracks_dialog_confirm_button"),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (canConfirm) PrimaryPinkContainer else PrimaryPinkContainer.copy(alpha = 0.4f),
                    ),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    if (isSaving) {
                        CircularProgressIndicator(
                            color = PureWhite,
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(20.dp),
                        )
                    } else {
                        Text(
                            text = if (selectedIds.isEmpty()) "Añadir" else "Añadir (${selectedIds.size})",
                            color = PureWhite,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AddTracksDialogRow(
    track: Track,
    isSelected: Boolean,
    onToggle: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (isSelected) SecondaryPurple.copy(alpha = 0.15f) else GlassFillLow)
            .border(1.dp, if (isSelected) SecondaryPurple else GlassBorderLow, RoundedCornerShape(12.dp))
            .clickable(onClick = onToggle)
            .padding(10.dp)
            .testTag("add_tracks_dialog_track_${track.id}"),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            AlbumArtView(
                coverStyle = coverStyleFor(track.coverId.ifEmpty { track.title }),
                imageModel = track.filepath.ifEmpty { track.coverId },
                modifier = Modifier
                    .size(38.dp)
                    .clip(RoundedCornerShape(8.dp)),
            )

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = track.title,
                    color = PureWhite,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = track.artist,
                    color = TextSecondary,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Box(
                modifier = Modifier
                    .size(22.dp)
                    .clip(CircleShape)
                    .background(if (isSelected) SecondaryPurple else Color.Transparent)
                    .border(1.dp, if (isSelected) SecondaryPurple else GlassBorderHigh, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                if (isSelected) {
                    Icon(
                        imageVector = Icons.Filled.Check,
                        contentDescription = "Seleccionada",
                        tint = PureWhite,
                        modifier = Modifier.size(14.dp),
                    )
                }
            }
        }
    }
}


