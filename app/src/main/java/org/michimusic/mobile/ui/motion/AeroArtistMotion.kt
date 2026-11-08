package org.michimusic.mobile.ui.motion

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import org.michimusic.mobile.ui.theme.LocalVisualPreferences
import org.michimusic.mobile.ui.theme.MichiMotion

/** Artist hero is local motion, not a shared photograph: HERO and THUMB can be different files. */
fun aeroArtistHeroInitialScale(enabled: Boolean): Float = if (enabled) 0.97f else 1f
fun aeroArtistHeroInitialAlpha(enabled: Boolean): Float = if (enabled) 0.82f else 1f

/** Recomposition from cache refresh, biography or playback will not restart the entrance. */
@Composable
fun Modifier.aeroArtistHeroReveal(artistCredit: String): Modifier {
    val prefs = LocalVisualPreferences.current
    val active = !prefs.reducedMotion && !prefs.highContrast
    var entered by rememberSaveable(artistCredit) { mutableStateOf(false) }
    LaunchedEffect(artistCredit) { entered = true }
    val scale by animateFloatAsState(
        targetValue = if (entered || !active) 1f else aeroArtistHeroInitialScale(true),
        animationSpec = if (!active) snap() else tween(
            durationMillis = MichiMotion.durationEmphasized,
            easing = MichiMotion.easingEmphasized,
        ),
        label = "aero_artist_hero_scale",
    )
    val alpha by animateFloatAsState(
        targetValue = if (entered || !active) 1f else aeroArtistHeroInitialAlpha(true),
        animationSpec = if (!active) snap() else tween(MichiMotion.durationStandard),
        label = "aero_artist_hero_alpha",
    )
    return this.graphicsLayer {
        scaleX = scale
        scaleY = scale
        this.alpha = alpha
    }
}
