package org.michimusic.mobile.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import org.michimusic.mobile.R
import org.michimusic.mobile.ui.theme.AccentCoral
import org.michimusic.mobile.ui.theme.AccentPink
import org.michimusic.mobile.ui.theme.AccentPurple
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
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(
                        SurfaceDark,
                        SmokeTop,
                        SmokeMid,
                        SmokeBottom,
                    )
                )
            )
            .background(
                Brush.radialGradient(
                    listOf(
                        AccentCoral.copy(alpha = 0.13f),
                        Color.Transparent,
                    ),
                    radius = 920f,
                )
            )
            .background(
                Brush.verticalGradient(
                    listOf(
                        Color.White.copy(alpha = 0.025f),
                        Color.Transparent,
                        Color.Black.copy(alpha = 0.22f),
                    )
                )
            ),
    ) {
        PremiumWaveBackdrop(
            modifier = Modifier
                .fillMaxWidth()
                .height(260.dp)
                .align(Alignment.TopCenter),
        )
        content()
    }
}

@Composable
fun PremiumWaveBackdrop(
    modifier: Modifier = Modifier,
) {
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
        drawPath(upperWave, AccentPink.copy(alpha = 0.09f))
        drawPath(lowerWave, AccentCoral.copy(alpha = 0.08f))
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
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary,
                maxLines = 1,
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
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(999.dp))
            .background(Color.White.copy(alpha = 0.08f))
            .border(0.5.dp, SurfaceBorder.copy(alpha = 1.6f), RoundedCornerShape(999.dp))
            .padding(horizontal = 10.dp, vertical = 5.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = AccentCoral,
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
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = TextPrimary,
            modifier = Modifier.alignByBaseline(),
        )
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = TextMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .alignByBaseline(),
        )
        if (action != null && onAction != null) {
            Text(
                text = action,
                style = MaterialTheme.typography.labelMedium,
                color = AccentCoral,
                modifier = Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .clickable(onClick = onAction)
                    .padding(horizontal = 8.dp, vertical = 4.dp)
                    .alignByBaseline(),
            )
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
        itemsIndexed(items) { index, item ->
            val selected = index == selectedIndex
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(
                        if (selected) {
                            Brush.horizontalGradient(
                                listOf(
                                    AccentCoral.copy(alpha = 0.22f),
                                    AccentPink.copy(alpha = 0.14f),
                                )
                            )
                        } else {
                            Brush.verticalGradient(
                                listOf(
                                    Color.White.copy(alpha = 0.075f),
                                    Color.White.copy(alpha = 0.035f),
                                )
                            )
                        }
                    )
                    .border(
                        0.5.dp,
                        if (selected) AccentCoral.copy(alpha = 0.46f) else SurfaceBorder.copy(alpha = 1.3f),
                        RoundedCornerShape(999.dp),
                    )
                    .clickable { onSelected(index) }
                    .padding(horizontal = 13.dp, vertical = 8.dp),
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
            .border(0.5.dp, SurfaceBorder.copy(alpha = 1.35f), RoundedCornerShape(8.dp)),
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
                Icons.Rounded.MusicNote,
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
                            Color.White.copy(alpha = 0.06f),
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
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .size(42.dp)
            .clip(RoundedCornerShape(999.dp)),
        colors = IconButtonDefaults.iconButtonColors(
            containerColor = Color.White.copy(alpha = 0.08f),
            contentColor = TextPrimary,
            disabledContainerColor = SurfaceElevated.copy(alpha = 0.24f),
            disabledContentColor = TextDim,
        ),
    ) {
        Icon(icon, contentDescription = contentDescription, modifier = Modifier.size(21.dp))
    }
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
        CircularProgressIndicator(color = AccentCoral)
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
            modifier = Modifier.fillMaxWidth(),
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
                    .border(0.5.dp, SurfaceBorder.copy(alpha = 1.35f), RoundedCornerShape(16.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = AccentCoral, modifier = Modifier.size(34.dp))
            }
            Spacer(Modifier.height(14.dp))
            Text(title, style = MaterialTheme.typography.titleMedium, color = TextPrimary)
            Spacer(Modifier.height(4.dp))
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = TextDim)
        }
    }
}

@Composable
fun PremiumButton(
    text: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = Color(0xFFF4EFE7),
            contentColor = SurfaceDark,
            disabledContainerColor = SurfaceElevated.copy(alpha = 0.42f),
            disabledContentColor = TextDim,
        ),
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
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
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .border(
                0.5.dp,
                if (isActive) AccentCoral.copy(alpha = 0.42f) else SurfaceBorder.copy(alpha = 1.2f),
                RoundedCornerShape(8.dp),
            )
            .background(
                if (isActive) AccentCoral.copy(alpha = 0.13f)
                else Color.White.copy(alpha = 0.055f)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TrackArtwork(coverId = coverId)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                color = if (isActive) AccentCoral else TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = if (isActive) TextSecondary else TextMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
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
        .border(0.5.dp, SurfaceBorder.copy(alpha = 1.2f), RoundedCornerShape(8.dp))

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
