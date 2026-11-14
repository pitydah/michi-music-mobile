package org.michimusic.mobile.ui.screens

import kotlin.math.roundToInt

/**
 * UI7 Phase C genre-detail header metrics.
 *
 * The header keeps a phone-height band between 160dp and 192dp. Normal text
 * uses 168dp; the band grows only as far as the 192dp cap when the active font
 * scale needs an extra line. The value is never derived from artwork geometry,
 * so a missing/short asset cannot shrink the header below the touch-safe band.
 */
internal const val GENRE_DETAIL_HEADER_MIN_DP = 160
internal const val GENRE_DETAIL_HEADER_MAX_DP = 192

internal fun genreDetailHeaderHeightDp(fontScale: Float): Int {
    val scale = fontScale.takeIf { it.isFinite() }?.coerceIn(1f, 2f) ?: 1f
    return (168f + (scale - 1f) * 24f).roundToInt()
        .coerceIn(GENRE_DETAIL_HEADER_MIN_DP, GENRE_DETAIL_HEADER_MAX_DP)
}

/**
 * The approved Editorial pack is bimodal: 162 posters are 1.47..2.08:1 and 54
 * are 3.18..4.24:1, with no asset between 2.1 and 3.2. The header only guards
 * the narrow family, whose width-led crop is the one that can reach the baked
 * title block; the panoramic family keeps its Phase C presentation unchanged.
 */
internal const val EDITORIAL_NARROW_MAX_RATIO = 2.6f

/**
 * Bottom of the baked title + subtitle + accent block, as a fraction of the
 * poster height, for the approved narrow family.
 *
 * Measured on the 216 approved WebPs: the widest baked block ends at 0.48
 * (genre_psychedelic_rock_editorial, genre_french_house_editorial); 0.50 keeps
 * a 2% margin for antialiasing and the poster's top frame. The header never
 * guesses from a filename, and it never edits the approved WebP.
 */
internal const val EDITORIAL_TITLE_ZONE_FRACTION = 0.50f

/**
 * Height of an opaque title matte at the top of the artwork band, in dp.
 *
 * The Editorial poster is drawn width-led and bottom-anchored. When the band is
 * wide (480dp) the visible window already starts below the baked title; when it
 * is narrow (320..390dp) the same crop reaches up into the title block and
 * slices its subtitle/accent line. The header replaces exactly that exposed
 * remainder with the design-system matte, so the first visible artwork row is
 * [EDITORIAL_TITLE_ZONE_FRACTION] at every width: no partial glyph, no partial
 * frame corner, no duplicated title, and no zoom, stretch or `FillBounds`.
 *
 * Returns 0 when the current crop is already title-safe, for the panoramic
 * family, and for invalid geometry.
 */
internal fun editorialTitleGuardDp(
    posterRatio: Float,
    bandWidthDp: Float,
    bandHeightDp: Float,
): Float {
    if (!posterRatio.isFinite() || posterRatio <= 0f ||
        !bandWidthDp.isFinite() || bandWidthDp <= 0f ||
        !bandHeightDp.isFinite() || bandHeightDp <= 0f
    ) {
        return 0f
    }
    if (posterRatio >= EDITORIAL_NARROW_MAX_RATIO) return 0f
    // Width-led crop: the band shows this fraction of the poster height.
    val visibleFraction = bandHeightDp * posterRatio / bandWidthDp
    if (visibleFraction >= 1f) return 0f // Height-led: the whole poster is visible.
    val windowTop = 1f - visibleFraction
    if (windowTop >= EDITORIAL_TITLE_ZONE_FRACTION) return 0f // Crop is already title-safe.
    val drawnHeightDp = bandWidthDp / posterRatio
    return ((EDITORIAL_TITLE_ZONE_FRACTION - windowTop) * drawnHeightDp)
        .coerceIn(0f, bandHeightDp)
}

/**
 * First poster row (fraction of height) left visible after [editorialTitleGuardDp]
 * is applied. Used by the header test to prove the baked title zone stays out of
 * the band at every phone width.
 */
internal fun editorialVisibleTopFraction(
    posterRatio: Float,
    bandWidthDp: Float,
    bandHeightDp: Float,
): Float {
    if (!posterRatio.isFinite() || posterRatio <= 0f ||
        !bandWidthDp.isFinite() || bandWidthDp <= 0f ||
        !bandHeightDp.isFinite() || bandHeightDp <= 0f
    ) {
        return 0f
    }
    val visibleFraction = bandHeightDp * posterRatio / bandWidthDp
    if (visibleFraction >= 1f) return 0f
    val windowTop = 1f - visibleFraction
    val guard = editorialTitleGuardDp(posterRatio, bandWidthDp, bandHeightDp)
    return (windowTop + guard / (bandWidthDp / posterRatio)).coerceAtMost(1f)
}
