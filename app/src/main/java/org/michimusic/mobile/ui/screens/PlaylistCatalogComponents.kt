package org.michimusic.mobile.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
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
import org.michimusic.mobile.ui.theme.AeroHairline
import org.michimusic.mobile.ui.theme.PrimaryPinkContainer
import org.michimusic.mobile.ui.theme.PureWhite
import org.michimusic.mobile.ui.theme.SurfaceObsidian
import org.michimusic.mobile.ui.theme.TextSecondary

/**
 * Editorial lead item for the playlist catalog. It is one real playlist, never
 * a fabricated recommendation, and it is intentionally not an enlarged AeroCard.
 */
@Composable
internal fun PlaylistCatalogSpotlight(
    playlist: PlaylistItem,
    visual: PlaylistVisualState?,
    onOpen: () -> Unit,
    actions: @Composable RowScope.() -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(20.dp)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(198.dp)
            .clip(shape)
            .clickable(onClick = onOpen)
            .testTag("playlist_item_${playlist.id}"),
    ) {
        if (visual?.bannerUri != null) {
            AsyncImage(
                model = visual.bannerUri,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            0f to Color.Transparent,
                            0.42f to Color.Transparent,
                            0.72f to SurfaceObsidian.copy(alpha = 0.72f),
                            1f to SurfaceObsidian.copy(alpha = 0.97f),
                        ),
                    ),
            )
        } else {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Brush.linearGradient(listOf(AeroGraphiteRaised, AeroGraphite))),
            )
            // A quiet corner glow gives the graphite surface depth without
            // introducing glass, shaders or a second image.
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.radialGradient(
                            colors = listOf(PrimaryPinkContainer.copy(alpha = 0.10f), Color.Transparent),
                            radius = 520f,
                        ),
                    ),
            )
        }

        Row(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            content = actions,
        )

        Row(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, bottom = 15.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            PlaylistCover(
                playlist = playlist,
                visual = visual,
                size = 88.dp,
                corner = 14.dp,
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                if (visual?.pinned == true) {
                    Text(
                        text = "FIJADA",
                        color = PrimaryPinkContainer,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 1.2.sp,
                    )
                }
                Text(
                    text = playlist.name,
                    color = PureWhite,
                    fontSize = 22.sp,
                    lineHeight = 26.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = trackCountLabel(playlist.trackCount),
                    color = TextSecondary,
                    fontSize = 12.sp,
                )
            }
        }
    }
}

/**
 * Secondary catalog identity. A hairline-separated musical row, not a card.
 */
@Composable
internal fun PlaylistCatalogRow(
    playlist: PlaylistItem,
    visual: PlaylistVisualState?,
    onOpen: () -> Unit,
    actions: @Composable RowScope.() -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 80.dp)
                .clickable(onClick = onOpen)
                .padding(vertical = 10.dp)
                .testTag("playlist_item_${playlist.id}"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            PlaylistCover(
                playlist = playlist,
                visual = visual,
                size = 54.dp,
                corner = 10.dp,
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text(
                    text = playlist.name,
                    color = PureWhite,
                    fontSize = 16.sp,
                    lineHeight = 20.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (visual?.pinned == true) {
                        Box(
                            Modifier
                                .size(5.dp)
                                .clip(CircleShape)
                                .background(PrimaryPinkContainer),
                        )
                    }
                    Text(
                        text = trackCountLabel(playlist.trackCount),
                        color = TextSecondary,
                        fontSize = 12.sp,
                    )
                }
            }
            actions()
        }
        HorizontalDivider(
            thickness = 0.75.dp,
            color = AeroHairline,
        )
    }
}

@Composable
private fun PlaylistCover(
    playlist: PlaylistItem,
    visual: PlaylistVisualState?,
    size: androidx.compose.ui.unit.Dp,
    corner: androidx.compose.ui.unit.Dp,
) {
    if (visual?.coverUri != null) {
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
