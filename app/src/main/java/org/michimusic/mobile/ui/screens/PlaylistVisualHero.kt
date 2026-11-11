package org.michimusic.mobile.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import coil3.compose.rememberAsyncImagePainter
import org.michimusic.mobile.ui.components.AeroArtworkEmphasis
import org.michimusic.mobile.ui.components.AeroArtworkMat
import org.michimusic.mobile.ui.components.PlaylistCoverMosaic
import org.michimusic.mobile.ui.components.coverStyleFor
import org.michimusic.mobile.ui.theme.AeroGraphite
import org.michimusic.mobile.ui.theme.LocalVisualPreferences
import org.michimusic.mobile.ui.theme.MichiTypography
import org.michimusic.mobile.ui.theme.PrimaryPinkContainer
import org.michimusic.mobile.ui.theme.PureWhite
import org.michimusic.mobile.ui.theme.SurfaceObsidian
import org.michimusic.mobile.ui.theme.TextSecondary

/**
 * ONE continuous Aero card: full-bleed banner, bottom-left cover and a
 * continuous atmospheric scrim behind the text. No second cropped image, no
 * visible title plate and no per-frame brightness sampling.
 */
@Composable
internal fun PlaylistVisualHero(
    id: String,
    title: String,
    trackCount: Int,
    coverIds: List<String>,
    visual: PlaylistVisualState,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(20.dp)
    val coverSize = playlistHeroCoverSizeDp(
        LocalConfiguration.current.screenWidthDp.toFloat(),
    ).dp
    val bannerPainter = rememberAsyncImagePainter(
        model = visual.bannerUri,
        contentScale = ContentScale.Crop,
    )
    val bannerState by bannerPainter.state.collectAsStateWithLifecycle()
    val bannerError = bannerState is AsyncImagePainter.State.Error
    val hasBanner = visual.bannerUri != null && !bannerError
    val bannerAlignment = playlistFocalAlignment(visual.bannerFocalX, visual.bannerFocalY)
    val coverAlignment = playlistFocalAlignment(visual.coverFocalX, visual.coverFocalY)
    val bannerOrigin = TransformOrigin(
        safePlaylistBannerFocal(visual.bannerFocalX),
        safePlaylistBannerFocal(visual.bannerFocalY),
    )
    val coverOrigin = TransformOrigin(
        safePlaylistBannerFocal(visual.coverFocalX),
        safePlaylistBannerFocal(visual.coverFocalY),
    )
    var coverFailed by remember(visual.coverUri) { mutableStateOf(false) }
    val preferences = LocalVisualPreferences.current
    val highContrast = preferences.highContrast
    val fallbackBrush = Brush.linearGradient(listOf(AeroGraphite, SurfaceObsidian))

    Box(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = PLAYLIST_HERO_MIN_HEIGHT_DP.dp)
            .clip(shape)
            .background(fallbackBrush)
            .testTag("playlist_visual_hero_$id"),
    ) {
        if (hasBanner) {
            Image(
                painter = bannerPainter,
                contentDescription = "Banner de $title",
                contentScale = ContentScale.Crop,
                alignment = bannerAlignment,
                modifier = Modifier
                    .matchParentSize()
                    .graphicsLayer(
                        scaleX = safePlaylistScale(visual.bannerScale),
                        scaleY = safePlaylistScale(visual.bannerScale),
                        transformOrigin = bannerOrigin,
                    )
                    .testTag("playlist_visual_banner"),
            )
            // A continuous atmospheric scrim, never a rectangle around the text.
            // High contrast uses the same full-width surface at full opacity.
            val scrim = if (highContrast) Brush.verticalGradient(
                0f to Color.Transparent,
                0.30f to Color.Transparent,
                1f to SurfaceObsidian,
            ) else Brush.verticalGradient(
                0f to Color.Transparent,
                0.52f to Color.Transparent,
                0.76f to SurfaceObsidian.copy(alpha = 0.12f),
                1f to SurfaceObsidian.copy(alpha = 0.52f),
            )
            Box(modifier = Modifier.matchParentSize().background(scrim))
        }

        Row(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 14.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            AeroArtworkMat(
                modifier = Modifier.size(coverSize),
                cornerRadius = 12.dp,
                inset = 2.dp,
                emphasis = AeroArtworkEmphasis.Featured,
            ) {
                if (visual.coverUri != null && !coverFailed) {
                    AsyncImage(
                        model = visual.coverUri,
                        contentDescription = "Portada de $title",
                        contentScale = ContentScale.Crop,
                        alignment = coverAlignment,
                        onError = { coverFailed = true },
                        modifier = Modifier
                            .matchParentSize()
                            .graphicsLayer(
                                scaleX = safePlaylistScale(visual.coverScale),
                                scaleY = safePlaylistScale(visual.coverScale),
                                transformOrigin = coverOrigin,
                            )
                            .testTag("playlist_visual_cover"),
                    )
                } else {
                    PlaylistCoverMosaic(
                        coverIds = coverIds,
                        coverStyle = coverStyleFor(title),
                        modifier = Modifier.matchParentSize().testTag("playlist_visual_cover"),
                        size = coverSize,
                        cornerRadius = 12.dp,
                    )
                }
            }

            // Text sits directly on the continuous scrim. No plate, border,
            // or secondary image edge may reveal a separate surface.
            Column(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 9.dp),
            ) {
                Text(
                    text = "PLAYLIST",
                    color = PrimaryPinkContainer,
                    fontSize = 10.sp,
                    lineHeight = 12.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.2.sp,
                )
                Text(
                    text = title,
                    style = MichiTypography.sectionTitle.copy(
                        fontSize = 22.sp,
                        lineHeight = 26.sp,
                        shadow = Shadow(
                            color = Color.Black.copy(alpha = 0.60f),
                            offset = Offset(0f, 1.5f),
                            blurRadius = 5f,
                        ),
                    ),
                    color = PureWhite,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .padding(top = 4.dp)
                        .testTag("playlist_detail_title"),
                )
                Text(
                    text = if (trackCount == 1) "1 canción" else "$trackCount canciones",
                    style = MichiTypography.metadata,
                    color = TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.testTag("playlist_detail_track_count"),
                )
            }
        }
    }
}
