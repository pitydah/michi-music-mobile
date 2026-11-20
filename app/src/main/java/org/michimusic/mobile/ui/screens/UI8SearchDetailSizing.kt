package org.michimusic.mobile.ui.screens

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Short lists wrap; long ones scroll within a bounded share of the viewport. */
internal fun searchDetailHeight(
    trackCount: Int,
    fontScale: Float,
    hasSubtitle: Boolean,
    availableViewport: Dp = 600.dp,
): Dp {
    if (trackCount <= 0) return 0.dp
    val scale = fontScale.takeIf { it.isFinite() && it > 0f }?.coerceIn(1f, 3f) ?: 1f
    // Each row is one line, or two for artists, with 16dp padding and 6dp spacing.
    val base = if (hasSubtitle) 42f else 40f
    val perRow = base + (scale - 1f) * if (hasSubtitle) 24f else 16f
    val gaps = (trackCount - 1).coerceAtLeast(0) * 6f
    val viewport = availableViewport.value.takeIf { it.isFinite() && it > 0f } ?: 600f
    val cap = (viewport * 0.42f).coerceIn(120f, 360f)
    return minOf(cap, trackCount * perRow + gaps).dp
}
