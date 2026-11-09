package org.michimusic.mobile.ui.screens

import android.os.Build
import android.os.PowerManager
import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import org.michimusic.mobile.ui.components.AlbumArtView
import org.michimusic.mobile.ui.components.PlaylistCoverMosaic
import org.michimusic.mobile.ui.components.coverStyleFor
import org.michimusic.mobile.ui.theme.PrimaryPinkContainer
import org.michimusic.mobile.ui.theme.PureWhite
import org.michimusic.mobile.ui.theme.SurfaceObsidian
import org.michimusic.mobile.ui.theme.AeroGraphiteRaised
import org.michimusic.mobile.ui.theme.LocalVisualPreferences
import org.michimusic.mobile.ui.theme.aeroPlaylistBannerBlurEnabled
import org.michimusic.mobile.ui.theme.aeroPlaylistPanelOpacity
import org.michimusic.mobile.ui.theme.TextSecondary

/**
 * Banner and cover are separate real sources. UI5 uses an opaque readable fallback
 * over the *actual* banner; Cristalino optionally blurs that image on API 31+.
 * It is NOT a backdrop capture, shader refraction, or cross-screen Glass.
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
    var bannerError by remember(visual.bannerUri) { mutableStateOf(false) }
    var bannerLoaded by remember(visual.bannerUri) { mutableStateOf(false) }
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
        .background(bg).testTag("playlist_visual_hero_$id")) {
        if (hasBanner && !bannerError) {
            AsyncImage(
                model = visual.bannerUri,
                contentDescription = "Banner de $title",
                contentScale = ContentScale.Crop,
                alignment = when {
                    visual.bannerFocalY < 0.35f -> Alignment.TopCenter
                    visual.bannerFocalY > 0.65f -> Alignment.BottomCenter
                    else -> Alignment.Center
                },
                onSuccess = { bannerLoaded = true },
                onError = { bannerError = true; bannerLoaded = false },
                // Exactly one actual loaded banner is blurred; no fake backdrop
                // sampling and no snapshot of another screen or surface.
                modifier = Modifier.matchParentSize()
                    .then(if (bannerImageBlur) Modifier.blur(10.dp) else Modifier)
                    .testTag("playlist_visual_banner"),
            )
        }
        Row(
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .background(SurfaceObsidian.copy(alpha = aeroPlaylistPanelOpacity(bannerImageBlur, highContrast)))
                .border(0.65.dp, PureWhite.copy(alpha = 0.14f), shape)
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (visual.coverUri != null) {
                AlbumArtView(
                    imageModel = visual.coverUri, coverStyle = coverStyleFor(title),
                    modifier = Modifier.size(58.dp).testTag("playlist_visual_cover"),
                    cornerRadius = 10.dp,
                )
            } else {
                PlaylistCoverMosaic(
                    coverIds = coverIds, coverStyle = coverStyleFor(title),
                    modifier = Modifier.size(58.dp).testTag("playlist_visual_cover"),
                    size = 58.dp, cornerRadius = 10.dp,
                )
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
