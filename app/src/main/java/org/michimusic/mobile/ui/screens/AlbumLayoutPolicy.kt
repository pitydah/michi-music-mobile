package org.michimusic.mobile.ui.screens

/** Full-width actions protect labels and touch targets on compact phones and scaled fonts. */
internal fun albumActionsStacked(screenWidthDp: Int, fontScale: Float): Boolean =
    screenWidthDp < 480 || !fontScale.isFinite() || fontScale >= 1.30f
