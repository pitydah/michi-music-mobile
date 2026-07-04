package org.michimusic.mobile.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import coil3.compose.AsyncImage
import kotlin.math.sin
import org.koin.androidx.compose.koinViewModel
import org.michimusic.mobile.sync.SyncViewModel
import org.koin.compose.koinInject
import org.michimusic.mobile.ui.theme.AccentCoral
import org.michimusic.mobile.ui.theme.AccentPink
import org.michimusic.mobile.ui.theme.TextPrimary
import org.michimusic.mobile.ui.theme.TextSecondary
import org.michimusic.mobile.ui.theme.michiAccentFor
import org.michimusic.player.AudioController
import org.michimusic.player.PlayerState

// --- PALETA DE COLORES (Estilo Michi Music Player) ---
val BgDark = Color(0xFF05070C)
val GlassBorder = Color(0x24FFFFFF)
private val PremiumGlass = Color(0x8F181B22)
private val PremiumGlassSoft = Color(0x6614161D)
private val GlassSmoke = Color(0x5C111318)
private val GlassSmokeDeep = Color(0x74101116)
private val ControlWhite = Color(0xFFF7F4ED)
private val MockupWarmSmoke = Color(0xFF725247)

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
    val dynamicAccent = remember(currentTrack?.coverId, currentTrack?.title) {
        michiAccentFor(currentTrack?.coverId ?: currentTrack?.title)
    }

    fun formatTime(ms: Long): String {
        if (ms < 0) return "0:00"
        val totalSec = ms / 1000
        val min = totalSec / 60
        val sec = totalSec % 60
        return "%d:%02d".format(min, sec)
    }

    fun formatTimer(ms: Long): String {
        val totalMin = ((ms + 59_999L) / 60_000L).coerceAtLeast(0L)
        return "${totalMin} min"
    }

    fun cycleSleepTimer() {
        val current = state.sleepTimerRemainingMs
        val next = when {
            current <= 0L -> 15 * 60_000L
            current <= 15 * 60_000L -> 30 * 60_000L
            current <= 30 * 60_000L -> 60 * 60_000L
            else -> 0L
        }
        audioController?.setSleepTimer(next)
    }

    Box(modifier = Modifier.fillMaxSize()) {
        PlayerBackdrop(coverId = currentTrack?.coverId, accent = dynamicAccent)

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp)
                .padding(bottom = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.windowInsetsTopHeight(WindowInsets.statusBars))
            Spacer(modifier = Modifier.height(6.dp))

            Box(contentAlignment = Alignment.TopCenter) {
                PlaybackSourceDropdown(
                    source = selectedSource,
                    isExpanded = isSourceMenuExpanded,
                    accent = dynamicAccent,
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
                                accent = dynamicAccent,
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

            Spacer(modifier = Modifier.height(10.dp))

            AlbumArtworkCard(
                coverId = currentTrack?.coverId,
                accent = dynamicAccent,
                modifier = Modifier
                    .weight(0.86f)
                    .aspectRatio(1f)
                    .fillMaxWidth()
                    .widthIn(max = 352.dp)
            )

            var dragProgress by remember { mutableFloatStateOf(progress) }
            LaunchedEffect(state.position, state.duration) {
                if (state.duration > 0L) {
                    dragProgress = (state.position.toFloat() / state.duration).coerceIn(0f, 1f)
                }
            }

            Spacer(modifier = Modifier.height(9.dp))

            FloatingControlsColumn {
                TrackInfo(
                    title = currentTrack?.title ?: "Sin reproducción",
                    artist = currentTrack?.artist ?: "Artista",
                    album = currentTrack?.album ?: "Álbum",
                )

                Spacer(modifier = Modifier.height(8.dp))

                UtilityIconRow(
                    isShuffled = state.shuffleMode,
                    repeatMode = state.repeatMode,
                    sleepTimerRemainingMs = state.sleepTimerRemainingMs,
                    onShuffle = { audioController?.toggleShuffle() },
                    onRepeat = {
                        audioController?.let {
                            val next = (it.state.value.repeatMode + 1) % 3
                            it.setRepeatMode(next)
                        }
                    },
                    onSleepTimer = ::cycleSleepTimer,
                    accent = dynamicAccent,
                    onNavigateToAudioRoute = onNavigateToAudioRoute,
                )

                if (state.sleepTimerRemainingMs > 0L) {
                    Text(
                        text = "Temporizador: ${formatTimer(state.sleepTimerRemainingMs)}",
                        color = dynamicAccent,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .background(Color.White.copy(alpha = 0.06f))
                            .border(0.5.dp, dynamicAccent.copy(alpha = 0.28f), RoundedCornerShape(999.dp))
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                    )
                    Spacer(modifier = Modifier.height(3.dp))
                } else {
                    Spacer(modifier = Modifier.height(5.dp))
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
                    timeEnd = formatTime(state.duration),
                    accent = dynamicAccent,
                )

                Spacer(modifier = Modifier.height(2.dp))

                MediaControlsBar(
                    isPlaying = state.isPlaying,
                    onPlayPause = {
                        if (state.isPlaying) audioController?.pause()
                        else audioController?.play()
                    },
                    onNext = { audioController?.skipNext() },
                    onPrevious = { audioController?.skipPrevious() },
                    accent = dynamicAccent,
                )
            }
        }
    }
}

// --- COMPONENTES ---

@Composable
private fun PlayerBackdrop(coverId: String?, accent: Color) {
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
                            Color(0xD905070C),
                            MockupWarmSmoke.copy(alpha = 0.62f),
                            Color(0xF705070C),
                        )
                    )
                )
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.radialGradient(
                        listOf(
                            accent.copy(alpha = 0.18f),
                            Color.Transparent,
                        ),
                        radius = 820f,
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
            .padding(horizontal = 0.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        content = content,
    )
}

@Composable
fun PlaybackSourceDropdown(
    source: PlaybackSource,
    isExpanded: Boolean,
    accent: Color = AccentCoral,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .width(250.dp)
            .height(44.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(GlassSmokeDeep)
            .border(0.7.dp, Color.White.copy(alpha = 0.16f), RoundedCornerShape(24.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(source.icon, contentDescription = null, tint = accent, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(10.dp))
            Column {
                Text(source.title, color = TextPrimary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                Text(source.subtitle, color = TextSecondary, fontSize = 10.sp)
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
    accent: Color = AccentCoral,
    onSourceSelected: (PlaybackSource) -> Unit,
    onManageClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .width(250.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(PremiumGlass)
            .border(1.dp, GlassBorder, RoundedCornerShape(22.dp))
            .padding(vertical = 16.dp)
    ) {
        Text(
            text = "Seleccionar fuente",
            color = accent,
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
                Icon(source.icon, contentDescription = null, tint = accent, modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(source.title, color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    Text(source.subtitle, color = TextSecondary, fontSize = 12.sp)
                }
                if (source.id == selectedSource.id) {
                    Icon(Icons.Rounded.CheckCircle, contentDescription = null, tint = accent, modifier = Modifier.size(18.dp))
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
            Icon(Icons.Rounded.Settings, contentDescription = null, tint = accent, modifier = Modifier.size(20.dp))
            Spacer(modifier = Modifier.width(12.dp))
            Text("Gestionar fuentes", color = TextSecondary, fontSize = 14.sp)
        }
    }
}

@Composable
fun AlbumArtworkCard(coverId: String? = null, accent: Color = AccentCoral, modifier: Modifier = Modifier) {
    val synthwaveGradient = Brush.verticalGradient(
        colors = listOf(
            Color(0xFF6EDDBC),
            accent.copy(alpha = 0.92f),
            Color(0xFF9F8247),
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
                    .padding(2.dp)
                    .blur(26.dp),
                contentScale = ContentScale.Crop,
                alpha = 0.28f,
            )
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 2.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(synthwaveGradient)
                .border(0.6.dp, Color.White.copy(alpha = 0.16f), RoundedCornerShape(8.dp)),
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
                        .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0x7A000000))))
                )
            } else {
                CleanAlbumPlaceholder()
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                Color.White.copy(alpha = 0.08f),
                                Color.Transparent,
                                Color.Black.copy(alpha = 0.22f),
                            )
                        )
                    )
            )
        }
    }
}

@Composable
private fun CleanAlbumPlaceholder() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier
                .fillMaxWidth(0.56f)
                .aspectRatio(1f)
                .offset(y = 8.dp)
                .clip(CircleShape)
                .background(
                    Brush.verticalGradient(
                        listOf(
                            Color(0xFFF6DFC0),
                            Color(0xFFBA5273),
                        )
                    )
                )
        )
        Box(
            modifier = Modifier
                .fillMaxWidth(0.21f)
                .aspectRatio(1f)
                .offset(y = 8.dp)
                .clip(CircleShape)
                .background(Color(0x29120C10))
        )
        Icon(
            imageVector = Icons.Rounded.MusicNote,
            contentDescription = null,
            tint = Color(0xF21B0D14),
            modifier = Modifier
                .offset(y = 4.dp)
                .size(68.dp),
        )
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
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .basicMarquee(),
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = artist,
            color = TextSecondary,
            fontSize = 11.sp,
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
            fontSize = 10.sp,
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
    sleepTimerRemainingMs: Long,
    onShuffle: () -> Unit,
    onRepeat: () -> Unit,
    onSleepTimer: () -> Unit,
    accent: Color,
    onNavigateToAudioRoute: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(32.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MichiIconButton(
            Icons.Rounded.FavoriteBorder,
            size = 17.dp,
            tint = TextSecondary,
        )
        MichiIconButton(
            Icons.Rounded.Bedtime,
            size = 17.dp,
            tint = if (sleepTimerRemainingMs > 0L) accent else TextSecondary,
            onClick = onSleepTimer,
        )
        MichiIconButton(
            Icons.AutoMirrored.Rounded.QueueMusic,
            size = 17.dp,
            tint = TextSecondary,
        )
        MichiIconButton(
            Icons.Rounded.MoreHoriz,
            size = 17.dp,
            tint = AccentCoral,
            onClick = onNavigateToAudioRoute,
        )
        MichiIconButton(
            icon = if (repeatMode == 1) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat,
            size = 17.dp,
            tint = if (repeatMode != 0) accent else TextSecondary,
            onClick = onRepeat,
        )
        MichiIconButton(
            Icons.Rounded.Shuffle,
            size = 17.dp,
            tint = if (isShuffled) accent else TextSecondary,
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
    isCompact: Boolean = false,
    accent: Color = AccentCoral,
) {
    val lastValue = remember { mutableFloatStateOf(value) }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        if (timeStart != null) {
            Text(timeStart, color = TextSecondary, fontSize = 9.sp, modifier = Modifier.width(29.dp))
            Spacer(modifier = Modifier.width(6.dp))
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
                        .size(if (isCompact) 10.dp else 16.dp)
                        .clip(CircleShape)
                        .background(Color.White)
                )
            },
            track = { sliderState ->
                val fraction = sliderState.value
                Canvas(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(if (isCompact) 12.dp else 22.dp)
                ) {
                    val bars = if (isCompact) 42 else 54
                    val gap = size.width / bars
                    val stroke = if (isCompact) 2.4.dp.toPx() else 3.2.dp.toPx()
                    val centerY = size.height / 2f
                    repeat(bars) { index ->
                        val x = gap * index + gap / 2f
                        val normalized = index / (bars - 1f)
                        val wave = 0.34f + 0.66f * (
                            0.5f + 0.5f * sin(index * 0.72f).toFloat()
                        )
                        val accentLift = 0.75f + 0.25f * sin(index * 0.19f + 1.4f).toFloat()
                        val barHeight = size.height * wave * accentLift
                        val active = normalized <= fraction
                        drawLine(
                            color = if (active) accent else Color.White.copy(alpha = 0.22f),
                            start = androidx.compose.ui.geometry.Offset(x, centerY - barHeight / 2f),
                            end = androidx.compose.ui.geometry.Offset(x, centerY + barHeight / 2f),
                            strokeWidth = stroke,
                            cap = StrokeCap.Round,
                        )
                    }
                }
            }
        )

        if (timeEnd != null) {
            Spacer(modifier = Modifier.width(6.dp))
            Text(timeEnd, color = TextSecondary, fontSize = 9.sp)
        }
    }
}

@Composable
fun MediaControlsBar(
    isPlaying: Boolean = false,
    onPlayPause: () -> Unit = {},
    onNext: () -> Unit = {},
    onPrevious: () -> Unit = {},
    accent: Color = AccentCoral,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(58.dp)
            .padding(horizontal = 14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MichiIconButton(Icons.AutoMirrored.Rounded.VolumeDown, size = 17.dp, tint = TextSecondary)
        MichiIconButton(Icons.Rounded.SkipPrevious, size = 27.dp, tint = ControlWhite, onClick = onPrevious)
        Box(
            modifier = Modifier
                .size(54.dp)
                .clip(CircleShape)
                .background(accent.copy(alpha = 0.14f))
                .border(0.6.dp, accent.copy(alpha = 0.32f), CircleShape)
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
                tint = Color.White,
                modifier = Modifier.size(43.dp)
            )
        }
        MichiIconButton(Icons.Rounded.SkipNext, size = 27.dp, tint = ControlWhite, onClick = onNext)
        MichiIconButton(Icons.Rounded.Equalizer, size = 17.dp, tint = TextSecondary)
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
