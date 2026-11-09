package org.michimusic.mobile.ui.motion

import org.michimusic.mobile.ui.theme.MichiMotion

/** Pure UI4 policy: never infer playback or network state from animation state. */
fun aeroPressScale(enabled: Boolean, pressed: Boolean, reducedMotion: Boolean): Float =
    if (enabled && pressed && !reducedMotion) MichiMotion.feedbackPressedScale
    else MichiMotion.feedbackRestScale

/** Artist portrait feedback stays inside its existing tile and touch target. */
fun aeroTilePressScale(pressed: Boolean, reducedMotion: Boolean): Float =
    if (pressed && !reducedMotion) 0.985f else 1f

/** Accessibility policy independent of the Compose animation engine. */
fun aeroMotionDurationMillis(reducedMotion: Boolean, normalDurationMillis: Int): Int {
    require(normalDurationMillis >= 0) { "Animation duration must not be negative" }
    return if (reducedMotion) 0 else normalDurationMillis
}
