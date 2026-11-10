package org.michimusic.mobile.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.michimusic.mobile.R
import org.michimusic.mobile.ui.screens.HomeHeroItem
import org.michimusic.mobile.ui.screens.HomeHeroKind
import org.michimusic.mobile.ui.theme.AeroFinish
import org.michimusic.mobile.ui.theme.AeroGradientRole
import org.michimusic.mobile.ui.theme.AeroSurfaceRole
import org.michimusic.mobile.ui.theme.InterFontFamily
import org.michimusic.mobile.ui.theme.LocalVisualPreferences
import org.michimusic.mobile.ui.theme.MichiShapes
import org.michimusic.mobile.ui.theme.PrimaryPinkContainer
import org.michimusic.mobile.ui.theme.PureWhite
import org.michimusic.mobile.ui.theme.SurfaceObsidian
import org.michimusic.mobile.ui.theme.TextSecondary
import org.michimusic.mobile.ui.theme.aeroResolveMaterial
import org.michimusic.mobile.ui.theme.aeroVerticalGradientBrush

/** Compact editorial pager over real library items. It never changes playback by swiping. */
@Composable
internal fun AeroHomeHeroCarousel(
    items: List<HomeHeroItem>,
    onPrimary: (HomeHeroItem) -> Unit,
    onPlayAll: () -> Unit,
    onShuffle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (items.isEmpty()) return
    val pagerState = rememberPagerState(pageCount = { items.size })
    var selectedKey by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(pagerState.currentPage, items) {
        selectedKey = items.getOrNull(pagerState.currentPage)?.key
    }
    LaunchedEffect(items) {
        val target = selectedKey?.let { key -> items.indexOfFirst { it.key == key } }
            ?.takeIf { it >= 0 }
            ?: pagerState.currentPage.coerceIn(0, items.lastIndex)
        if (pagerState.currentPage != target) pagerState.scrollToPage(target)
    }
    Column(modifier = modifier.testTag("home_hero_carousel")) {
        HorizontalPager(
            state = pagerState,
            key = { items[it].key },
            pageSpacing = 10.dp,
            modifier = Modifier.fillMaxWidth(),
        ) { page ->
            HomeHeroCard(
                item = items[page],
                onPrimary = { onPrimary(items[page]) },
                onPlayAll = onPlayAll,
                onShuffle = onShuffle,
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp)
                .semantics {
                    contentDescription = "Página ${pagerState.currentPage + 1} de ${items.size}"
                }
                .testTag("home_hero_page_indicator"),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            repeat(items.size) { index ->
                Box(
                    Modifier
                        .padding(horizontal = 2.dp)
                        .size(width = if (index == pagerState.currentPage) 18.dp else 5.dp, height = 3.dp)
                        .clip(CircleShape)
                        .background(
                            if (index == pagerState.currentPage) PrimaryPinkContainer
                            else PureWhite.copy(alpha = 0.22f),
                        ),
                )
            }
        }
    }
}

@Composable
private fun HomeHeroCard(
    item: HomeHeroItem,
    onPrimary: () -> Unit,
    onPlayAll: () -> Unit,
    onShuffle: () -> Unit,
) {
    val visual = LocalVisualPreferences.current
    val fontScale = LocalDensity.current.fontScale
    val shape = RoundedCornerShape(20.dp)
    val material = aeroResolveMaterial(
        role = AeroSurfaceRole.Raised,
        requestedFinish = AeroFinish.Anodized,
        profile = visual.appearanceProfile,
        highContrast = visual.highContrast,
        microtexturesEnabled = visual.microtexturesEnabled,
    )
    val grain = if (material.spec.grainAlpha > 0f) {
        ImageBitmap.imageResource(R.drawable.aero_graphite_grain)
    } else null
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(aeroVerticalGradientBrush(AeroGradientRole.Hero, visual.highContrast))
            .aeroFinishDrawing(material.finish, material.spec, grain, shape)
            .border(
                if (visual.highContrast) 1.2.dp else 0.65.dp,
                if (visual.highContrast) Color.White.copy(alpha = 0.86f) else Color.White.copy(alpha = 0.16f),
                shape,
            )
            .testTag("home_hero_page_${item.key}"),
    ) {
        val compact = maxWidth < 340.dp || fontScale >= 1.5f
        val stackedActions = maxWidth < 300.dp || fontScale >= 1.5f
        Column(
            modifier = Modifier.fillMaxWidth().padding(if (compact) 14.dp else 17.dp),
            verticalArrangement = Arrangement.spacedBy(11.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.size(5.dp).background(PrimaryPinkContainer, CircleShape))
                Text(
                    text = when (item.kind) {
                        HomeHeroKind.TRACK -> "SELECCIÓN DE TU BIBLIOTECA"
                        HomeHeroKind.ALBUM -> "ÁLBUM DE TU COLECCIÓN"
                        HomeHeroKind.PLAYLIST -> "PLAYLIST DE TU COLECCIÓN"
                    },
                    color = PrimaryPinkContainer,
                    fontFamily = InterFontFamily,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 0.8.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(if (compact) 11.dp else 15.dp),
            ) {
                val coverSize = if (compact) 92.dp else 112.dp
                AeroArtworkMat(
                    modifier = Modifier.size(coverSize),
                    cornerRadius = 12.dp,
                    inset = 2.dp,
                    emphasis = AeroArtworkEmphasis.Hero,
                ) {
                    if (item.kind == HomeHeroKind.PLAYLIST && item.coverIds.isNotEmpty()) {
                        PlaylistCoverMosaic(
                            coverIds = item.coverIds,
                            coverStyle = coverStyleFor(item.title),
                            modifier = Modifier.matchParentSize(),
                            size = coverSize,
                            cornerRadius = 12.dp,
                        )
                    } else {
                        AlbumArtView(
                            coverStyle = coverStyleFor(item.coverIds.firstOrNull().orEmpty().ifEmpty { item.title }),
                            imageModel = item.coverIds.firstOrNull(),
                            fallbackMediaUri = item.fallbackMediaUri,
                            modifier = Modifier.matchParentSize(),
                            cornerRadius = 12.dp,
                            borderWidth = 0.dp,
                        )
                    }
                }
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = item.title,
                        color = PureWhite,
                        fontFamily = InterFontFamily,
                        fontSize = if (compact) 19.sp else 22.sp,
                        lineHeight = if (compact) 23.sp else 27.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.testTag("home_hero_title_${item.key}"),
                    )
                    Text(
                        text = item.subtitle,
                        color = TextSecondary,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = item.meta,
                        color = PrimaryPinkContainer,
                        fontSize = 11.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (stackedActions) {
                Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    HeroAction(item.primaryLabel, onPrimary, Modifier.fillMaxWidth(), Icons.Filled.PlayArrow,
                        true, "m60_feature_play", item.primaryAccessibilityLabel)
                    Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        HeroAction("Todo", onPlayAll, Modifier.weight(1f), null, false, "quick_play_button",
                            "Reproducir todas las canciones")
                        HeroAction("Mezclar", onShuffle, Modifier.weight(1f), Icons.Filled.Shuffle, false, "shuffle_button")
                    }
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    HeroAction(item.primaryLabel, onPrimary, Modifier.weight(1f), Icons.Filled.PlayArrow,
                        true, "m60_feature_play", item.primaryAccessibilityLabel)
                    HeroAction("Todo", onPlayAll, Modifier.weight(1f), null, false, "quick_play_button",
                        "Reproducir todas las canciones")
                    HeroAction("Mezclar", onShuffle, Modifier.weight(1f), Icons.Filled.Shuffle, false, "shuffle_button")
                }
            }
        }
    }
}

@Composable
private fun HeroAction(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier,
    icon: ImageVector?,
    primary: Boolean,
    testTag: String,
    accessibilityLabel: String? = null,
) {
    val visual = LocalVisualPreferences.current
    val shape = RoundedCornerShape(11.dp)
    val base = when {
        !primary -> Color(0xFF20262E).copy(alpha = 0.84f)
        visual.highContrast -> Color(0xFFFFB1BC)
        else -> Color(0xFFE86277)
    }
    val border = when {
        primary && visual.highContrast -> Color.White.copy(alpha = 0.9f)
        primary -> Color(0xFFFFA8B4).copy(alpha = 0.46f)
        visual.highContrast -> Color.White.copy(alpha = 0.72f)
        else -> Color.White.copy(alpha = 0.15f)
    }
    val ink = if (primary) SurfaceObsidian else PureWhite
    Row(
        modifier = modifier
            .heightIn(min = 48.dp)
            .clip(shape)
            .background(base)
            .border(if (visual.highContrast) 1.1.dp else 0.55.dp, border, shape)
            .clickable(
                interactionSource = rememberHeroInteraction(),
                indication = ripple(color = if (primary) SurfaceObsidian else PureWhite),
                role = Role.Button,
                onClick = onClick,
            )
            .semantics { contentDescription = accessibilityLabel ?: label }
            .testTag(testTag)
            .padding(horizontal = 8.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = ink, modifier = Modifier.size(17.dp))
            Spacer(Modifier.width(5.dp))
        }
        BasicText(
            text = label,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.testTag("${testTag}_label"),
            autoSize = TextAutoSize.StepBased(minFontSize = 10.sp, maxFontSize = 12.sp, stepSize = 0.5.sp),
            style = TextStyle(
                fontFamily = InterFontFamily,
                color = ink,
                fontSize = 12.sp,
                lineHeight = 16.sp,
                fontWeight = FontWeight.SemiBold,
            ),
        )
    }
}

@Composable
private fun rememberHeroInteraction(): MutableInteractionSource =
    androidx.compose.runtime.remember { MutableInteractionSource() }
