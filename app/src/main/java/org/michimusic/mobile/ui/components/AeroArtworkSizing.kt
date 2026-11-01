package org.michimusic.mobile.ui.components

/** Width/height are layout Dp. No binding to album IDs, audio state or pixel resolution. */
fun aeroArtworkSideDp(availableWidthDp: Float, availableHeightDp: Float): Float =
    minOf((availableWidthDp * 0.89f).coerceAtLeast(0f), availableHeightDp.coerceAtLeast(0f))
