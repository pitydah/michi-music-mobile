package org.michimusic.mobile.ui.screens

internal enum class AlbumActionLayout { SINGLE_ROW, TWO_ROWS }

internal enum class AlbumHeroLayout { HORIZONTAL, STACKED }

/** Never returns the old three-row layout; large text gets one primary row plus one icon row. */
internal fun albumActionLayout(screenWidthDp: Int, fontScale: Float): AlbumActionLayout =
    if (screenWidthDp < 300 || !fontScale.isFinite() || fontScale >= 1.5f) {
        AlbumActionLayout.TWO_ROWS
    } else {
        AlbumActionLayout.SINGLE_ROW
    }

internal fun albumHeroLayout(screenWidthDp: Int, fontScale: Float): AlbumHeroLayout =
    if (screenWidthDp < 300 || screenWidthDp <= 412 && fontScale.isFinite() && fontScale >= 1.7f) {
        AlbumHeroLayout.STACKED
    } else {
        AlbumHeroLayout.HORIZONTAL
    }

internal fun albumHeroTitleMaxLines(layout: AlbumHeroLayout): Int = when (layout) {
    AlbumHeroLayout.HORIZONTAL -> 3
    AlbumHeroLayout.STACKED -> 5
}
