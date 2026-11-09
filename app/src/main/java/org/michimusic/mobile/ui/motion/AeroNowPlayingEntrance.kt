package org.michimusic.mobile.ui.motion

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import org.michimusic.mobile.ui.theme.LocalVisualPreferences
import org.michimusic.mobile.ui.theme.MichiMotion

/** A finite reveal when the Now Playing destination first enters composition. */
fun aeroNowPlayingInitialScale(enabled: Boolean, reducedMotion: Boolean): Float =
    if (enabled && !reducedMotion) 0.965f else 1f

fun aeroNowPlayingInitialAlpha(enabled: Boolean, reducedMotion: Boolean): Float =
    if (enabled && !reducedMotion) 0.78f else 1f

/**
 * Fallback entrance while NavHost M52 continues to forbid global crossfades.
 * This is NOT a claim of geometric continuity with the MiniPlayer thumbnail.
 * Its target never depends on audio position, buffer, route, or a network timer.
 */
@Composable
fun Modifier.aeroNowPlayingHeroEnter(hasTrack: Boolean): Modifier {
    val preferences = LocalVisualPreferences.current
    val active = hasTrack && !preferences.reducedMotion && !preferences.highContrast
    var entered by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { entered = true }
    val targetScale = if (entered || !active) 1f else aeroNowPlayingInitialScale(true, false)
    val targetAlpha = if (entered || !active) 1f else aeroNowPlayingInitialAlpha(true, false)
    val scale by animateFloatAsState(
        targetValue = targetScale,
        animationSpec = if (!active) snap() else tween(
            MichiMotion.durationEmphasized, easing = MichiMotion.easingEmphasized,
        ),
        label = "aero_nowplaying_entry_scale",
    )
    val alpha by animateFloatAsState(
        targetValue = targetAlpha,
        animationSpec = if (!active) snap() else tween(MichiMotion.durationStandard),
        label = "aero_nowplaying_entry_opacity",
    )
    return this.graphicsLayer {
        scaleX = scale
        scaleY = scale
        this.alpha = alpha
    }
}
