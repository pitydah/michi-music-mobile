package org.michimusic.mobile.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.automirrored.rounded.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties

// --- PALETA DE COLORES (Estilo Michi Music Player) ---
val BgDark = Color(0xFF05070C)
val BgDarker = Color(0xFF090B11)
val GlassBg = Color(0xAA151820)
val GlassBorder = Color(0x1AFFFFFF)
private val TextPrimary = Color(0xFFFFFFFF)
private val TextSecondary = Color(0xFFA8AAB3)
private val AccentPink = Color(0xFFFF4F8B)
private val AccentCoral = Color(0xFFFF6A3D)
private val GradientProgress = Brush.horizontalGradient(listOf(AccentCoral, AccentPink))

// --- MODELOS ---
data class PlaybackSource(
    val id: String,
    val title: String,
    val subtitle: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector
)

val mockSources = listOf(
    PlaybackSource("local", "Este dispositivo", "Audio local", Icons.Rounded.Smartphone),
    PlaybackSource("micro", "Michi Micro Server", "192.168.1.100", Icons.Rounded.Dns),
    PlaybackSource("big", "Michi Big Server", "192.168.1.200", Icons.Rounded.Dns),
    PlaybackSource("nas", "NAS Casa", "Synology DS920+", Icons.Rounded.FolderSpecial),
    PlaybackSource("usb", "USB Kingston", "14.6 GB libre", Icons.Rounded.Usb)
)

// --- PANTALLA PRINCIPAL ---
@Composable
fun NowPlayingScreen() {
    var selectedSource by remember { mutableStateOf(mockSources.first()) }
    var isSourceMenuExpanded by remember { mutableStateOf(true) }
    var progress by remember { mutableFloatStateOf(0.45f) }
    var volume by remember { mutableFloatStateOf(0.7f) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(BgDarker, BgDark)))
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.windowInsetsTopHeight(WindowInsets.statusBars))
            Spacer(modifier = Modifier.height(16.dp))

            // 1 & 2. Selector Desplegable de Fuente
            Box(contentAlignment = Alignment.TopCenter) {
                PlaybackSourceDropdown(
                    source = selectedSource,
                    isExpanded = isSourceMenuExpanded,
                    onClick = { isSourceMenuExpanded = !isSourceMenuExpanded }
                )

                if (isSourceMenuExpanded) {
                    Popup(
                        alignment = Alignment.TopCenter,
                        properties = PopupProperties(focusable = true),
                        onDismissRequest = { isSourceMenuExpanded = false }
                    ) {
                        Box(modifier = Modifier.padding(top = 70.dp)) {
                            PlaybackSourceMenu(
                                sources = mockSources,
                                selectedSource = selectedSource,
                                onSourceSelected = {
                                    selectedSource = it
                                    isSourceMenuExpanded = false
                                },
                                onManageClick = { isSourceMenuExpanded = false }
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(32.dp))

            // 3. Carátula del Álbum
            AlbumArtworkCard(
                modifier = Modifier
                    .weight(1f)
                    .aspectRatio(1f)
                    .widthIn(max = 330.dp)
            )

            Spacer(modifier = Modifier.height(32.dp))

            // 4. Información de la Canción
            TrackInfo(title = "Random Access Memories", artist = "Daft Punk")

            Spacer(modifier = Modifier.height(24.dp))

            // 5. Barra de Progreso
            MichiSlider(
                value = progress,
                onValueChange = { progress = it },
                timeStart = "3:28",
                timeEnd = "4:34"
            )

            Spacer(modifier = Modifier.height(24.dp))

            // 6. Controles Principales
            PlaybackControls()

            Spacer(modifier = Modifier.height(24.dp))

            // 7. Volumen y Accesos Rápidos
            VolumeAndToolsRow(
                volume = volume,
                onVolumeChange = { volume = it }
            )

            Spacer(modifier = Modifier.height(110.dp))
        }

        // 8. Barra de Navegación Inferior (Flotante)
        SmokedGlassBottomBar(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(horizontal = 24.dp, vertical = 24.dp)
        )
    }
}

// --- COMPONENTES ---

@Composable
fun PlaybackSourceDropdown(
    source: PlaybackSource,
    isExpanded: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .width(280.dp)
            .height(58.dp)
            .clip(RoundedCornerShape(28.dp))
            .background(GlassBg)
            .border(1.dp, GlassBorder, RoundedCornerShape(28.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(source.icon, contentDescription = null, tint = AccentCoral, modifier = Modifier.size(24.dp))
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(source.title, color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                Text(source.subtitle, color = TextSecondary, fontSize = 12.sp)
            }
        }
        Icon(
            imageVector = if (isExpanded) Icons.Rounded.KeyboardArrowUp else Icons.Rounded.KeyboardArrowDown,
            contentDescription = null,
            tint = TextSecondary
        )
    }
}

@Composable
fun PlaybackSourceMenu(
    sources: List<PlaybackSource>,
    selectedSource: PlaybackSource,
    onSourceSelected: (PlaybackSource) -> Unit,
    onManageClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .width(280.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(GlassBg)
            .border(1.dp, GlassBorder, RoundedCornerShape(22.dp))
            .padding(vertical = 16.dp)
    ) {
        Text(
            text = "Seleccionar fuente",
            color = AccentPink,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 12.dp)
        )

        sources.forEach { source ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSourceSelected(source) }
                    .padding(horizontal = 20.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(source.icon, contentDescription = null, tint = AccentCoral, modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(source.title, color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    Text(source.subtitle, color = TextSecondary, fontSize = 12.sp)
                }
                if (source.id == selectedSource.id) {
                    Icon(Icons.Rounded.CheckCircle, contentDescription = null, tint = AccentPink, modifier = Modifier.size(18.dp))
                }
            }
        }

        HorizontalDivider(color = GlassBorder, modifier = Modifier.padding(vertical = 8.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onManageClick)
                .padding(horizontal = 20.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Rounded.Settings, contentDescription = null, tint = AccentCoral, modifier = Modifier.size(20.dp))
            Spacer(modifier = Modifier.width(12.dp))
            Text("Gestionar fuentes", color = TextSecondary, fontSize = 14.sp)
        }
    }
}

@Composable
fun AlbumArtworkCard(modifier: Modifier = Modifier) {
    val synthwaveGradient = Brush.verticalGradient(
        colors = listOf(
            Color(0xFF1E0B2D),
            Color(0xFF8A1C59),
            Color(0xFFFF6A3D)
        )
    )

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(24.dp))
            .background(synthwaveGradient)
            .border(1.dp, GlassBorder, RoundedCornerShape(24.dp)),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(120.dp)
                .offset(y = 20.dp)
                .clip(CircleShape)
                .background(Brush.verticalGradient(listOf(Color(0xFFFFD700), AccentPink)))
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xAA000000))))
        )
    }
}

@Composable
fun TrackInfo(title: String, artist: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = title,
            color = TextPrimary,
            fontSize = 26.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = artist,
            color = TextSecondary,
            fontSize = 20.sp,
            fontWeight = FontWeight.Normal
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MichiSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    timeStart: String? = null,
    timeEnd: String? = null,
    isVolume: Boolean = false
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        if (timeStart != null) {
            Text(timeStart, color = TextSecondary, fontSize = 12.sp, modifier = Modifier.width(36.dp))
            Spacer(modifier = Modifier.width(8.dp))
        }

        Slider(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.weight(1f),
            thumb = {
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .clip(CircleShape)
                        .background(AccentCoral)
                )
            },
            track = { sliderState ->
                val fraction = sliderState.value
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF2A2E38))
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(fraction)
                            .fillMaxHeight()
                            .background(GradientProgress)
                    )
                }
            }
        )

        if (timeEnd != null) {
            Spacer(modifier = Modifier.width(8.dp))
            Text(timeEnd, color = TextSecondary, fontSize = 12.sp)
        }
    }
}

@Composable
fun PlaybackControls() {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        MichiIconButton(Icons.Rounded.Shuffle, size = 24.dp, tint = AccentCoral)
        MichiIconButton(Icons.Rounded.SkipPrevious, size = 32.dp)

        Box(
            modifier = Modifier
                .size(86.dp)
                .drawBehind {
                    drawCircle(
                        color = AccentPink.copy(alpha = 0.15f),
                        radius = size.width / 2 + 12.dp.toPx()
                    )
                }
                .clip(CircleShape)
                .background(GlassBg)
                .border(2.dp, AccentPink.copy(alpha = 0.6f), CircleShape)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {}
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Rounded.Pause, contentDescription = "Pause", tint = AccentCoral, modifier = Modifier.size(38.dp))
        }

        MichiIconButton(Icons.Rounded.SkipNext, size = 32.dp)
        MichiIconButton(Icons.Rounded.Repeat, size = 24.dp, tint = AccentCoral)
    }
}

@Composable
fun VolumeAndToolsRow(volume: Float, onVolumeChange: (Float) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.AutoMirrored.Rounded.VolumeDown, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(20.dp))
        Spacer(modifier = Modifier.width(8.dp))

        Box(modifier = Modifier.weight(1f)) {
            MichiSlider(value = volume, onValueChange = onVolumeChange, isVolume = true)
        }

        Spacer(modifier = Modifier.width(8.dp))
        Icon(Icons.AutoMirrored.Rounded.VolumeUp, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(20.dp))

        Spacer(modifier = Modifier.width(24.dp))

        MichiIconButton(Icons.Rounded.Tune, size = 20.dp, tint = AccentCoral)
        Spacer(modifier = Modifier.width(16.dp))
        MichiIconButton(Icons.Rounded.SpeakerGroup, size = 20.dp, tint = AccentCoral)
    }
}

@Composable
fun SmokedGlassBottomBar(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(82.dp)
            .clip(RoundedCornerShape(34.dp))
            .background(GlassBg)
            .border(1.dp, GlassBorder, RoundedCornerShape(34.dp))
            .padding(horizontal = 24.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        BottomBarIcon(Icons.Rounded.Home, isActive = false)
        BottomBarIcon(Icons.Rounded.LibraryMusic, isActive = false)
        BottomBarIcon(Icons.Rounded.PlayArrow, isActive = false)
        BottomBarIcon(Icons.Rounded.CloudSync, isActive = false)
        BottomBarIcon(Icons.Rounded.SpeakerGroup, isActive = true)
    }
}

@Composable
fun BottomBarIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, isActive: Boolean) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable { }) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (isActive) AccentPink else TextSecondary.copy(alpha = 0.6f),
            modifier = Modifier.size(26.dp)
        )
        if (isActive) {
            Spacer(modifier = Modifier.height(6.dp))
            Box(modifier = Modifier.size(4.dp).clip(CircleShape).background(AccentPink))
        }
    }
}

@Composable
fun MichiIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    size: Dp,
    tint: Color = TextSecondary
) {
    IconButton(onClick = { }, modifier = Modifier.size(size + 16.dp)) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(size))
    }
}
