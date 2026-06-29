package org.michimusic.mobile.ui.screens

import android.content.Context
import android.media.AudioManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.*
import androidx.compose.material.icons.filled.*
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import coil3.compose.AsyncImage
import org.koin.androidx.compose.koinViewModel
import org.michimusic.mobile.sync.SyncViewModel
import org.michimusic.mobile.ui.getAudioController
import org.michimusic.player.AudioController
import org.michimusic.player.PlayerState

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

// --- PANTALLA PRINCIPAL ---
@Composable
fun NowPlayingScreen() {
    val audioController = remember { getAudioController() }
    val state by audioController?.state?.collectAsState() ?: remember { mutableStateOf(PlayerState()) }
    val syncViewModel: SyncViewModel = koinViewModel()
    val syncUiState by syncViewModel.uiState.collectAsState()

    val context = LocalContext.current
    val audioManager = remember { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
    val currentVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
    var volume by remember { mutableFloatStateOf(currentVolume.toFloat() / maxVolume.coerceAtLeast(1)) }

    val localSource = PlaybackSource("local", "Este dispositivo", "Audio local", Icons.Rounded.Smartphone)
    val peerSources = remember(syncUiState.peers) {
        syncUiState.peers.map { peer ->
            PlaybackSource(
                id = "peer_${peer.deviceId}",
                title = peer.alias,
                subtitle = peer.ip,
                icon = Icons.Rounded.Dns,
            )
        }
    }
    val allSources = listOf(localSource) + peerSources
    var selectedSource by remember { mutableStateOf(localSource) }
    var isSourceMenuExpanded by remember { mutableStateOf(false) }

    val currentTrack = state.currentTrack
    val progress = if (state.duration > 0L) (state.position.toFloat() / state.duration).coerceIn(0f, 1f) else 0f

    fun formatTime(ms: Long): String {
        if (ms < 0) return "0:00"
        val totalSec = ms / 1000
        val min = totalSec / 60
        val sec = totalSec % 60
        return "%d:%02d".format(min, sec)
    }

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
                                sources = allSources,
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
                coverId = currentTrack?.coverId,
                modifier = Modifier
                    .weight(1f)
                    .aspectRatio(1f)
                    .widthIn(max = 330.dp)
            )

            Spacer(modifier = Modifier.height(32.dp))

            // 4. Información de la Canción
            TrackInfo(
                title = currentTrack?.title ?: "Sin reproducción",
                artist = currentTrack?.artist ?: ""
            )

            Spacer(modifier = Modifier.height(24.dp))

            // 5. Barra de Progreso
            var dragProgress by remember { mutableFloatStateOf(progress) }
            LaunchedEffect(state.position, state.duration) {
                if (state.duration > 0L) {
                    dragProgress = (state.position.toFloat() / state.duration).coerceIn(0f, 1f)
                }
            }
            MichiSlider(
                value = dragProgress,
                onValueChange = { dragProgress = it },
                onValueChangeFinished = {
                    if (state.duration > 0L) {
                        audioController?.seekTo((dragProgress * state.duration).toLong())
                    }
                },
                timeStart = formatTime(state.position),
                timeEnd = formatTime(state.duration)
            )

            Spacer(modifier = Modifier.height(24.dp))

            // 6. Controles Principales
            PlaybackControls(
                isPlaying = state.isPlaying,
                onPlayPause = {
                    if (state.isPlaying) audioController?.pause()
                    else audioController?.play()
                },
                onNext = { audioController?.skipNext() },
                onPrevious = { audioController?.skipPrevious() },
                onShuffle = { audioController?.toggleShuffle() },
                onRepeat = { audioController?.let {
                    val next = (it.state.value.repeatMode + 1) % 3
                    it.setRepeatMode(next)
                } },
                isShuffled = state.shuffleMode,
                repeatMode = state.repeatMode,
            )

            Spacer(modifier = Modifier.height(24.dp))

            // 7. Volumen y Accesos Rápidos
            VolumeAndToolsRow(
                volume = volume,
                onVolumeChange = { fraction ->
                    volume = fraction
                    val vol = (fraction * maxVolume).toInt().coerceIn(0, maxVolume)
                    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, vol, 0)
                }
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
fun AlbumArtworkCard(coverId: String? = null, modifier: Modifier = Modifier) {
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
        if (!coverId.isNullOrEmpty()) {
            AsyncImage(
                model = "content://media/external/audio/albumart/$coverId",
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xAA000000))))
            )
        } else {
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
    onValueChangeFinished: ((Float) -> Unit)? = null,
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
            onValueChange = { v ->
                onValueChange(v)
            },
            onValueChangeFinished = { onValueChangeFinished?.invoke(value) },
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
fun PlaybackControls(
    isPlaying: Boolean = false,
    onPlayPause: () -> Unit = {},
    onNext: () -> Unit = {},
    onPrevious: () -> Unit = {},
    onShuffle: () -> Unit = {},
    onRepeat: () -> Unit = {},
    isShuffled: Boolean = false,
    repeatMode: Int = 0,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        MichiIconButton(Icons.Rounded.Shuffle, size = 24.dp, tint = if (isShuffled) AccentPink else AccentCoral, onClick = onShuffle)
        MichiIconButton(Icons.Rounded.SkipPrevious, size = 32.dp, onClick = onPrevious)

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
                    onClick = onPlayPause
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                contentDescription = if (isPlaying) "Pausar" else "Reproducir",
                tint = AccentCoral,
                modifier = Modifier.size(38.dp)
            )
        }

        MichiIconButton(Icons.Rounded.SkipNext, size = 32.dp, onClick = onNext)
        MichiIconButton(Icons.Rounded.Repeat, size = 24.dp, tint = if (repeatMode != 0) AccentPink else AccentCoral, onClick = onRepeat)
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
    tint: Color = TextSecondary,
    onClick: () -> Unit = {},
) {
    IconButton(onClick = onClick, modifier = Modifier.size(size + 16.dp)) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(size))
    }
}
