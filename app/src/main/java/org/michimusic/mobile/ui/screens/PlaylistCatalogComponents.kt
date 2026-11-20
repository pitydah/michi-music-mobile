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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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
 * A one-pixel-offset shadow under the caption letters. It is what keeps the words
 * readable on a bright banner while the scrim stays soft, without any blur pass at
 * runtime: the blur radius is a text-layout value, not a RenderEffect.
 */
private fun captionShadow(blurRadius: Float): Shadow = Shadow(
    color = Color.Black.copy(alpha = 0.85f),
    offset = Offset(0f, 1.5f),
    blurRadius = blurRadius,
)

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
                        // UI8-KILLCRITIC: the 72% plate is gone; the words sit on a soft
                        // radial scrim plus a typographic shadow. Two edge-free variants
                        // were measured and rejected: an elliptical scrim (1.48:1 at the
                        // letters over a near-white banner) and a bottom-anchored gradient
                        // (2.94:1 at 320 dp/2.0x, where the caption spans 33%-74% of the
                        // card). WCAG AA for the words wins over the softer edge.
                        .drawBehind {
                            drawRect(
                                brush = Brush.radialGradient(
                                    colorStops = arrayOf(
                                        0f to Color.Black.copy(alpha = UI8VisualSpec.captionScrimAlpha),
                                        0.6f to Color.Black.copy(alpha = UI8VisualSpec.captionScrimAlpha * 0.92f),
                                        1f to Color.Transparent,
                                    ),
                                    center = Offset(size.width * 0.42f, size.height * 0.5f),
                                    radius = size.maxDimension * 1.05f,
                                ),
                            )
                        }
                        .padding(8.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    if (visual.pinned) {
                        Text(
                            text = "FIJADA",
                            color = PrimaryPinkContainer,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.SemiBold,
                            letterSpacing = 1.2.sp,
                            style = TextStyle(shadow = captionShadow(4f)),
                        )
                    }
                    Text(
                        text = playlist.name,
                        color = PureWhite,
                        fontSize = 22.sp,
                        lineHeight = 26.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = titleLines,
                        overflow = TextOverflow.Ellipsis,
                        style = TextStyle(shadow = captionShadow(7f)),
                        modifier = Modifier.testTag("playlist_spotlight_title_${playlist.id}"),
                    )
                    Text(
                        text = trackCountLabel(playlist.trackCount),
                        color = PureWhite,
                        fontSize = 12.sp,
                        style = TextStyle(shadow = captionShadow(5f)),
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
