package org.michimusic.mobile.ui.screens

import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.PowerManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import coil3.compose.rememberAsyncImagePainter
import org.michimusic.mobile.ui.components.AeroArtworkEmphasis
import org.michimusic.mobile.ui.components.AeroArtworkMat
import org.michimusic.mobile.ui.components.PlaylistCoverMosaic
import org.michimusic.mobile.ui.components.coverStyleFor
import org.michimusic.mobile.ui.theme.AeroAppearanceProfile
import org.michimusic.mobile.ui.theme.AeroGraphite
import org.michimusic.mobile.ui.theme.LocalVisualPreferences
import org.michimusic.mobile.ui.theme.MichiTypography
import org.michimusic.mobile.ui.theme.PrimaryPinkContainer
import org.michimusic.mobile.ui.theme.PureWhite
import org.michimusic.mobile.ui.theme.SurfaceObsidian
import org.michimusic.mobile.ui.theme.TextSecondary
import org.michimusic.mobile.ui.theme.aeroPlaylistBannerBlurEnabled

/**
 * ONE continuous Aero card: full-bleed banner, a bottom-left cover, and a
 * localized information plate. Cristalino may reuse the SAME loaded painter to
 * blur only the plate region; the base image always stays sharp. No per-frame
 * brightness sampling, no bitmap capture, no Haze.
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
    val shape = RoundedCornerShape(18.dp)
    val coverSize = playlistHeroCoverSizeDp(
        LocalConfiguration.current.screenWidthDp.toFloat(),
    ).dp
    val bannerPainter = rememberAsyncImagePainter(
        model = visual.bannerUri,
        contentScale = ContentScale.Crop,
    )
    val bannerState by bannerPainter.state.collectAsStateWithLifecycle()
    val bannerLoaded = bannerState is AsyncImagePainter.State.Success
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
    var heroSize by remember { mutableStateOf(IntSize.Zero) }
    var heroOrigin by remember { mutableStateOf(Offset.Zero) }
    var plateSize by remember { mutableStateOf(IntSize.Zero) }
    var plateOrigin by remember { mutableStateOf(Offset.Zero) }
    val density = LocalDensity.current
    val preferences = LocalVisualPreferences.current
    val highContrast = preferences.highContrast
    val appContext = LocalContext.current.applicationContext
    val powerManager = remember(appContext) {
        appContext.getSystemService(Context.POWER_SERVICE) as? PowerManager
    }
    val lowRamDevice = remember(appContext) {
        (appContext.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager)?.isLowRamDevice == true
    }
    var powerSaveMode by remember(appContext) {
        mutableStateOf(powerManager?.isPowerSaveMode == true)
    }
    val needsPowerObserver = hasBanner &&
        preferences.appearanceProfile == AeroAppearanceProfile.CRISTALINO &&
        Build.VERSION.SDK_INT >= 31
    DisposableEffect(appContext, powerManager, needsPowerObserver) {
        if (!needsPowerObserver) {
            onDispose { }
        } else {
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    if (intent?.action == PowerManager.ACTION_POWER_SAVE_MODE_CHANGED) {
                        powerSaveMode = powerManager?.isPowerSaveMode == true
                    }
                }
            }
            val registered = runCatching {
                ContextCompat.registerReceiver(
                    appContext,
                    receiver,
                    IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED),
                    ContextCompat.RECEIVER_NOT_EXPORTED,
                )
            }.isSuccess
            onDispose { if (registered) runCatching { appContext.unregisterReceiver(receiver) } }
        }
    }
    val bannerImageBlur = aeroPlaylistBannerBlurEnabled(
        preferences.appearanceProfile,
        Build.VERSION.SDK_INT,
        bannerLoaded && !bannerError,
        highContrast,
        preferences.reducedMotion,
        powerSaveMode = powerSaveMode,
        lowRamDevice = lowRamDevice,
    )
    val fallbackBrush = Brush.linearGradient(listOf(AeroGraphite, SurfaceObsidian))

    Box(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = PLAYLIST_HERO_MIN_HEIGHT_DP.dp)
            .clip(shape)
            .background(fallbackBrush)
            .onSizeChanged { heroSize = it }
            .onGloballyPositioned { heroOrigin = it.positionInRoot() }
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
            // A soft bottom scrim grounds the cover and plate without becoming
            // a second opaque card or blurring the whole banner.
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(
                        Brush.verticalGradient(
                            0.55f to Color.Transparent,
                            1f to SurfaceObsidian.copy(alpha = 0.36f),
                        ),
                    ),
            )
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

            Box(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .onSizeChanged { plateSize = it }
                    .onGloballyPositioned { plateOrigin = it.positionInRoot() }
                    .testTag("playlist_visual_tray"),
            ) {
                // Localized Cristalino strip: the same painter, same crop and same
                // focal point, clipped to the plate. Whole-image blur is never used.
                if (hasBanner && bannerImageBlur &&
                    heroSize != IntSize.Zero && plateSize != IntSize.Zero
                ) {
                    val heroWidth = with(density) { heroSize.width.toDp() }
                    val heroHeight = with(density) { heroSize.height.toDp() }
                    val offsetX = with(density) { -(plateOrigin.x - heroOrigin.x).toDp() }
                    val offsetY = with(density) { -(plateOrigin.y - heroOrigin.y).toDp() }
                    Box(modifier = Modifier.matchParentSize().clipToBounds()) {
                        Image(
                            painter = bannerPainter,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            alignment = bannerAlignment,
                            modifier = Modifier
                                .size(heroWidth, heroHeight)
                                .offset(x = offsetX, y = offsetY)
                                .graphicsLayer(
                                    scaleX = safePlaylistScale(visual.bannerScale),
                                    scaleY = safePlaylistScale(visual.bannerScale),
                                    transformOrigin = bannerOrigin,
                                )
                                .blur(10.dp),
                        )
                    }
                }
                // Localized readability plate: graphite over the base image (and
                // over the optional blur strip) keeps title/count legible. High
                // contrast makes it fully opaque.
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .background(
                            if (highContrast) Brush.verticalGradient(
                                listOf(SurfaceObsidian, SurfaceObsidian),
                            ) else Brush.verticalGradient(
                                0f to SurfaceObsidian.copy(alpha = 0.18f),
                                1f to SurfaceObsidian.copy(alpha = 0.74f),
                            ),
                        ),
                )
                Column(
                    modifier = Modifier
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
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(AeroGraphite.copy(alpha = if (highContrast) 1f else 0.92f))
                            .border(
                                0.65.dp,
                                PrimaryPinkContainer.copy(alpha = if (highContrast) 1f else 0.62f),
                                RoundedCornerShape(6.dp),
                            )
                            .padding(horizontal = 7.dp, vertical = 3.dp),
                    )
                    Text(
                        text = title,
                        style = MichiTypography.sectionTitle,
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
}
