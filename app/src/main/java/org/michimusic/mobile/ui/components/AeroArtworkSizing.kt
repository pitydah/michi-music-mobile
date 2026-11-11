package org.michimusic.mobile.ui.components

/**
 * Width/height are layout Dp. No binding to album IDs, audio state or pixel
 * resolution. The hero keeps a deliberate margin inside the available box and
 * never exceeds the free height; compact layouts use a slightly smaller share.
 */
fun aeroArtworkSideDp(
    availableWidthDp: Float,
    availableHeightDp: Float,
    compact: Boolean = false,
): Float {
    if (!availableWidthDp.isFinite() || !availableHeightDp.isFinite()) return 0f
    val widthFraction = if (compact) 0.91f else 0.94f
    return minOf(
        (availableWidthDp * widthFraction).coerceAtLeast(0f),
        availableHeightDp.coerceAtLeast(0f),
    )
}
