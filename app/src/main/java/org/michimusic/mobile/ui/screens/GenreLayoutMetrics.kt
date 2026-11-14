package org.michimusic.mobile.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer
import org.michimusic.mobile.ui.theme.MichiTypography
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Library > Genres grid metrics.
 *
 * The grid always reserves the same 20dp side gutters, 10dp gaps, 4dp top and
 * 28dp bottom padding; only the column count is adaptive. Two columns are chosen
 * from a *measured* minimum tile width, so a narrow phone (320dp) and a 360dp
 * phone stay single-column, while 390/411/480 pair compact cards. Large text
 * scales are not forced into two columns when the measured label no longer fits.
 */
internal const val GENRE_GRID_SIDE_PADDING_DP = 20
internal const val GENRE_GRID_GAP_DP = 10
internal const val GENRE_GRID_TOP_PADDING_DP = 4
internal const val GENRE_GRID_BOTTOM_PADDING_DP = 28

/**
 * Artwork + baked-title legibility floor for a compact (two-column) card.
 *
 * 156dp (not 148dp) is the contract floor: a 360dp panel has 320dp of usable
 * width, so two 156dp cards plus the 10dp gap (322dp) no longer fit and the panel
 * stays single-column. 390dp (350dp usable) still pairs. Do not lower this to
 * re-create the old 360dp two-column behaviour: that squeeze truncated the
 * baked artwork titles.
 */
internal const val GENRE_COMPACT_TILE_MIN_WIDTH_DP = 156

/** Horizontal breathing room inside a compact card caption/footer. */
internal const val GENRE_CARD_TEXT_PADDING_DP = 12

/** Text used to measure the widest unbounded count label ("1234 pistas"). */
private const val genreCountSample = "999 pistas"

/** Pure column arithmetic, used by unit tests and by the measured composable. */
internal fun genreGridColumns(availableWidthDp: Int, minimumTileWidthDp: Int): Int {
    require(minimumTileWidthDp > 0) { "minimumTileWidthDp must be positive" }
    val usable = availableWidthDp - 2 * GENRE_GRID_SIDE_PADDING_DP
    return if (usable >= 2 * minimumTileWidthDp + GENRE_GRID_GAP_DP) 2 else 1
}

/**
 * Columns from real constraints: the track-count label is measured with the
 * active font scale and the tile must also keep [GENRE_COMPACT_TILE_MIN_WIDTH_DP]
 * for the baked artwork title. Never derived from width x fontScale alone.
 */
@Composable
internal fun rememberGenreGridColumns(availableWidthDp: Float): Int {
    val density = LocalDensity.current
    val fontScale = density.fontScale
    val measurer = rememberTextMeasurer()
    val minimumTileWidthDp = remember(density, fontScale) {
        val measured = measurer.measure(genreCountSample, MichiTypography.metadata)
        val textWidthDp = with(density) { measured.size.width.toDp().value }
        max(
            GENRE_COMPACT_TILE_MIN_WIDTH_DP.toFloat(),
            ceil(textWidthDp + 2 * GENRE_CARD_TEXT_PADDING_DP + 8f),
        ).toInt()
    }
    return genreGridColumns(availableWidthDp.roundToInt(), minimumTileWidthDp)
}
