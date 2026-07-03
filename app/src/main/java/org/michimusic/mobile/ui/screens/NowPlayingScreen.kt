package org.michimusic.mobile.ui.screens

import android.content.Context
import android.media.AudioManager
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
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
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import coil3.compose.AsyncImage
import org.koin.androidx.compose.koinViewModel
import org.michimusic.mobile.sync.SyncViewModel
import org.koin.compose.koinInject
import org.michimusic.mobile.ui.theme.AccentCoral
import org.michimusic.mobile.ui.theme.AccentPink
import org.michimusic.mobile.ui.theme.TextPrimary
import org.michimusic.mobile.ui.theme.TextSecondary
import org.michimusic.player.AudioController
import org.michimusic.player.PlayerState

// --- PALETA DE COLORES (Estilo Michi Music Player) ---
val BgDark = Color(0xFF05070C)
val GlassBorder = Color(0x24FFFFFF)
private val PremiumGlass = Color(0x8F181B22)
private val PremiumGlassSoft = Color(0x52181B22)

// --- MODELOS ---
data class PlaybackSource(
    val id: String,
    val title: String,
    val subtitle: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector
)

// --- PANTALLA PRINCIPAL ---
@Composable
fun NowPlayingScreen(
    onNavigateToSettings: () -> Unit = {},
    onNavigateToAudioRoute: () -> Unit = {},
) {
    val audioController: AudioController = koinInject()
    val state by audioController?.state?.collectAsState() ?: remember { mutableStateOf(PlayerState()) }
    val syncViewModel: SyncViewModel = koinViewModel()
    val syncUiState by syncViewModel.uiState.collectAsState()

    val context = LocalContext.current
    val audioManager = remember {
        context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    } ?: return
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

    Box(modifier = Modifier.fillMaxSize()) {
        PlayerBackdrop(coverId = currentTrack?.coverId)

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp)
                .padding(bottom = 22.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.windowInsetsTopHeight(WindowInsets.statusBars))
            Spacer(modifier = Modifier.height(8.dp))

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
                                onSourceSelected = { source ->
                                    selectedSource = source
                                    isSourceMenuExpanded = false
                                    if (source.id != "local") {
                                        onNavigateToSettings()
                                    }
                                },
                                onManageClick = {
                                    isSourceMenuExpanded = false
                                    onNavigateToSettings()
                                }
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            AlbumArtworkCard(
                coverId = currentTrack?.coverId,
                modifier = Modifier
                    .weight(0.92f)
                    .aspectRatio(1f)
                    .fillMaxWidth()
                    .widthIn(max = 360.dp)
            )

            var dragProgress by remember { mutableFloatStateOf(progress) }
            LaunchedEffect(state.position, state.duration) {
                if (state.duration > 0L) {
                    dragProgress = (state.position.toFloat() / state.duration).coerceIn(0f, 1f)
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            FloatingControlsColumn {
                TrackInfo(
                    title = currentTrack?.title ?: "Sin reproducción",
                    artist = currentTrack?.artist ?: "Artista",
                    album = currentTrack?.album ?: "Álbum",
                )

                Spacer(modifier = Modifier.height(10.dp))

                UtilityIconRow(
                    isShuffled = state.shuffleMode,
                    repeatMode = state.repeatMode,
                    onShuffle = { audioController?.toggleShuffle() },
                    onRepeat = {
                        audioController?.let {
                            val next = (it.state.value.repeatMode + 1) % 3
                            it.setRepeatMode(next)
                        }
                    },
                    onNavigateToAudioRoute = onNavigateToAudioRoute,
                )

                Spacer(modifier = Modifier.height(8.dp))

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

                Spacer(modifier = Modifier.height(10.dp))

                TransportAndVolumeRow(
                    volume = volume,
                    onVolumeChange = { fraction ->
                        volume = fraction
                        val vol = (fraction * maxVolume).toInt().coerceIn(0, maxVolume)
                        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, vol, 0)
                    },
                    isPlaying = state.isPlaying,
                    onPlayPause = {
                        if (state.isPlaying) audioController?.pause()
                        else audioController?.play()
                    },
                    onNext = { audioController?.skipNext() },
                    onPrevious = { audioController?.skipPrevious() },
                )
            }

            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

// --- COMPONENTES ---

@Composable
private fun PlayerBackdrop(coverId: String?) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(BgDark)
    ) {
        if (!coverId.isNullOrEmpty()) {
            AsyncImage(
                model = "content://media/external/audio/albumart/$coverId",
                contentDescription = null,
                modifier = Modifier
                    .fillMaxSize()
                    .blur(36.dp),
                contentScale = ContentScale.Crop,
                alpha = 0.42f,
            )
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            Color(0xB005070C),
                            Color(0x8A2C1816),
                            Color(0xF205070C),
                        )
                    )
                )
        )
    }
}

@Composable
private fun FloatingControlsColumn(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 2.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        content = content,
    )
}

@Composable
fun PlaybackSourceDropdown(
    source: PlaybackSource,
    isExpanded: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .width(280.dp)
            .height(54.dp)
            .clip(RoundedCornerShape(27.dp))
            .background(PremiumGlassSoft)
            .border(1.dp, GlassBorder, RoundedCornerShape(27.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(source.icon, contentDescription = null, tint = AccentCoral, modifier = Modifier.size(22.dp))
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(source.title, color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
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
            .background(PremiumGlass)
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
            Color(0xFF1AE1B0),
            Color(0xFFFF375F),
            Color(0xFFFFD166),
        )
    )

    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center,
    ) {
        if (!coverId.isNullOrEmpty()) {
            AsyncImage(
                model = "content://media/external/audio/albumart/$coverId",
                contentDescription = null,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(12.dp)
                    .blur(28.dp),
                contentScale = ContentScale.Crop,
                alpha = 0.34f,
            )
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(14.dp))
                .background(synthwaveGradient)
                .border(1.dp, GlassBorder, RoundedCornerShape(14.dp)),
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
                        .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xA0000000))))
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.58f)
                        .aspectRatio(1f)
                        .offset(y = 16.dp)
                        .clip(CircleShape)
                        .background(Brush.verticalGradient(listOf(Color(0xFFFFF2CE), AccentPink)))
                )
                Icon(
                    imageVector = Icons.Rounded.MusicNote,
                    contentDescription = null,
                    tint = Color(0xF21B0D14),
                    modifier = Modifier.size(76.dp),
                )
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0x73000000))))
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TrackInfo(title: String, artist: String, album: String) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = title,
            color = TextPrimary,
            fontSize = 22.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .basicMarquee(),
        )
        Spacer(modifier = Modifier.height(3.dp))
        Text(
            text = artist,
            color = TextSecondary,
            fontSize = 14.sp,
            fontWeight = FontWeight.Normal,
            maxLines = 1,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .basicMarquee(),
        )
        Text(
            text = album,
            color = TextSecondary.copy(alpha = 0.70f),
            fontSize = 12.sp,
            fontWeight = FontWeight.Normal,
            maxLines = 1,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .basicMarquee(),
        )
    }
}

@Composable
private fun UtilityIconRow(
    isShuffled: Boolean,
    repeatMode: Int,
    onShuffle: () -> Unit,
    onRepeat: () -> Unit,
    onNavigateToAudioRoute: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MichiIconButton(
            Icons.Rounded.FavoriteBorder,
            size = 19.dp,
            tint = TextSecondary,
        )
        MichiIconButton(
            Icons.Rounded.Info,
            size = 19.dp,
            tint = TextSecondary,
        )
        MichiIconButton(
            Icons.AutoMirrored.Rounded.QueueMusic,
            size = 19.dp,
            tint = TextSecondary,
        )
        MichiIconButton(
            Icons.Rounded.SpeakerGroup,
            size = 19.dp,
            tint = AccentCoral,
            onClick = onNavigateToAudioRoute,
        )
        MichiIconButton(
            icon = if (repeatMode == 1) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat,
            size = 19.dp,
            tint = if (repeatMode != 0) AccentPink else TextSecondary,
            onClick = onRepeat,
        )
        MichiIconButton(
            Icons.Rounded.Shuffle,
            size = 19.dp,
            tint = if (isShuffled) AccentPink else TextSecondary,
            onClick = onShuffle,
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
    val lastValue = remember { mutableFloatStateOf(value) }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        if (timeStart != null) {
            Text(timeStart, color = TextSecondary, fontSize = 12.sp, modifier = Modifier.width(36.dp))
            Spacer(modifier = Modifier.width(8.dp))
        }

        Slider(
            value = value,
            onValueChange = { v ->
                lastValue.floatValue = v
                onValueChange(v)
            },
            onValueChangeFinished = { onValueChangeFinished?.invoke(lastValue.floatValue) },
            modifier = Modifier.weight(1f),
            thumb = {
                Box(
                    modifier = Modifier
                        .size(if (isVolume) 10.dp else 16.dp)
                        .clip(CircleShape)
                        .background(Color.White)
                )
            },
            track = { sliderState ->
                val fraction = sliderState.value
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(if (isVolume) 3.dp else 4.dp)
                        .clip(CircleShape)
                        .background(Color(0x33FFFFFF))
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(fraction)
                            .fillMaxHeight()
                            .background(if (isVolume) Color.White.copy(alpha = 0.78f) else Color.White)
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
fun TransportAndVolumeRow(
    volume: Float,
    onVolumeChange: (Float) -> Unit,
    isPlaying: Boolean = false,
    onPlayPause: () -> Unit = {},
    onNext: () -> Unit = {},
    onPrevious: () -> Unit = {},
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(PremiumGlassSoft)
            .border(1.dp, GlassBorder, RoundedCornerShape(24.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.AutoMirrored.Rounded.VolumeDown, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(18.dp))
        Box(modifier = Modifier.weight(1f)) {
            MichiSlider(value = volume, onValueChange = onVolumeChange, isVolume = true)
        }
        MichiIconButton(Icons.Rounded.SkipPrevious, size = 25.dp, tint = TextPrimary, onClick = onPrevious)
        Box(
            modifier = Modifier
                .size(52.dp)
                .clip(CircleShape)
                .background(Color.White)
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
                tint = Color(0xFF15171D),
                modifier = Modifier.size(30.dp)
            )
        }
        MichiIconButton(Icons.Rounded.SkipNext, size = 25.dp, tint = TextPrimary, onClick = onNext)
        MichiIconButton(Icons.Rounded.Equalizer, size = 19.dp, tint = AccentCoral)
    }
}

@Composable
fun MichiIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    size: Dp,
    tint: Color = TextSecondary,
    onClick: (() -> Unit)? = null,
) {
    if (onClick == null) {
        Box(
            modifier = Modifier.size(size + 16.dp),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(size))
        }
    } else {
        IconButton(onClick = onClick, modifier = Modifier.size(size + 16.dp)) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(size))
        }
    }
}
