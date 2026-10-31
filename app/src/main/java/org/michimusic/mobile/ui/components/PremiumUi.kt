package org.michimusic.mobile.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Audiotrack
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import org.michimusic.mobile.R
import org.michimusic.mobile.ui.theme.AeroSurfaceRole
import org.michimusic.mobile.ui.theme.aeroMaterialStyle
import org.michimusic.mobile.ui.theme.AeroGraphiteRaised
import org.michimusic.mobile.ui.theme.LocalVisualPreferences
import org.michimusic.mobile.ui.theme.PrimaryPinkContainer
import org.michimusic.mobile.ui.theme.MichiSpacing
import org.michimusic.mobile.ui.theme.MichiShapes
import org.michimusic.mobile.ui.theme.PureWhite
import org.michimusic.mobile.ui.theme.AccentCoral
import org.michimusic.mobile.ui.theme.AccentPink
import org.michimusic.mobile.ui.theme.AccentPurple
import org.michimusic.mobile.ui.theme.GlassHighlight
import org.michimusic.mobile.ui.theme.GlassInk
import org.michimusic.mobile.ui.theme.GlassMist
import org.michimusic.mobile.ui.theme.GlassVeil
import org.michimusic.mobile.ui.theme.SmokeBottom
import org.michimusic.mobile.ui.theme.SmokeMid
import org.michimusic.mobile.ui.theme.SmokeTop
import org.michimusic.mobile.ui.theme.SurfaceDark
import org.michimusic.mobile.ui.theme.SurfaceElevated
import org.michimusic.mobile.ui.theme.SurfaceBorder
import org.michimusic.mobile.ui.theme.TextDim
import org.michimusic.mobile.ui.theme.TextMuted
import org.michimusic.mobile.ui.theme.TextPrimary
import org.michimusic.mobile.ui.theme.TextSecondary

@Composable
fun PremiumScreen(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    // Ambient waves/textures remain opt-in helpers. A global screen never draws
    // neon halos on every device or ignores the user's contrast preference.
    val canvas = aeroMaterialStyle(AeroSurfaceRole.Canvas, LocalVisualPreferences.current.highContrast)
    Box(modifier = modifier.fillMaxSize().background(canvas.fill)) { content() }
}

@Composable
fun PremiumGlassTexture(
    modifier: Modifier = Modifier,
) {
    if (LocalVisualPreferences.current.highContrast) return
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val step = 18.dp.toPx()
        var x = -w * 0.25f
        while (x < w * 1.1f) {
            drawLine(GlassHighlight.copy(alpha = 0.018f), Offset(x, 0f), Offset(x + w * 0.36f, h), strokeWidth = 1.dp.toPx())
            x += step
        }
        val dotStep = 26.dp.toPx()
        var y = 10.dp.toPx()
        var row = 0
        while (y < h) {
            var dotX = if (row % 2 == 0) 8.dp.toPx() else 21.dp.toPx()
            while (dotX < w) {
                drawCircle(Color.White.copy(alpha = 0.014f), 0.55.dp.toPx(), center = Offset(dotX, y))
                dotX += dotStep
            }
            row += 1
            y += dotStep
        }
    }
}

@Composable
fun PremiumWaveBackdrop(
    modifier: Modifier = Modifier,
) {
    if (LocalVisualPreferences.current.highContrast) return
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val upperWave = Path().apply {
            moveTo(0f, h * 0.26f)
            quadraticTo(w * 0.22f, h * 0.02f, w * 0.48f, h * 0.24f)
            quadraticTo(w * 0.72f, h * 0.44f, w, h * 0.18f)
            lineTo(w, 0f)
            lineTo(0f, 0f)
            close()
        }
        val lowerWave = Path().apply {
            moveTo(0f, h * 0.62f)
            quadraticTo(w * 0.24f, h * 0.38f, w * 0.5f, h * 0.58f)
            quadraticTo(w * 0.78f, h * 0.78f, w, h * 0.48f)
            lineTo(w, h)
            lineTo(0f, h)
            close()
        }
        drawPath(upperWave, AccentPink.copy(alpha = 0.075f))
        drawPath(
            upperWave,
            AccentPink.copy(alpha = 0.11f),
            style = Stroke(width = 0.8.dp.toPx()),
        )
        drawPath(lowerWave, AccentCoral.copy(alpha = 0.065f))
        drawPath(
            lowerWave,
            GlassVeil.copy(alpha = 0.08f),
            style = Stroke(width = 1.dp.toPx()),
        )
        drawCircle(
            color = AccentPurple.copy(alpha = 0.055f),
            radius = w * 0.34f,
            center = androidx.compose.ui.geometry.Offset(w * 0.78f, h * 0.1f),
        )
    }
}

@Composable
fun ScreenHeader(
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Image(
            painter = painterResource(R.mipmap.michi_logo),
            contentDescription = null,
            modifier = Modifier
                .size(44.dp)
                .shadow(12.dp, RoundedCornerShape(12.dp), clip = false)
                .clip(RoundedCornerShape(12.dp)),
        )
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineMedium,
                color = TextPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary,
                maxLines = if (LocalConfiguration.current.fontScale > 1.2f) 2 else 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        trailing?.invoke()
    }
}

@Composable
fun PremiumStatPill(
    text: String,
    modifier: Modifier = Modifier,
) {
    val material = aeroMaterialStyle(AeroSurfaceRole.Raised, LocalVisualPreferences.current.highContrast)
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(999.dp))
            .background(material.fill)
            .border(material.borderWidth, material.outline, RoundedCornerShape(999.dp))
            .padding(horizontal = 12.dp, vertical = 7.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = if (LocalVisualPreferences.current.highContrast) TextPrimary else PrimaryPinkContainer,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
fun PremiumSectionHeader(
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    action: String? = null,
    onAction: (() -> Unit)? = null,
) {
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val compact = maxWidth < 360.dp || LocalConfiguration.current.fontScale > 1.2f
        val actionVisible = !action.isNullOrBlank() && onAction != null
        if (compact) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium,
                    color = TextPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (subtitle.isNotBlank()) {
                    Text(subtitle, style = MaterialTheme.typography.bodySmall,
                        color = TextMuted, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                if (actionVisible) {
                    Text(action.orEmpty(), style = MaterialTheme.typography.labelMedium,
                        color = if (LocalVisualPreferences.current.highContrast) TextPrimary else PrimaryPinkContainer,
                        modifier = Modifier.heightIn(min = MichiSpacing.minTouchTarget)
                            .clip(MichiShapes.pill)
                            .clickable(role = Role.Button, onClick = onAction!!)
                            .padding(horizontal = 12.dp, vertical = 12.dp))
                }
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleMedium,
                        color = TextPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (subtitle.isNotBlank()) Text(subtitle, style = MaterialTheme.typography.bodySmall,
                        color = TextMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (actionVisible) {
                    Text(action.orEmpty(), style = MaterialTheme.typography.labelMedium,
                        color = if (LocalVisualPreferences.current.highContrast) TextPrimary else PrimaryPinkContainer,
                        modifier = Modifier.heightIn(min = MichiSpacing.minTouchTarget)
                            .clip(MichiShapes.pill)
                            .clickable(role = Role.Button, onClick = onAction!!)
                            .padding(horizontal = 12.dp, vertical = 12.dp))
                }
            }
        }
    }
}

@Composable
fun PremiumFilterChips(
    items: List<String>,
    selectedIndex: Int,
    onSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        itemsIndexed(items, key = { index, _ -> index }) { index, item ->
            val selected = index == selectedIndex
            val material = aeroMaterialStyle(AeroSurfaceRole.Raised, LocalVisualPreferences.current.highContrast)
            Box(
                modifier = Modifier
                    .heightIn(min = MichiSpacing.minTouchTarget)
                    .clip(RoundedCornerShape(999.dp))
                    .background(if (selected) PrimaryPinkContainer.copy(alpha = 0.16f) else material.fill)
                    .border(
                        material.borderWidth,
                        if (selected) PrimaryPinkContainer else material.outline,
                        RoundedCornerShape(999.dp),
                    )
                    .selectable(selected = selected, role = Role.Button, onClick = { onSelected(index) })
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = item,
                    color = if (selected) TextPrimary else TextMuted,
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
fun EditorialCoverCollage(
    coverIds: List<String>,
    modifier: Modifier = Modifier,
) {
    GlassCard(modifier = modifier, contentPadding = 10.dp) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(176.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CollageTile(
                coverId = coverIds.getOrNull(0).orEmpty(),
                modifier = Modifier
                    .weight(1.65f)
                    .fillMaxSize(),
                large = true,
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                CollageTile(
                    coverId = coverIds.getOrNull(1).orEmpty(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                )
                CollageTile(
                    coverId = coverIds.getOrNull(2).orEmpty(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                )
            }
        }
    }
}

@Composable
private fun CollageTile(
    coverId: String,
    modifier: Modifier = Modifier,
    large: Boolean = false,
) {
    CollageTileContent(coverId = coverId, modifier = modifier, large = large)
}

@Composable
private fun CollageTileContent(
    coverId: String,
    modifier: Modifier = Modifier,
    large: Boolean = false,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(
                Brush.verticalGradient(
                    listOf(
                        Color(0xFF53D8B6),
                        Color(0xFFD33259),
                        Color(0xFF9F8042),
                    )
                )
            )
            .border(0.5.dp, SurfaceBorder, RoundedCornerShape(8.dp)),
        contentAlignment = Alignment.Center,
    ) {
        if (coverId.isNotEmpty()) {
            AsyncImage(
                model = "content://media/external/audio/albumart/$coverId",
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.radialGradient(
                            listOf(
                                Color.White.copy(alpha = 0.42f),
                                Color.Transparent,
                            ),
                            radius = if (large) 250f else 170f,
                        )
                    ),
            )
            Icon(
                Icons.Rounded.Audiotrack,
                contentDescription = null,
                tint = Color(0xE91B0D14),
                modifier = Modifier.size(if (large) 54.dp else 30.dp),
            )
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            GlassHighlight.copy(alpha = 0.10f),
                            Color.Transparent,
                            Color.Black.copy(alpha = 0.16f),
                        )
                    )
                ),
        )
    }
}

@Composable
fun PremiumIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    org.michimusic.mobile.ui.components.premium.MichiIconAction(
        icon = icon,
        contentDescription = contentDescription,
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        iconSize = 22.dp,
        minTargetSize = MichiSpacing.minTouchTarget,
    )
}

@Composable
fun PremiumLoadingState(
    text: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator(
            modifier = Modifier.semantics { stateDescription = text },
            color = PrimaryPinkContainer,
        )
        Spacer(Modifier.height(12.dp))
        Text(text, color = TextSecondary, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
fun PremiumEmptyState(
    icon: ImageVector,
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
) {
    GlassCard(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                Color.White.copy(alpha = 0.11f),
                                AccentCoral.copy(alpha = 0.12f),
                            )
                        )
                    )
                    .border(0.5.dp, SurfaceBorder, RoundedCornerShape(16.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = AccentCoral, modifier = Modifier.size(34.dp))
            }
            Spacer(Modifier.height(14.dp))
            Text(title, style = MaterialTheme.typography.titleMedium, color = TextPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(4.dp))
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = TextSecondary, textAlign = TextAlign.Center)
        }
    }
}

@Composable
fun PremiumButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = MichiSpacing.minTouchTarget),
        shape = MichiShapes.sm,
        colors = ButtonDefaults.buttonColors(
            containerColor = PrimaryPinkContainer,
            contentColor = PureWhite,
            disabledContainerColor = SurfaceElevated.copy(alpha = 0.42f),
            disabledContentColor = TextDim,
        ),
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun PremiumTrackItem(
    title: String,
    subtitle: String,
    coverId: String,
    modifier: Modifier = Modifier,
    isActive: Boolean = false,
    trailing: (@Composable () -> Unit)? = null,
    onClick: () -> Unit,
) {
    // Keep transport/overflow actions outside the track-opening clickable surface.
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 60.dp)
            .clip(MichiShapes.sm)
            .border(
                0.65.dp,
                if (isActive) PrimaryPinkContainer.copy(alpha = 0.48f) else SurfaceBorder,
                MichiShapes.sm,
            )
            .background(if (isActive) PrimaryPinkContainer.copy(alpha = 0.13f) else AeroGraphiteRaised)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .heightIn(min = MichiSpacing.minTouchTarget)
                .clip(MichiShapes.sm)
                .clickable(role = Role.Button, onClick = onClick)
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            TrackArtwork(coverId = coverId)
            Column(Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (isActive) PrimaryPinkContainer else TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        trailing?.invoke()
    }
}

@Composable
fun TrackArtwork(
    coverId: String,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
) {
    val artworkModifier = modifier
        .size(size)
        .clip(RoundedCornerShape(8.dp))
        .border(0.5.dp, SurfaceBorder, RoundedCornerShape(8.dp))

    if (coverId.isNotEmpty()) {
        AsyncImage(
            model = "content://media/external/audio/albumart/$coverId",
            contentDescription = null,
            modifier = artworkModifier,
            contentScale = ContentScale.Crop,
        )
    } else {
        Box(
            modifier = artworkModifier.background(
                Brush.verticalGradient(
                    listOf(
                        Color(0xFF6EDDBC),
                        Color(0xFFD03B58),
                        Color(0xFF9F8247),
                    )
                )
            ),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .size(size * 0.58f)
                    .clip(RoundedCornerShape(999.dp))
                    .background(
                        Brush.verticalGradient(
                            listOf(Color(0xFFF6DFC0), Color(0xFFBA5273))
                        )
                    ),
            )
            Icon(
                Icons.Rounded.GraphicEq,
                contentDescription = null,
                tint = Color(0xF21B0D14),
                modifier = Modifier.size(size * 0.38f),
            )
        }
    }
}
