package org.michimusic.mobile.ui.components

/** Decisions use dp and font scale, with no Android dependencies for JVM regression tests. */
fun shouldStackAudioActions(availableWidthDp: Float, fontScale: Float): Boolean =
    !availableWidthDp.isFinite() || availableWidthDp < 350f ||
        !fontScale.isFinite() || fontScale >= 1.30f

/** Fixed artwork height is required when the player is vertically scrollable. */
fun shouldScrollNowPlaying(screenHeightDp: Int, fontScale: Float): Boolean =
    screenHeightDp < 685 || !fontScale.isFinite() || fontScale >= 1.30f

fun scrollArtworkHeightDp(screenHeightDp: Int): Int = when {
    screenHeightDp < 540 -> 168
    screenHeightDp < 660 -> 206
    else -> 240
}
