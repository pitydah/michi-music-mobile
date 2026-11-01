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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import kotlin.math.sin
import org.michimusic.mobile.ui.theme.AeroGraphite
import org.michimusic.mobile.ui.theme.AeroGraphiteRaised
import org.michimusic.mobile.ui.theme.AeroHairline
import org.michimusic.mobile.ui.theme.AeroSheen
import org.michimusic.mobile.ui.theme.AeroTrack
import org.michimusic.mobile.ui.theme.AeroSurfaceRole
import org.michimusic.mobile.ui.theme.aeroMaterialStyle
import org.michimusic.mobile.ui.theme.LocalVisualPreferences
import org.michimusic.mobile.ui.components.aeroArtworkBackdropAllowed
import org.michimusic.mobile.ui.components.aeroSafeFraction
import org.michimusic.mobile.ui.theme.MichiShapes
import org.michimusic.mobile.ui.theme.MichiSpacing
import org.michimusic.mobile.ui.theme.PrimaryPinkContainer
import org.michimusic.mobile.ui.theme.AccentCoral
import org.michimusic.mobile.ui.theme.AccentPink
import org.michimusic.mobile.ui.theme.GlassHighlight
import org.michimusic.mobile.ui.theme.SurfaceObsidian
import org.michimusic.mobile.ui.theme.PureWhite
import org.michimusic.mobile.ui.theme.TextPrimary
import org.michimusic.mobile.ui.theme.TextSecondary
import org.michimusic.mobile.ui.theme.michiAccentFor

val BgDark = SurfaceObsidian
val GlassBorder = AeroHairline
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
    val visualPrefs = LocalVisualPreferences.current
    Box(modifier = Modifier.fillMaxSize()) {
        if (aeroArtworkBackdropAllowed(!coverId.isNullOrBlank(), visualPrefs.highContrast, visualPrefs.reducedMotion)) {
            AsyncImage(
                model = coverId,
                contentDescription = null,
                modifier = Modifier
                    .fillMaxSize()
                    .blur(24.dp),
                contentScale = ContentScale.Crop,
            )
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            accent.copy(alpha = if (visualPrefs.highContrast) 0f else 0.06f),
                            SurfaceObsidian.copy(alpha = 0.72f),
                            SurfaceObsidian,
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
                            SurfaceObsidian.copy(alpha = 0.40f),
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
            .heightIn(min = MichiSpacing.minTouchTarget)
            .clip(MichiShapes.pill)
            .background(AeroGraphiteRaised)
            .border(0.75.dp, AeroHairline, MichiShapes.pill)
            .clickable(enabled = sources.isNotEmpty(), role = Role.Button, onClick = onToggle)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (sources.isEmpty()) {
            Text("Sin fuentes disponibles", color = TextSecondary, fontSize = 12.sp)
        } else {
            val active = sources.firstOrNull { it.isActive } ?: sources.first()
            active.icon()
            Spacer(Modifier.width(6.dp))
            Text(
                text = active.label,
                color = TextPrimary,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
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
    if (expanded && sources.isNotEmpty()) {
        val visualPrefs = LocalVisualPreferences.current
        val floatingMaterial = aeroMaterialStyle(AeroSurfaceRole.Floating, visualPrefs.highContrast)
        Popup(
            alignment = Alignment.TopCenter,
            onDismissRequest = onDismiss,
            properties = PopupProperties(focusable = true),
        ) {
            Column(
                modifier = Modifier
                    .widthIn(min = 180.dp, max = 320.dp)
                    .background(
                        floatingMaterial.fill,
                        RoundedCornerShape(16.dp),
                    )
                    .border(floatingMaterial.borderWidth, floatingMaterial.outline, RoundedCornerShape(16.dp))
                    .padding(6.dp),
            ) {
                sources.forEach { source ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .heightIn(min = MichiSpacing.minTouchTarget)
                            .semantics { selected = source.isActive }
                            .clickable(role = Role.Button) { onSelect(source) }
                            .background(
                                if (source.isActive) PrimaryPinkContainer.copy(alpha = 0.18f) else Color.Transparent,
                                RoundedCornerShape(12.dp),
                            )
                            .border(
                                width = if (source.isActive) 0.65.dp else 0.dp,
                                color = if (source.isActive) PrimaryPinkContainer.copy(alpha = 0.40f) else Color.Transparent,
                                shape = RoundedCornerShape(12.dp),
                            )
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        source.icon()
                        Spacer(Modifier.width(10.dp))
                        Text(
                            text = source.label,
                            color = if (source.isActive) PureWhite else TextSecondary,
                            fontWeight = if (source.isActive) FontWeight.SemiBold else FontWeight.Normal,
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
                0.65.dp,
                AeroHairline,
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
    val reducedMotion = LocalVisualPreferences.current.reducedMotion
    val enlargedText = LocalConfiguration.current.fontScale > 1.2f
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
            maxLines = if (reducedMotion || enlargedText) 2 else 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .fillMaxWidth()
                .then(if (reducedMotion || enlargedText) Modifier else Modifier.basicMarquee(iterations = 1, initialDelayMillis = 2200)),
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = artist.ifEmpty { "Artista desconocido" },
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary.copy(alpha = 0.8f),
            textAlign = TextAlign.Center,
            maxLines = if (enlargedText) 2 else 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(1.dp))
        Text(
            text = album.ifEmpty { "Álbum desconocido" },
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary.copy(alpha = 0.5f),
            textAlign = TextAlign.Center,
            maxLines = if (enlargedText) 2 else 1,
            overflow = TextOverflow.Ellipsis,
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
        org.michimusic.mobile.ui.components.premium.MichiIconAction(
            icon = Icons.AutoMirrored.Rounded.KeyboardArrowLeft,
            contentDescription = "Atrás",
            tint = TextSecondary,
            iconSize = 24.dp,
            onClick = onBack,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            org.michimusic.mobile.ui.components.premium.MichiIconAction(
                icon = Icons.Rounded.Timer,
                contentDescription = if (sleepTimerActive) "Temporizador de apagado activo" else "Temporizador de apagado",
                tint = if (sleepTimerActive) AccentCoral else TextSecondary.copy(alpha = 0.6f),
                iconSize = 18.dp,
                onClick = onTimerClick,
            )
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
            value = aeroSafeFraction(value),
            onValueChange = { onValueChange(aeroSafeFraction(it)) },
            onValueChangeFinished = { onSeekEnd(aeroSafeFraction(value)) },
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = MichiSpacing.minTouchTarget),
            colors = SliderDefaults.colors(
                thumbColor = Color.White,
                activeTrackColor = accent,
                inactiveTrackColor = AeroTrack,
            ),
            thumb = {
                Box(
                    modifier = Modifier
                        .size(20.dp)
                        .clip(CircleShape)
                        .background(Color.White)
                        .border(1.dp, accent, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(Modifier.size(8.dp).background(accent, CircleShape))
                }
            },
            track = { sliderState ->
                val active = aeroSafeFraction(sliderState.value)
                val barHeightPx = with(LocalDensity.current) { 3.dp.toPx() }
                Canvas(modifier = Modifier.fillMaxWidth().height(24.dp)) {
                    val barTop = (size.height - barHeightPx) / 2f
                    val activeW = size.width * active.coerceIn(0f, 1f)
                    drawRoundRect(AeroTrack, Offset(0f, barTop), Size(size.width, barHeightPx), CornerRadius(3f, 3f))
                    drawRoundRect(accent, Offset(0f, barTop), Size(activeW, barHeightPx), CornerRadius(3f, 3f))
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
            .height(72.dp)
            .padding(horizontal = 14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MichiIconButton(Icons.Rounded.SkipPrevious, size = 27.dp, tint = ControlWhite, onClick = onPrevious, contentDescription = "Anterior")
        org.michimusic.mobile.ui.components.premium.MichiIconAction(
            icon = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
            contentDescription = if (isPlaying) "Pausar" else "Reproducir",
            onClick = onPlayPause,
            tint = PureWhite,
            iconSize = 42.dp,
            minTargetSize = 68.dp,
        )
        MichiIconButton(Icons.Rounded.SkipNext, size = 27.dp, tint = ControlWhite, onClick = onNext, contentDescription = "Siguiente")
    }
}

fun validateMichiIconButtonContentDescription(onClick: (() -> Unit)?, contentDescription: String?) {
    if (onClick != null) {
        require(!contentDescription.isNullOrBlank()) {
            "MichiIconButton requires an explicit, non-blank contentDescription when clickable (K46D-23)."
        }
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
    validateMichiIconButtonContentDescription(onClick, contentDescription)
    if (onClick == null) {
        Box(
            modifier = Modifier.size((size + 16.dp).coerceAtLeast(MichiSpacing.minTouchTarget)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(size))
        }
    } else {
        org.michimusic.mobile.ui.components.premium.MichiIconAction(
            icon = icon,
            contentDescription = contentDescription!!,
            onClick = onClick,
            tint = tint,
            iconSize = size,
            minTargetSize = (size + 16.dp).coerceAtLeast(MichiSpacing.minTouchTarget),
        )
    }
}
