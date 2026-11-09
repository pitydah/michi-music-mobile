package org.michimusic.mobile.ui.screens

import android.os.Build
import android.os.PowerManager
import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImagePainter
import coil3.compose.rememberAsyncImagePainter
import org.michimusic.mobile.ui.components.AeroArtworkEmphasis
import org.michimusic.mobile.ui.components.AeroArtworkMat
import org.michimusic.mobile.ui.components.AlbumArtView
import org.michimusic.mobile.ui.components.PlaylistCoverMosaic
import org.michimusic.mobile.ui.components.coverStyleFor
import org.michimusic.mobile.ui.theme.PrimaryPinkContainer
import org.michimusic.mobile.ui.theme.PureWhite
import org.michimusic.mobile.ui.theme.SurfaceObsidian
import org.michimusic.mobile.ui.theme.AeroGraphiteRaised
import org.michimusic.mobile.ui.theme.LocalVisualPreferences
import org.michimusic.mobile.ui.theme.aeroPlaylistBannerBlurEnabled
import org.michimusic.mobile.ui.theme.aeroPlaylistGlassRegionTopPx
import org.michimusic.mobile.ui.theme.aeroPlaylistPanelOpacity
import org.michimusic.mobile.ui.theme.TextSecondary

/**
 * Banner and cover are separate real sources. The banner itself always stays
 * sharp; Cristalino may reuse the SAME loaded painter to blur only the strip
 * behind the information tray. It is NOT a backdrop capture, shader
 * refraction, or cross-screen Glass.
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
    val hasBanner = visual.bannerUri != null
    val bannerPainter = rememberAsyncImagePainter(
        model = visual.bannerUri,
        contentScale = ContentScale.Crop,
    )
    val bannerState by bannerPainter.state.collectAsStateWithLifecycle()
    val bannerLoaded = bannerState is AsyncImagePainter.State.Success
    val bannerError = bannerState is AsyncImagePainter.State.Error
    val bannerAlignment = when {
        visual.bannerFocalY < 0.35f -> Alignment.TopCenter
        visual.bannerFocalY > 0.65f -> Alignment.BottomCenter
        else -> Alignment.Center
    }
    var heroSize by remember { mutableStateOf(IntSize.Zero) }
    var traySize by remember { mutableStateOf(IntSize.Zero) }
    val density = LocalDensity.current
    val bg = Brush.linearGradient(listOf(AeroGraphiteRaised, SurfaceObsidian))
    val preferences = LocalVisualPreferences.current
    val highContrast = preferences.highContrast
    // Read real Android device constraints, then listen only while this banner
    // is mounted. No polling, per-frame allocations, or bitmap capture.
    val appContext = LocalContext.current.applicationContext
    val powerManager = remember(appContext) {
        appContext.getSystemService(Context.POWER_SERVICE) as? PowerManager
    }
    val lowRamDevice = remember(appContext) {
        (appContext.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager)?.isLowRamDevice == true
    }
    var powerSaveMode by remember(appContext) { mutableStateOf(powerManager?.isPowerSaveMode == true) }
    val needsPowerObserver = hasBanner &&
        preferences.appearanceProfile == org.michimusic.mobile.ui.theme.AeroAppearanceProfile.CRISTALINO &&
        Build.VERSION.SDK_INT >= 31
    DisposableEffect(appContext, powerManager, needsPowerObserver) {
        if (!needsPowerObserver) {
            onDispose { }
        } else {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
                    powerSaveMode = powerManager?.isPowerSaveMode == true
            }
        }
        val registered = runCatching {
            ContextCompat.registerReceiver(appContext, receiver,
                IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED),
                ContextCompat.RECEIVER_NOT_EXPORTED)
        }.isSuccess
        onDispose { if (registered) runCatching { appContext.unregisterReceiver(receiver) } }
        }
    }
    val bannerImageBlur = aeroPlaylistBannerBlurEnabled(
        preferences.appearanceProfile, Build.VERSION.SDK_INT,
        bannerLoaded && !bannerError, highContrast, preferences.reducedMotion,
        powerSaveMode = powerSaveMode, lowRamDevice = lowRamDevice,
    )
    Box(modifier.fillMaxWidth().heightIn(min = 150.dp).clip(shape)
        .background(bg).onSizeChanged { heroSize = it }
        .testTag("playlist_visual_hero_$id")) {
        if (hasBanner && !bannerError) {
            Image(
                painter = bannerPainter,
                contentDescription = "Banner de $title",
                contentScale = ContentScale.Crop,
                alignment = bannerAlignment,
                // The full image remains sharp. No fake backdrop sampling and
                // no snapshot of another screen or surface.
                modifier = Modifier.matchParentSize()
                    .testTag("playlist_visual_banner"),
            )
            // Reuse the same loaded painter for a second, tray-sized draw.
            // The layer below is clipped to the information tray and offset
            // so crop, focal point and scale stay pixel-identical to the base.
            if (aeroPlaylistGlassRegionTopPx(heroSize.height, traySize.height, bannerImageBlur) != null) {
                val trayHeight = with(density) { traySize.height.toDp() }
                val heroHeight = with(density) { heroSize.height.toDp() }
                Box(
                    modifier = Modifier.align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(trayHeight)
                        .clipToBounds()
                        .testTag("playlist_visual_banner_glass"),
                ) {
                    Image(
                        painter = bannerPainter,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        alignment = bannerAlignment,
                        modifier = Modifier.fillMaxWidth()
                            .height(heroHeight)
                            .offset(y = -(heroHeight - trayHeight))
                            .blur(10.dp),
                    )
                }
            }
        }
        Row(
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .background(SurfaceObsidian.copy(alpha = aeroPlaylistPanelOpacity(bannerImageBlur, highContrast)))
                .border(0.65.dp, PureWhite.copy(alpha = 0.14f), shape)
                .drawBehind {
                    val line = 0.65.dp.toPx()
                    drawLine(
                        brush = Brush.horizontalGradient(
                            listOf(Color.White.copy(alpha = 0.22f), Color.White.copy(alpha = 0.07f)),
                        ),
                        start = Offset(0f, line / 2f), end = Offset(size.width, line / 2f),
                        strokeWidth = line,
                    )
                    drawLine(
                        brush = Brush.verticalGradient(
                            listOf(Color.White.copy(alpha = 0.10f), Color.Transparent),
                        ),
                        start = Offset(line / 2f, 0f), end = Offset(line / 2f, size.height),
                        strokeWidth = line,
                    )
                }
                .onSizeChanged { traySize = it }
                .testTag("playlist_visual_tray")
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            AeroArtworkMat(
                modifier = Modifier.size(58.dp),
                cornerRadius = 10.dp,
                inset = 2.dp,
                emphasis = AeroArtworkEmphasis.Featured,
            ) {
                if (visual.coverUri != null) {
                    AlbumArtView(
                        imageModel = visual.coverUri, coverStyle = coverStyleFor(title),
                        modifier = Modifier.matchParentSize().testTag("playlist_visual_cover"),
                        cornerRadius = 10.dp,
                    )
                } else {
                    PlaylistCoverMosaic(
                        coverIds = coverIds, coverStyle = coverStyleFor(title),
                        modifier = Modifier.matchParentSize().testTag("playlist_visual_cover"),
                        size = 58.dp, cornerRadius = 10.dp,
                    )
                }
            }
            Column(modifier = Modifier.weight(1f)) {
                Text("PLAYLIST", color = PrimaryPinkContainer)
                Text(title, color = PureWhite, fontWeight = FontWeight.SemiBold,
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.testTag("playlist_detail_title"))
                Text(if (trackCount == 1) "1 canción" else "$trackCount canciones",
                    color = TextSecondary, modifier = Modifier.testTag("playlist_detail_track_count"))
            }
        }
    }
}
