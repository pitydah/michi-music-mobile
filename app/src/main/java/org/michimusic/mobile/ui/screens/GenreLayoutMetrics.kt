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
 * 390/411/480 pair. The height of every cell comes from the treatment, never
 * from an asset's native ratio.
 */
internal const val GENRE_GRID_SIDE_PADDING_DP = 20
internal const val GENRE_GRID_GAP_DP = 10
internal const val GENRE_GRID_TOP_PADDING_DP = 4
internal const val GENRE_GRID_BOTTOM_PADDING_DP = 28

/** Artwork + numeric-chip legibility floor for a compact (two-column) card. */
internal const val GENRE_COMPACT_TILE_MIN_WIDTH_DP = 156

/** Horizontal breathing room inside a Compose fallback caption. */
internal const val GENRE_CARD_TEXT_PADDING_DP = 12

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
    return if (columns >= 2) {
        val width = ((usable - GENRE_GRID_GAP_DP) / 2f).coerceAtLeast(1f)
        GenreMosaicCardSize(width, (width * 5f / 7f))
    } else {
        GenreMosaicCardSize(usable, usable / 1.60f)
    }
}

/** Cover and portrait widths for the Editorial wall at the current panel width. */
internal data class GenreEditorialCardSize(
    val coverWidthDp: Float,
    val portraitWidthDp: Float,
    val coverHeightDp: Float,
    val portraitHeightDp: Float,
    val singleHeightDp: Float,
)

internal fun editorialCardSize(
    availableWidthDp: Float,
    columns: Int,
    fontScale: Float,
): GenreEditorialCardSize {
    require(columns >= 1)
    val usable = (availableWidthDp - 2 * GENRE_GRID_SIDE_PADDING_DP).coerceAtLeast(1f)
    val portrait = if (columns >= 2) {
        ((usable - GENRE_GRID_GAP_DP) / 2f).coerceAtLeast(1f)
    } else {
        usable
    }
    return GenreEditorialCardSize(
        coverWidthDp = usable,
        portraitWidthDp = portrait,
        coverHeightDp = genreEditorialHeightDp(GenreEditorialTreatment.COVER, columns, fontScale)
            .toFloat(),
        portraitHeightDp =
            genreEditorialHeightDp(GenreEditorialTreatment.PORTRAIT_LEFT, columns, fontScale)
                .toFloat(),
        singleHeightDp =
            genreEditorialHeightDp(GenreEditorialTreatment.COVER, 1, fontScale).toFloat(),
    )
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
