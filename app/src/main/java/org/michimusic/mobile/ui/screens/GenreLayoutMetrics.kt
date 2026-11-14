package org.michimusic.mobile.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Library > Genres grid metrics (UI7 visual contract).
 *
 * 20dp side gutters, 10dp gaps, 4dp top and 28dp bottom padding are fixed. Only
 * the column count is adaptive: two columns require a 156dp legibility floor and
 * a measured numeric-chip width, so 320/360dp panels stay single-column and
 * 390/411/480 pair.
 *
 * Mosaico always uses the homogeneous 1.42 cell in one and two columns. Editorial
 * no longer has per-treatment fixed heights: its geometry comes from each real
 * file ratio through [editorialPlan].
 */
internal const val GENRE_GRID_SIDE_PADDING_DP = 20
internal const val GENRE_GRID_GAP_DP = 10
internal const val GENRE_GRID_TOP_PADDING_DP = 4
internal const val GENRE_GRID_BOTTOM_PADDING_DP = 28

/** Homogeneous Mosaico cell: every card of a breakpoint shares W and H = W / 1.42. */
internal const val GENRE_MOSAIC_ASPECT_RATIO = 1.42f

/** Artwork + numeric-chip legibility floor for a compact (two-column) card. */
internal const val GENRE_COMPACT_TILE_MIN_WIDTH_DP = 156

/** Horizontal breathing room inside a Compose fallback caption. */
internal const val GENRE_CARD_TEXT_PADDING_DP = 12

/** Editorial class boundaries (doc §3.2). */
internal const val EDITORIAL_COMPACT_MIN_RATIO = 1.40f
internal const val EDITORIAL_COMPACT_MAX_RATIO = 2.40f

/** Minimum legible width of each piece of an Editorial pair at fontScale 1. */
internal const val EDITORIAL_PAIR_MIN_WIDTH_DP = 150f

/** Extra pair minimum per unit of font scale above 1, up to 2x (doc §3.3 E2). */
internal const val EDITORIAL_PAIR_MIN_WIDTH_GROWTH_DP = 32f

/**
 * Nominal ratio used only by a fallback cell whose genre has no packaged poster.
 * It is never applied to a real asset, whose own ratio always wins.
 */
internal const val EDITORIAL_FALLBACK_RATIO = 1.70f

/** Widest supported count ("1234") measured for the chip floor. */
private const val genreCountSample = "1234"

/** Pure column arithmetic, used by unit tests and by the measured composable. */
internal fun genreGridColumns(availableWidthDp: Int, minimumTileWidthDp: Int): Int {
    require(minimumTileWidthDp > 0) { "minimumTileWidthDp must be positive" }
    val usable = availableWidthDp - 2 * GENRE_GRID_SIDE_PADDING_DP
    return if (usable >= 2 * minimumTileWidthDp + GENRE_GRID_GAP_DP) 2 else 1
}

/** Card width/height for the current panel width and column count. */
internal data class GenreMosaicCardSize(val widthDp: Float, val heightDp: Float)

internal fun mosaicCardSize(availableWidthDp: Float, columns: Int): GenreMosaicCardSize {
    require(columns >= 1)
    val usable = (availableWidthDp - 2 * GENRE_GRID_SIDE_PADDING_DP).coerceAtLeast(1f)
    val width = if (columns >= 2) {
        ((usable - GENRE_GRID_GAP_DP) / 2f).coerceAtLeast(1f)
    } else {
        usable
    }
    return GenreMosaicCardSize(width, width / GENRE_MOSAIC_ASPECT_RATIO)
}

/**
 * Columns from real constraints: the widest chip is measured with the active font
 * scale and the tile also keeps [GENRE_COMPACT_TILE_MIN_WIDTH_DP] for the baked
 * artwork title. Never derived from width x fontScale alone.
 */
@Composable
internal fun rememberGenreGridColumns(availableWidthDp: Float): Int {
    val density = LocalDensity.current
    val fontScale = density.fontScale
    val measurer = rememberTextMeasurer()
    val minimumTileWidthDp = remember(density, fontScale) {
        val measured = measurer.measure(genreCountSample, GenreCounterTextStyle)
        val textWidthDp = with(density) { measured.size.width.toDp().value }
        max(
            GENRE_COMPACT_TILE_MIN_WIDTH_DP.toFloat(),
            ceil(textWidthDp + 2 * GENRE_COUNTER_INSET_DP + GENRE_COUNTER_HORIZONTAL_PADDING_DP * 2),
        ).toInt()
    }
    return genreGridColumns(availableWidthDp.roundToInt(), minimumTileWidthDp)
}
