package org.michimusic.mobile.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.automirrored.rounded.VolumeDown
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.FolderSpecial
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Smartphone
import androidx.compose.material.icons.rounded.SpeakerGroup
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Usb
import androidx.compose.material.icons.rounded.VolumeDown
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import coil3.compose.AsyncImage
import org.koin.compose.koinInject
import org.michimusic.mobile.ui.theme.AccentCoral
import org.michimusic.mobile.ui.theme.AccentPink
import org.michimusic.mobile.ui.theme.GlassBg
import org.michimusic.mobile.ui.theme.GlassBorder
import org.michimusic.mobile.ui.theme.SurfaceDark
import org.michimusic.mobile.ui.theme.TextDim
import org.michimusic.mobile.ui.theme.TextMuted
import org.michimusic.mobile.ui.theme.TextPrimary
import org.michimusic.mobile.ui.theme.TextSecondary
import org.michimusic.player.AudioController

private val GradientProgress = Brush.horizontalGradient(listOf(AccentCoral, AccentPink))

data class PlaybackSource(
    val id: String,
    val title: String,
    val subtitle: String,
    val icon: ImageVector,
)

private val localSource = PlaybackSource("local", "Este dispositivo", "Audio local", Icons.Rounded.Smartphone)

@Composable
fun NowPlayingScreen() {
    val controller: AudioController = koinInject()
    val playerState by controller.state.collectAsState()

    val track = playerState.currentTrack
    var selectedSource by remember { mutableStateOf(localSource) }
    var isSourceMenuExpanded by remember { mutableStateOf(false) }
    var volume by remember { mutableFloatStateOf(0.7f) }

    val displayPosition = playerState.position
    val displayDuration = playerState.duration.coerceAtLeast(1L)
    val progressFraction = if (displayDuration > 0) {
        (displayPosition.toFloat() / displayDuration.toFloat()).coerceIn(0f, 1f)
    } else 0f

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(SurfaceDark, SurfaceDark)))
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(modifier = Modifier.windowInsetsTopHeight(WindowInsets.statusBars))
            Spacer(modifier = Modifier.height(16.dp))

            Box(contentAlignment = Alignment.TopCenter) {
                PlaybackSourceDropdown(
                    source = selectedSource,
                    isExpanded = isSourceMenuExpanded,
                    onClick = { isSourceMenuExpanded = !isSourceMenuExpanded },
                )
                if (isSourceMenuExpanded) {
                    Popup(
                        alignment = Alignment.TopCenter,
                        properties = PopupProperties(focusable = true),
                        onDismissRequest = { isSourceMenuExpanded = false },
                    ) {
                        Box(modifier = Modifier.padding(top = 70.dp)) {
                            PlaybackSourceMenu(
                                sources = listOf(localSource),
                                selectedSource = selectedSource,
                                onSourceSelected = {
                                    selectedSource = it
                                    isSourceMenuExpanded = false
                                },
                                onManageClick = { isSourceMenuExpanded = false },
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(32.dp))

            AlbumArtworkCard(
                coverUri = if (track?.coverId?.isNotEmpty() == true)
                    "content://media/external/audio/albumart/${track.coverId}"
                else null,
                modifier = Modifier
                    .weight(1f)
                    .aspectRatio(1f)
                    .widthIn(max = 330.dp),
            )

            Spacer(modifier = Modifier.height(32.dp))

            TrackInfo(
                title = track?.title ?: "Sin reproducción",
                artist = track?.artist ?: "",
            )

            Spacer(modifier = Modifier.height(24.dp))

            MichiSlider(
                value = progressFraction,
                onValueChange = { controller.seekTo((it * displayDuration).toLong()) },
                timeStart = formatDuration(displayPosition),
                timeEnd = formatDuration(displayDuration),
            )

            Spacer(modifier = Modifier.height(24.dp))

            PlaybackControls(
                isPlaying = playerState.isPlaying,
                onPlayPause = { if (playerState.isPlaying) controller.pause() else controller.play() },
                onNext = controller::skipNext,
                onPrevious = controller::skipPrevious,
            )

            Spacer(modifier = Modifier.height(24.dp))

            VolumeAndToolsRow(
                volume = volume,
                onVolumeChange = { volume = it },
            )

            Spacer(modifier = Modifier.height(110.dp))
        }

        SmokedGlassBottomBar(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(horizontal = 24.dp, vertical = 24.dp),
        )
    }
}

private fun formatDuration(millis: Long): String {
    val totalSeconds = (millis / 1000).coerceAtLeast(0)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}

@Composable
fun PlaybackSourceDropdown(
    source: PlaybackSource,
    isExpanded: Boolean,
    onClick: () -> Unit,
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
        horizontalArrangement = Arrangement.SpaceBetween,
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
            tint = TextSecondary,
        )
    }
}

@Composable
fun PlaybackSourceMenu(
    sources: List<PlaybackSource>,
    selectedSource: PlaybackSource,
    onSourceSelected: (PlaybackSource) -> Unit,
    onManageClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .width(280.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(GlassBg)
            .border(1.dp, GlassBorder, RoundedCornerShape(22.dp))
            .padding(vertical = 16.dp),
    ) {
        Text(
            text = "Seleccionar fuente",
            color = AccentPink,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 12.dp),
        )
        sources.forEach { source ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSourceSelected(source) }
                    .padding(horizontal = 20.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
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
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.Settings, contentDescription = null, tint = AccentCoral, modifier = Modifier.size(20.dp))
            Spacer(modifier = Modifier.width(12.dp))
            Text("Gestionar fuentes", color = TextSecondary, fontSize = 14.sp)
        }
    }
}

@Composable
fun AlbumArtworkCard(
    coverUri: String?,
    modifier: Modifier = Modifier,
) {
    val synthwaveGradient = Brush.verticalGradient(
        colors = listOf(
            Color(0xFF1E0B2D),
            Color(0xFF8A1C59),
            Color(0xFFFF6A3D),
        ),
    )

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(24.dp))
            .background(synthwaveGradient)
            .border(1.dp, GlassBorder, RoundedCornerShape(24.dp)),
        contentAlignment = Alignment.Center,
    ) {
        if (coverUri != null) {
            AsyncImage(
                model = coverUri,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        } else {
            Box(
                modifier = Modifier
                    .size(120.dp)
                    .offset(y = 20.dp)
                    .clip(CircleShape)
                    .background(Brush.verticalGradient(listOf(Color(0xFFFFD700), AccentPink))),
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xAA000000)))),
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
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        if (artist.isNotEmpty()) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = artist,
                color = TextSecondary,
                fontSize = 20.sp,
                fontWeight = FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MichiSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    timeStart: String? = null,
    timeEnd: String? = null,
    isVolume: Boolean = false,
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
                        .background(AccentCoral),
                )
            },
            track = { sliderState ->
                val fraction = sliderState.value
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF2A2E38)),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(fraction)
                            .fillMaxHeight()
                            .background(GradientProgress),
                    )
                }
            },
        )
        if (timeEnd != null) {
            Spacer(modifier = Modifier.width(8.dp))
            Text(timeEnd, color = TextSecondary, fontSize = 12.sp)
        }
    }
}

@Composable
fun PlaybackControls(
    isPlaying: Boolean,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MichiIconButton(Icons.Default.Shuffle, size = 24.dp, tint = AccentCoral, onClick = {})
        MichiIconButton(Icons.Rounded.SkipPrevious, size = 32.dp, onClick = onPrevious)

        Box(
            modifier = Modifier
                .size(86.dp)
                .drawBehind {
                    drawCircle(
                        color = AccentPink.copy(alpha = 0.15f),
                        radius = size.width / 2 + 12.dp.toPx(),
                    )
                }
                .clip(CircleShape)
                .background(GlassBg)
                .border(2.dp, AccentPink.copy(alpha = 0.6f), CircleShape)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onPlayPause,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                contentDescription = if (isPlaying) "Pausar" else "Reproducir",
                tint = AccentCoral,
                modifier = Modifier.size(38.dp),
            )
        }

        MichiIconButton(Icons.Rounded.SkipNext, size = 32.dp, onClick = onNext)
        MichiIconButton(Icons.Rounded.Repeat, size = 24.dp, tint = AccentCoral, onClick = {})
    }
}

@Composable
fun VolumeAndToolsRow(volume: Float, onVolumeChange: (Float) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.AutoMirrored.Rounded.VolumeDown, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(20.dp))
        Spacer(modifier = Modifier.width(8.dp))
        Box(modifier = Modifier.weight(1f)) {
            MichiSlider(value = volume, onValueChange = onVolumeChange, isVolume = true)
        }
        Spacer(modifier = Modifier.width(8.dp))
        Icon(Icons.AutoMirrored.Rounded.VolumeUp, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(20.dp))
        Spacer(modifier = Modifier.width(24.dp))
        MichiIconButton(Icons.Rounded.Tune, size = 20.dp, tint = AccentCoral, onClick = {})
        Spacer(modifier = Modifier.width(16.dp))
        MichiIconButton(Icons.Rounded.SpeakerGroup, size = 20.dp, tint = AccentCoral, onClick = {})
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
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BottomBarIcon(Icons.Default.Home, isActive = false)
        BottomBarIcon(Icons.Default.LibraryMusic, isActive = false)
        BottomBarIcon(Icons.Default.PlayArrow, isActive = false)
        BottomBarIcon(Icons.Rounded.Sync, isActive = false)
        BottomBarIcon(Icons.Rounded.SpeakerGroup, isActive = true)
    }
}

@Composable
fun BottomBarIcon(icon: ImageVector, isActive: Boolean) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable { }) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (isActive) AccentPink else TextSecondary.copy(alpha = 0.6f),
            modifier = Modifier.size(26.dp),
        )
        if (isActive) {
            Spacer(modifier = Modifier.height(6.dp))
            Box(modifier = Modifier.size(4.dp).clip(CircleShape).background(AccentPink))
        }
    }
}

@Composable
fun MichiIconButton(
    icon: ImageVector,
    size: Dp,
    tint: Color = TextSecondary,
    onClick: () -> Unit = {},
) {
    IconButton(onClick = onClick, modifier = Modifier.size(size + 16.dp)) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(size))
    }
}