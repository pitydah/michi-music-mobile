package org.michimusic.mobile.ui.components

/** UI5: pure sizing contract for the album-detail action strip, independent of UI4 motion. */
internal object AeroAlbumMicroMetrics {
    const val TOUCH_TARGET_DP = 48
    const val PLAY_SURFACE_DP = 44
    const val SECONDARY_SURFACE_DP = 40
    const val PLAY_GLYPH_DP = 22
    const val SECONDARY_GLYPH_DP = 20
    const val GAP_DP = 6
    const val ACTION_COUNT = 3

    /** Three disjoint 48 dp touch areas with two 6 dp gutters: 156 dp total. */
    const val GROUP_WIDTH_DP = ACTION_COUNT * TOUCH_TARGET_DP + (ACTION_COUNT - 1) * GAP_DP

    /** Used by the eventual post-UI4 host; never squeeze touch targets to fit. */
    fun fits(availableWidthDp: Float): Boolean =
        availableWidthDp.isFinite() && availableWidthDp >= GROUP_WIDTH_DP

    fun hasValidGeometry(): Boolean =
        PLAY_SURFACE_DP <= TOUCH_TARGET_DP &&
            SECONDARY_SURFACE_DP <= TOUCH_TARGET_DP &&
            PLAY_GLYPH_DP < PLAY_SURFACE_DP &&
            SECONDARY_GLYPH_DP < SECONDARY_SURFACE_DP &&
            GAP_DP >= 0
}

/** Non-overlapping target rectangles for any row that can host the strip. */
internal fun aeroAlbumActionTargetBounds(index: Int): IntRange? =
    if (index !in 0 until AeroAlbumMicroMetrics.ACTION_COUNT) null else {
        val start = index * (AeroAlbumMicroMetrics.TOUCH_TARGET_DP + AeroAlbumMicroMetrics.GAP_DP)
        start until start + AeroAlbumMicroMetrics.TOUCH_TARGET_DP
    }
