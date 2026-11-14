package org.michimusic.mobile.ui.screens

import kotlin.math.max
import kotlin.math.min

/**
 * Artwork framing policy for the approved UI7 art packs.
 *
 * The renderer never stretches, never uses FillBounds and never applies an
 * arbitrary zoom or a cosmetic blur. Given the artwork's declared proportions
 * and the real container proportions it picks one of two documented modes:
 *
 * - [GenreArtworkFraming.MODERATE_CROP]: the two ratios are close
 *   (<= [GENRE_ARTWORK_CROP_TOLERANCE]). A centered [ContentScale.Crop] removes
 *   at most ~18% of one axis, which keeps the subject (cat face, ears, paws,
 *   instrument) and the baked title inside the frame while removing side bands.
 * - [GenreArtworkFraming.CONTAIN_WITH_MATTE]: the ratios are incompatible. The
 *   whole figure is kept with [ContentScale.Fit] and the card's own designed
 *   surface (the Compose plane + a hairline frame) extends the background, so
 *   the remaining area reads as an intentional matte instead of an empty or
 *   black band.
 *
 * Invalid/unknown geometry falls back to [GenreArtworkFraming.CONTAIN_WITH_MATTE]
 * so a malformed manifest can never crop or distort an approved asset.
 */
internal enum class GenreArtworkFraming { MODERATE_CROP, CONTAIN_WITH_MATTE }

/**
 * Canonical frame of the Mosaico pack: the approved WebPs are 1.32..1.56
 * (506x352..507x325). 1.41 keeps the whole figure at a truthful size and leaves
 * room for the localized caption instead of stretching the card to the asset.
 */
internal const val GENRE_ARTWORK_FRAME_RATIO = 1.41f

/** Maximum tolerated ratio delta before a crop is considered destructive. */
internal const val GENRE_ARTWORK_CROP_TOLERANCE = 1.22f

internal fun genreArtworkContainerRatio(widthDp: Float, heightDp: Float): Float? =
    if (widthDp.isFinite() && heightDp.isFinite() && widthDp > 0f && heightDp > 0f) {
        widthDp / heightDp
    } else null

internal fun genreArtworkFraming(
    assetRatio: Float?,
    containerRatio: Float?,
): GenreArtworkFraming {
    if (assetRatio == null || containerRatio == null) return GenreArtworkFraming.CONTAIN_WITH_MATTE
    if (!assetRatio.isFinite() || !containerRatio.isFinite()) {
        return GenreArtworkFraming.CONTAIN_WITH_MATTE
    }
    if (assetRatio <= 0f || containerRatio <= 0f) return GenreArtworkFraming.CONTAIN_WITH_MATTE
    val delta = max(assetRatio, containerRatio) / min(assetRatio, containerRatio)
    return if (delta <= GENRE_ARTWORK_CROP_TOLERANCE) GenreArtworkFraming.MODERATE_CROP
    else GenreArtworkFraming.CONTAIN_WITH_MATTE
}
