package org.michimusic.mobile.ui.screens.nowplaying

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.VolumeDown
import androidx.compose.material.icons.rounded.Equalizer
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
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
import org.michimusic.mobile.ui.theme.AccentCoral
import org.michimusic.mobile.ui.theme.AccentPink
import org.michimusic.mobile.ui.theme.GlassHighlight
import org.michimusic.mobile.ui.theme.TextPrimary
import org.michimusic.mobile.ui.theme.TextSecondary
import org.michimusic.mobile.ui.theme.michiAccentFor

val BgDark = Color(0xFF080A10)
val GlassBorder = Color.White.copy(alpha = 0.08f)
val PremiumGlass = Color(0x1AFFFFFF)
val PremiumGlassSoft = Color(0x0DFFFFFF)
val GlassSmoke = Color(0x08000000)
val GlassSmokeDeep = Color(0x14000000)
val ControlWhite = Color(0xF0F0F0F0)
val MockupWarmSmoke = Color(0x7F1E1C1F)

data class PlaybackSource(
    val id: String,
    val label: String,
    val icon: @Composable () -> Unit,
    val isActive: Boolean = false,
)

@Composable
fun PlayerBackdrop(coverId: String?, accent: Color) {
    Box(modifier = Modifier.fillMaxSize()) {
        if (coverId != null && coverId.isNotEmpty()) {
            AsyncImage(
                model = coverId,
                contentDescription = null,
                modifier = Modifier
                    .fillMaxSize()
                    .blur(80.dp),
                contentScale = ContentScale.Crop,
            )
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            accent.copy(alpha = 0.18f),
                            BgDark.copy(alpha = 0.62f),
                            BgDark,
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
                            Color.Transparent,
                            BgDark.copy(alpha = 0.35f),
                        )
                    )
                )
        )
    }
}

@Composable
fun FloatingControlsColumn(content: @Composable ColumnScope.() -> Unit) {
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
    sources: List<PlaybackSource>,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .clickable(onClick = onToggle)
            .background(PremiumGlass, RoundedCornerShape(20.dp))
            .border(0.5.dp, GlassBorder, RoundedCornerShape(20.dp))
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (sources.isNotEmpty()) {
            val active = sources.firstOrNull { it.isActive } ?: sources.first()
            active.icon()
            Spacer(Modifier.width(6.dp))
            Text(
                text = active.label,
                color = TextSecondary,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.width(4.dp))
            Icon(
                imageVector = Icons.Rounded.MoreVert,
                contentDescription = "Cambiar fuente",
                tint = TextSecondary,
                modifier = Modifier.size(14.dp),
            )
        }
    }
}

@Composable
fun PlaybackSourceMenu(
    sources: List<PlaybackSource>,
    expanded: Boolean,
    onDismiss: () -> Unit,
    onSelect: (PlaybackSource) -> Unit,
) {
    if (expanded) {
        Popup(
            alignment = Alignment.TopCenter,
            onDismissRequest = onDismiss,
            properties = PopupProperties(focusable = true),
        ) {
            Column(
                modifier = Modifier
                    .background(
                        Brush.verticalGradient(
                            listOf(Color(0xFF1A1D26), Color(0xFF13151C))
                        ),
                        RoundedCornerShape(16.dp),
                    )
                    .border(0.5.dp, GlassBorder, RoundedCornerShape(16.dp))
                    .padding(6.dp),
            ) {
                sources.forEach { source ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { onSelect(source) }
                            .background(
                                if (source.isActive) GlassHighlight else Color.Transparent,
                                RoundedCornerShape(12.dp),
                            )
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        source.icon()
                        Spacer(Modifier.width(10.dp))
                        Text(
                            text = source.label,
                            color = if (source.isActive) Color.White else TextSecondary,
                            fontSize = 13.sp,
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun NowPlayingStage(
    coverId: String?,
    isPlaying: Boolean,
    accent: Color = AccentCoral,
    onPlayPause: () -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 50.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .padding(horizontal = 48.dp, vertical = 0.dp)
                .aspectRatio(1f)
                .fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            AlbumArtworkCard(coverId = coverId, accent = accent, modifier = Modifier.fillMaxSize())
        }
        Spacer(Modifier.height(16.dp))
        content()
    }
}

@Composable
fun AlbumArtworkCard(
    coverId: String? = null,
    accent: Color = AccentCoral,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .border(
                0.5.dp,
                Brush.linearGradient(
                    listOf(Color.White.copy(alpha = 0.22f), Color.White.copy(alpha = 0.06f))
                ),
                RoundedCornerShape(20.dp),
            ),
    ) {
        if (coverId != null && coverId.isNotEmpty()) {
            AsyncImage(
                model = coverId,
                contentDescription = "Carátula del álbum",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        } else {
            CleanAlbumPlaceholder()
        }
    }
}

@Composable
private fun CleanAlbumPlaceholder() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(
                        Color(0xFF6EDDBC),
                        Color(0xFFD03B58),
                        Color(0xFF9F8247),
                    )
                ),
                RoundedCornerShape(20.dp),
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Rounded.GraphicEq,
            contentDescription = null,
            tint = Color(0xF21B0D14),
            modifier = Modifier.size(56.dp),
        )
    }
}

@Composable
fun TrackInfo(title: String, artist: String, album: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = title.ifEmpty { "Sin título" },
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            fontSize = 22.sp,
            color = Color.White,
            textAlign = TextAlign.Center,
            maxLines = 1,
            modifier = Modifier
                .basicMarquee(iterations = 30, delayMillis = 2000)
                .fillMaxWidth(),
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = artist.ifEmpty { "Artista desconocido" },
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary.copy(alpha = 0.8f),
            textAlign = TextAlign.Center,
            maxLines = 1,
        )
        Spacer(Modifier.height(1.dp))
        Text(
            text = album.ifEmpty { "Álbum desconocido" },
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary.copy(alpha = 0.5f),
            textAlign = TextAlign.Center,
            maxLines = 1,
        )
    }
}

@Composable
fun UtilityIconRow(
    onBack: () -> Unit = {},
    sleepTimerActive: Boolean = false,
    timerText: String? = null,
    onTimerClick: () -> Unit = {},
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowLeft,
                contentDescription = "Atrás",
                tint = TextSecondary,
                modifier = Modifier.size(24.dp),
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (sleepTimerActive) {
                IconButton(onClick = onTimerClick) {
                    Icon(
                        imageVector = Icons.Rounded.Timer,
                        contentDescription = "Temporizador",
                        tint = AccentCoral,
                        modifier = Modifier.size(18.dp),
                    )
                }
            } else {
                IconButton(onClick = onTimerClick) {
                    Icon(
                        imageVector = Icons.Rounded.Timer,
                        contentDescription = "Temporizador",
                        tint = TextSecondary.copy(alpha = 0.6f),
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
            if (timerText != null) {
                Text(
                    text = timerText,
                    color = AccentCoral,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MichiSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    accent: Color = AccentCoral,
    timeStart: String = "0:00",
    timeEnd: String? = null,
    onSeekEnd: (Float) -> Unit = {},
) {
    Column(modifier = modifier.fillMaxWidth().padding(horizontal = 28.dp)) {
        Slider(
            value = value,
            onValueChange = onValueChange,
            onValueChangeFinished = { onSeekEnd(value) },
            modifier = Modifier
                .fillMaxWidth()
                .height(22.dp),
            colors = SliderDefaults.colors(
                thumbColor = Color.White,
                activeTrackColor = accent,
                inactiveTrackColor = Color.White.copy(alpha = 0.18f),
            ),
            thumb = {
                Canvas(
                    modifier = Modifier
                        .size(10.dp)
                        .blur(6.dp)
                ) {
                    drawCircle(color = accent.copy(alpha = 0.5f), radius = 16f)
                }
                Canvas(modifier = Modifier.size(8.dp)) {
                    drawCircle(color = Color.White, radius = 4f)
                }
            },
            track = { sliderState ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(40.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    val active = sliderState.value / (sliderState.valueRange.endInclusive - sliderState.valueRange.start)
                    val barHeight = 3.dp
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(barHeight)
                            .padding(horizontal = 1.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .weight(active.coerceIn(0f, 1f))
                                .height(barHeight)
                                .background(
                                    Brush.horizontalGradient(
                                        listOf(accent, accent.copy(alpha = 0.5f))
                                    ),
                                    RoundedCornerShape(3.dp),
                                )
                        )
                        Box(
                            modifier = Modifier
                                .weight((1f - active).coerceIn(0f, 1f))
                                .height(barHeight)
                                .background(
                                    Color.White.copy(alpha = 0.14f),
                                    RoundedCornerShape(3.dp),
                                )
                        )
                    }
                }
            },
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(timeStart, color = TextSecondary.copy(alpha = 0.6f), fontSize = 9.sp)
            if (timeEnd != null) {
                Text(timeEnd, color = TextSecondary, fontSize = 9.sp)
            }
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
        MichiIconButton(Icons.AutoMirrored.Rounded.VolumeDown, size = 17.dp, tint = TextSecondary, contentDescription = "Bajar volumen")
        MichiIconButton(Icons.Rounded.SkipPrevious, size = 27.dp, tint = ControlWhite, onClick = onPrevious, contentDescription = "Anterior")
        Box(
            modifier = Modifier
                .size(58.dp)
                .clip(CircleShape)
                .background(
                    Brush.radialGradient(
                        listOf(
                            Color.White.copy(alpha = 0.96f),
                            Color(0xFFF7F0E8),
                            accent.copy(alpha = 0.34f),
                        )
                    )
                )
                .border(0.35.dp, Color.White.copy(alpha = 0.48f), CircleShape)
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
                tint = BgDark,
                modifier = Modifier.size(34.dp)
            )
        }
        MichiIconButton(Icons.Rounded.SkipNext, size = 27.dp, tint = ControlWhite, onClick = onNext, contentDescription = "Siguiente")
        MichiIconButton(Icons.Rounded.Equalizer, size = 17.dp, tint = TextSecondary, contentDescription = "Ecualizador")
    }
}

@Composable
fun MichiIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    size: Dp,
    tint: Color = TextSecondary,
    onClick: (() -> Unit)? = null,
    contentDescription: String? = null,
) {
    if (onClick == null) {
        Box(
            modifier = Modifier.size(size + 16.dp),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = contentDescription, tint = tint, modifier = Modifier.size(size))
        }
    } else {
        IconButton(onClick = onClick, modifier = Modifier.size(size + 16.dp)) {
            Icon(icon, contentDescription = contentDescription, tint = tint, modifier = Modifier.size(size))
        }
    }
}
