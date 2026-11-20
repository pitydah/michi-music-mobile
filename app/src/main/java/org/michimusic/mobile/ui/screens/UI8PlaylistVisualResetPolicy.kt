package org.michimusic.mobile.ui.screens

/** Reset includes all six crop parameters, not only existing or newly selected images.
 * This is presentation-only; no file is deleted until the user confirms Save.
 */
internal fun playlistVisualResetNeeded(
    coverHasImage: Boolean, bannerHasImage: Boolean,
    coverFocalX: Float, coverFocalY: Float, coverScale: Float,
    bannerFocalX: Float, bannerFocalY: Float, bannerScale: Float,
): Boolean = coverHasImage || bannerHasImage ||
    coverFocalX != 0.5f || coverFocalY != 0.5f || coverScale != 1f ||
    bannerFocalX != 0.5f || bannerFocalY != 0.5f || bannerScale != 1f
