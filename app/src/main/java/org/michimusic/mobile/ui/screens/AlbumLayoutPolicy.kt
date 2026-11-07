package org.michimusic.mobile.ui.screens

internal enum class AlbumActionLayout { SINGLE_ROW, TWO_ROWS }

/** Never returns the old three-row layout; large text gets one primary row plus one icon row. */
internal fun albumActionLayout(screenWidthDp: Int, fontScale: Float): AlbumActionLayout =
    if (screenWidthDp < 300 || !fontScale.isFinite() || fontScale >= 1.5f) {
        AlbumActionLayout.TWO_ROWS
    } else {
        AlbumActionLayout.SINGLE_ROW
    }
