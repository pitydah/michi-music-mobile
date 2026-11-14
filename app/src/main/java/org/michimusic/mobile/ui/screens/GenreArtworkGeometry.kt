package org.michimusic.mobile.ui.screens

import androidx.compose.ui.Alignment

/**
 * Artwork geometry for the approved UI7 packs.
 *
 * Every approved asset is drawn with [androidx.compose.ui.layout.ContentScale.Fit]:
 * the packs are complete artworks whose baked frame, title and subject reach the
 * bitmap edges, so any crop would cut a protected region. The leftover area is
 * the card's own continuous surface (a deliberate matte), never a stretched,
 * zoomed, blurred or `FillBounds` bitmap.
 *
 * [genreArtworkFitRect] is pure arithmetic: it reports where Compose will paint
 * the bitmap inside a container, so overlays (the count chip) can be anchored to
 * the *artwork* border rather than to the card, and so layout tests can assert it
 * without decoding pixels.
 */
internal data class GenreArtworkRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
}

/** Displayed artwork rect (dp, relative to the container) for a Fit-drawn bitmap. */
internal fun genreArtworkFitRect(
    containerWidthDp: Float,
    containerHeightDp: Float,
    assetRatio: Float?,
): GenreArtworkRect? {
    if (!containerWidthDp.isFinite() || !containerHeightDp.isFinite() ||
        containerWidthDp <= 0f || containerHeightDp <= 0f
    ) return null
    if (assetRatio == null || !assetRatio.isFinite() || assetRatio <= 0f) return null
    val containerRatio = containerWidthDp / containerHeightDp
    return if (assetRatio >= containerRatio) {
        val height = containerWidthDp / assetRatio
        val top = (containerHeightDp - height) / 2f
        GenreArtworkRect(0f, top, containerWidthDp, top + height)
    } else {
        val width = containerHeightDp * assetRatio
        val left = (containerWidthDp - width) / 2f
        GenreArtworkRect(left, 0f, left + width, containerHeightDp)
    }
}

/**
 * Height of a full-width artwork band that keeps the whole frame and stays above
 * the reserved count-chip surface. Invalid geometry falls back to [maxHeightDp]
 * so a malformed declaration can never produce a zero-height or cropped band.
 */
internal fun genreArtworkBandHeightDp(
    widthDp: Float,
    assetRatio: Float?,
    maxHeightDp: Float,
): Float {
    if (!maxHeightDp.isFinite() || maxHeightDp <= 0f) return 0f
    if (!widthDp.isFinite() || widthDp <= 0f) return maxHeightDp
    if (assetRatio == null || !assetRatio.isFinite() || assetRatio <= 0f) return maxHeightDp
    return minOf(widthDp / assetRatio, maxHeightDp)
}

/** Fit always: no approved UI7 artwork may be cropped. */
internal const val GENRE_ARTWORK_CONTENT_SCALE_FIT = true

/**
 * Padding (dp) that places the count chip at [anchor] of the *artwork* border,
 * [GENRE_COUNTER_INSET_DP] inside it, clamped so the chip can never leave the
 * card. A null [artworkRect] means the artwork fills the card.
 */
internal data class GenreCounterPlacement(
    val startDp: Float = 0f,
    val topDp: Float = 0f,
    val endDp: Float = 0f,
    val bottomDp: Float = 0f,
)

internal fun genreCounterPlacement(
    anchor: GenreOverlayAnchor,
    containerWidthDp: Float,
    containerHeightDp: Float,
    artworkRect: GenreArtworkRect?,
    chip: GenreCounterSize,
): GenreCounterPlacement {
    if (!containerWidthDp.isFinite() || !containerHeightDp.isFinite() ||
        containerWidthDp <= 0f || containerHeightDp <= 0f
    ) return GenreCounterPlacement()
    val rect = artworkRect ?: GenreArtworkRect(0f, 0f, containerWidthDp, containerHeightDp)
    val inset = GENRE_COUNTER_INSET_DP.toFloat()
    val maxStart = (containerWidthDp - chip.widthDp - inset).coerceAtLeast(0f)
    val maxTop = (containerHeightDp - chip.heightDp - inset).coerceAtLeast(0f)
    val desiredStart = rect.left + inset
    val desiredTop = rect.top + inset
    val desiredEnd = containerWidthDp - rect.right + inset
    val desiredBottom = containerHeightDp - rect.bottom + inset
    return when (anchor) {
        GenreOverlayAnchor.TOP_START -> GenreCounterPlacement(
            startDp = desiredStart.coerceIn(0f, maxStart),
            topDp = desiredTop.coerceIn(0f, maxTop),
        )
        GenreOverlayAnchor.TOP_END -> GenreCounterPlacement(
            topDp = desiredTop.coerceIn(0f, maxTop),
            endDp = desiredEnd.coerceIn(0f, (containerWidthDp - chip.widthDp).coerceAtLeast(0f)),
        )
        GenreOverlayAnchor.BOTTOM_START -> GenreCounterPlacement(
            startDp = desiredStart.coerceIn(0f, maxStart),
            bottomDp = desiredBottom.coerceIn(0f, (containerHeightDp - chip.heightDp).coerceAtLeast(0f)),
        )
        GenreOverlayAnchor.BOTTOM_END -> GenreCounterPlacement(
            endDp = desiredEnd.coerceIn(0f, (containerWidthDp - chip.widthDp).coerceAtLeast(0f)),
            bottomDp = desiredBottom.coerceIn(0f, (containerHeightDp - chip.heightDp).coerceAtLeast(0f)),
        )
    }
}

/** Compose alignment matching the presentation anchor. */
internal fun GenreOverlayAnchor.toAlignment(): Alignment = when (this) {
    GenreOverlayAnchor.TOP_START -> Alignment.TopStart
    GenreOverlayAnchor.TOP_END -> Alignment.TopEnd
    GenreOverlayAnchor.BOTTOM_START -> Alignment.BottomStart
    GenreOverlayAnchor.BOTTOM_END -> Alignment.BottomEnd
}
