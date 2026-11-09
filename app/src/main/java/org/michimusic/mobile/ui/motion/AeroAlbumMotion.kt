package org.michimusic.mobile.ui.motion

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import org.michimusic.mobile.ui.theme.LocalVisualPreferences
import org.michimusic.mobile.ui.theme.MichiMotion

/**
 * The UI3 navigation identity is authoritative. Unique albums retain stable keys
 * when the grid is reordered; ambiguous duplicates are never animated as if they
 * were the same album after a rescan.
 */
fun albumMosaicMotionKey(identity: String, count: Int, index: Int): String =
    if (count == 1) "unique:$identity" else "duplicate:$identity#$index"

/** Physical press feedback, never an implicit playback or selection event. */
fun albumMosaicPressScale(pressed: Boolean, navigable: Boolean, reducedMotion: Boolean): Float =
    if (pressed && navigable && !reducedMotion) 0.975f else 1f

/** Hero starts just smaller and lower; never changes the bitmap or technical metadata. */
data class AeroAlbumHeroTarget(val opacity: Float, val scale: Float, val shift: Float)

fun aeroAlbumHeroTarget(visible: Boolean, reducedMotion: Boolean, highContrast: Boolean = false): AeroAlbumHeroTarget =
    if (visible || reducedMotion || highContrast) AeroAlbumHeroTarget(1f, 1f, 0f)
    else AeroAlbumHeroTarget(0f, 0.965f, 0.045f)

/**
 * Short, single-shot entrance for UI3's album cover. No periodic transitions,
 * no UI authority over loading, and no change to the album screen's size/geometry.
 */
@Composable
fun Modifier.aeroAlbumHeroReveal(visible: Boolean): Modifier {
    val preferences = LocalVisualPreferences.current
    val motionDisabled = preferences.reducedMotion || preferences.highContrast
    val target = aeroAlbumHeroTarget(visible, preferences.reducedMotion, preferences.highContrast)
    val alpha by animateFloatAsState(
        targetValue = target.opacity,
        animationSpec = if (motionDisabled) snap() else tween(
            durationMillis = MichiMotion.durationEmphasized,
            easing = MichiMotion.easingDecelerate,
        ),
        label = "aero_album_hero_opacity",
    )
    val scale by animateFloatAsState(
        targetValue = target.scale,
        animationSpec = if (motionDisabled) snap() else tween(
            durationMillis = MichiMotion.durationEmphasized,
            easing = MichiMotion.easingEmphasized,
        ),
        label = "aero_album_hero_scale",
    )
    val shift by animateFloatAsState(
        targetValue = target.shift,
        animationSpec = if (motionDisabled) snap() else tween(
            durationMillis = MichiMotion.durationEmphasized,
            easing = MichiMotion.easingDecelerate,
        ),
        label = "aero_album_hero_shift",
    )
    return this.graphicsLayer {
        this.alpha = alpha
        scaleX = scale
        scaleY = scale
        translationY = shift * size.height
    }
}
