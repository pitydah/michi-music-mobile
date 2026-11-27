package org.michimusic.mobile.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import org.michimusic.mobile.screens.PlaylistItem
import org.michimusic.mobile.ui.components.AlbumArtView
import org.michimusic.mobile.ui.components.PlaylistCoverMosaic
import org.michimusic.mobile.ui.components.coverStyleFor
import org.michimusic.mobile.ui.theme.AeroGraphite
import org.michimusic.mobile.ui.theme.AeroGraphiteRaised
import org.michimusic.mobile.ui.theme.PrimaryPinkContainer
import org.michimusic.mobile.ui.theme.PureWhite
import org.michimusic.mobile.ui.theme.SurfaceObsidian
import org.michimusic.mobile.ui.theme.UI8VisualSpec

/**
 * A soft drop shadow under the caption letters. The black glyph outline in
 * [CaptionText] is what guarantees local 4.5:1 over bright artwork; the shadow
 * keeps the typographic edge instead of turning it into a hard sticker.
 */
private fun captionShadow(blurRadius: Float): Shadow = Shadow(
    color = Color.Black.copy(alpha = 0.85f),
    offset = Offset(0f, 1.5f),
    blurRadius = blurRadius,
)

/**
 * Caption typography with a real outline, not a surface. The black stroke tracks
 * every glyph at every font scale, so white text stays legible over a light or
 * photographic banner while the banner itself is untouched. The outline layer is
 * decoration only and never contributes semantics.
 */
@Composable
private fun CaptionText(
    text: String,
    color: Color,
    fontSize: TextUnit,
    modifier: Modifier = Modifier,
    lineHeight: TextUnit = TextUnit.Unspecified,
    fontWeight: FontWeight? = null,
    letterSpacing: TextUnit = TextUnit.Unspecified,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
    shadow: Shadow? = null,
    outlineWidth: Dp = 1.dp,
) {
    val outlinePx = with(LocalDensity.current) { outlineWidth.toPx() }
    Box(modifier = modifier) {
        Text(
            text = text,
            color = Color.Black,
            fontSize = fontSize,
            lineHeight = lineHeight,
            fontWeight = fontWeight,
            letterSpacing = letterSpacing,
            maxLines = maxLines,
            overflow = overflow,
            style = TextStyle(drawStyle = Stroke(width = outlinePx)),
            modifier = Modifier.clearAndSetSemantics {},
        )
        Text(
            text = text,
            color = color,
            fontSize = fontSize,
            lineHeight = lineHeight,
            fontWeight = fontWeight,
            letterSpacing = letterSpacing,
            maxLines = maxLines,
            overflow = overflow,
            style = TextStyle(shadow = shadow),
        )
    }
}

/**
 * Editorial hero for the first visible playlist. It grows with its content
 * instead of freezing a height that clips large fonts, and it applies the same
 * banner focal point and scale as the detail hero.
 */
@Composable
internal fun PlaylistCatalogSpotlight(
    playlist: PlaylistItem,
    visual: PlaylistVisualState,
    onOpen: () -> Unit,
    actions: @Composable RowScope.() -> Unit,
    modifier: Modifier = Modifier,
) {
    val fontScale = LocalDensity.current.fontScale
    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(UI8VisualSpec.playlistHeroCorner))
            .clickable(onClick = onOpen)
            .testTag("playlist_item_${playlist.id}"),
    ) {
        val widthDp = maxWidth.value
        val coverSize = playlistSpotlightCoverSizeDp(widthDp, fontScale)
        val titleLines = playlistSpotlightTitleLines(widthDp, fontScale)

        if (visual.bannerUri != null) {
            val focalX = safePlaylistBannerFocal(visual.bannerFocalX)
            val focalY = safePlaylistBannerFocal(visual.bannerFocalY)
            AsyncImage(
                model = visual.bannerUri,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                alignment = playlistFocalAlignment(visual.bannerFocalX, visual.bannerFocalY),
                modifier = Modifier
                    .matchParentSize()
                    .graphicsLayer {
                        val scale = safePlaylistScale(visual.bannerScale)
                        scaleX = scale
                        scaleY = scale
                        transformOrigin = TransformOrigin(focalX, focalY)
                    },
            )
            // UI8: never darken/fade the lower image. The banner stays intact.
        } else {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(
                        Brush.linearGradient(
                            listOf(AeroGraphiteRaised, AeroGraphite),
                        ),
                    ),
            )
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(
                        Brush.radialGradient(
                            colors = listOf(
                                PrimaryPinkContainer.copy(alpha = 0.10f),
                                Color.Transparent,
                            ),
                        ),
                    ),
            )
        }

        Column(
            // UI8-KILLCRITIC: one measurement contract for both modes. The card wraps
            // its content with the 198 dp floor whether it is the Lista hero or the
            // front of the deck, so switching mode cannot change its height.
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 198.dp)
                .padding(12.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("playlist_spotlight_actions_${playlist.id}"),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
                content = actions,
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                PlaylistCover(
                    playlist = playlist,
                    visual = visual,
                    size = coverSize.dp,
                    corner = 14.dp,
                )
                Column(
                    modifier = Modifier.weight(1f)
                        // UI8-KILLCRITIC: the 72% plate and the text-bounded radial
                        // scrim are gone. The words sit directly on the banner with a
                        // glyph outline plus a typographic shadow: no rectangle, no
                        // second surface, and the outline tracks every font scale.
                        .padding(8.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    if (visual.pinned) {
                        CaptionText(
                            text = "FIJADA",
                            color = PrimaryPinkContainer,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.SemiBold,
                            letterSpacing = 1.2.sp,
                            shadow = captionShadow(4f),
                            outlineWidth = 0.8.dp,
                        )
                    }
                    CaptionText(
                        text = playlist.name,
                        color = PureWhite,
                        fontSize = 22.sp,
                        lineHeight = 26.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = titleLines,
                        overflow = TextOverflow.Ellipsis,
                        shadow = captionShadow(7f),
                        outlineWidth = 1.2.dp,
                        modifier = Modifier.testTag("playlist_spotlight_title_${playlist.id}"),
                    )
                    CaptionText(
                        text = trackCountLabel(playlist.trackCount),
                        color = PureWhite,
                        fontSize = 12.sp,
                        shadow = captionShadow(5f),
                        outlineWidth = 1.2.dp,
                    )
                }
            }
        }
    }
}

@Composable
private fun PlaylistCover(
    playlist: PlaylistItem,
    visual: PlaylistVisualState,
    size: androidx.compose.ui.unit.Dp,
    corner: androidx.compose.ui.unit.Dp,
) {
    if (visual.coverUri != null) {
        AlbumArtView(
            coverStyle = coverStyleFor(playlist.name),
            imageModel = visual.coverUri,
            modifier = Modifier.size(size),
            cornerRadius = corner,
        )
    } else {
        PlaylistCoverMosaic(
            coverIds = playlist.coverIds,
            coverStyle = coverStyleFor(playlist.name),
            modifier = Modifier.size(size),
            size = size,
            cornerRadius = corner,
        )
    }
}

private fun trackCountLabel(count: Int): String =
    if (count == 1) "1 canción" else "$count canciones"
