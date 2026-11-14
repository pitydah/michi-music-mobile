package org.michimusic.mobile.ui.screens

import androidx.compose.ui.Alignment
import kotlin.math.max
import kotlin.math.min

/**
 * Artwork geometry for the UI7 contract.
 *
 * The WebP is always fitted with one uniform scale: no `FillBounds`, no stretch
 * and no destructive crop. This file reports where the bitmap actually lands so
 * the renderer can clip the *painted* rect, anchor the numeric chip to the real
 * artwork border and record the residual band as `aspect_debt_dp` — never as a
 * reason to hide the image.
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

/** Translate a fit rect into the coordinate space of its parent container. */
internal fun GenreArtworkRect.offsetBy(dxDp: Float, dyDp: Float): GenreArtworkRect =
    GenreArtworkRect(left + dxDp, top + dyDp, right + dxDp, bottom + dyDp)

/** Fit always: no approved UI7 artwork may be cropped or stretched. */
internal const val GENRE_ARTWORK_CONTENT_SCALE_FIT = true

/**
 * Residual band (dp per side) left by an intact Fit inside a fixed cell. Above
 * [GENRE_ASPECT_DEBT_THRESHOLD_DP] the resource is recorded as `aspect_debt_dp`
 * and blocked for artistic approval; it is still painted.
 */
internal const val GENRE_ASPECT_DEBT_THRESHOLD_DP = 2f

internal fun aspectDebtPerSideDp(
    cellWidthDp: Float,
    cellHeightDp: Float,
    artRect: GenreArtworkRect,
): Float = max(
    (cellWidthDp - artRect.width) / 2f,
    (cellHeightDp - artRect.height) / 2f,
).coerceAtLeast(0f)

/** Convenience overload used by the pure decision and by tests. */
internal fun aspectDebtPerSideDp(
    cellWidthDp: Float,
    cellHeightDp: Float,
    assetRatio: Float?,
): Float {
    val rect = genreArtworkFitRect(cellWidthDp, cellHeightDp, assetRatio) ?: return 0f
    return aspectDebtPerSideDp(cellWidthDp, cellHeightDp, rect)
}

/**
 * Padding (dp) that places the count chip at the bottom-end of the *artwork*
 * border, 12dp inside it, clamped so the chip can never leave the card. A null
 * [artworkRect] means the artwork fills the card.
 */
internal data class GenreCounterPlacement(
    val startDp: Float = 0f,
    val topDp: Float = 0f,
    val endDp: Float = 0f,
    val bottomDp: Float = 0f,
)

internal fun genreCounterPlacement(
    containerWidthDp: Float,
    containerHeightDp: Float,
    artworkRect: GenreArtworkRect?,
    chip: GenreCounterSize,
    anchor: GenreOverlayAnchor = GenreOverlayAnchor.BOTTOM_END,
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
            bottomDp = desiredBottom.coerceIn(0f,
                (containerHeightDp - chip.heightDp).coerceAtLeast(0f)),
        )
        GenreOverlayAnchor.BOTTOM_END -> GenreCounterPlacement(
            endDp = desiredEnd.coerceIn(0f, (containerWidthDp - chip.widthDp).coerceAtLeast(0f)),
            bottomDp = desiredBottom.coerceIn(0f,
                (containerHeightDp - chip.heightDp).coerceAtLeast(0f)),
        )
    }
}

internal enum class GenreOverlayAnchor { TOP_START, TOP_END, BOTTOM_START, BOTTOM_END }

/** Compose alignment matching the chip anchor. */
internal fun GenreOverlayAnchor.toAlignment(): Alignment = when (this) {
    GenreOverlayAnchor.TOP_START -> Alignment.TopStart
    GenreOverlayAnchor.TOP_END -> Alignment.TopEnd
    GenreOverlayAnchor.BOTTOM_START -> Alignment.BottomStart
    GenreOverlayAnchor.BOTTOM_END -> Alignment.BottomEnd
}

/**
 * Normalized chip box for the bottom-end anchor, used by pure collision tests.
 * Returns null when the chip cannot fit inside the artwork.
 */
internal fun normalizedBottomEndChipBox(
    artworkWidthDp: Float,
    artworkHeightDp: Float,
    chipWidthDp: Float,
    chipHeightDp: Float,
): NormalizedRect? {
    if (!artworkWidthDp.isFinite() || !artworkHeightDp.isFinite() ||
        artworkWidthDp <= 0f || artworkHeightDp <= 0f
    ) return null
    val inset = GENRE_COUNTER_INSET_DP.toFloat()
    val right = (artworkWidthDp - inset) / artworkWidthDp
    val left = (artworkWidthDp - inset - chipWidthDp) / artworkWidthDp
    val top = (artworkHeightDp - inset - chipHeightDp) / artworkHeightDp
    val bottom = (artworkHeightDp - inset) / artworkHeightDp
    if (left < 0f || top < 0f || right > 1f || bottom > 1f) return null
    return NormalizedRect(left, top, right, bottom)
}

/**
 * True when the chip fits the asset's documented bottom-end region without
 * touching a protected art region, with a 4dp safety margin.
 *
 * This is a *quality* answer only: `false` records `counter_pending` and the
 * bitmap keeps painting. The chip region documented for the 312-resource family
 * is a family-wide approximate region, not a per-subject measurement, so it can
 * never be used as an image veto.
 */
internal fun counterPlacementIsSafe(
    spec: Ui7VisualAssetSpec,
    artworkWidthDp: Float,
    artworkHeightDp: Float,
    chipWidthDp: Float,
    chipHeightDp: Float,
): Boolean {
    val safe = spec.counterSafeRect ?: return false
    val box = normalizedBottomEndChipBox(
        artworkWidthDp, artworkHeightDp, chipWidthDp, chipHeightDp,
    ) ?: return false
    if (!safe.contains(box)) return false
    val marginX = 4f / artworkWidthDp
    val marginY = 4f / artworkHeightDp
    val padded = NormalizedRect(
        (box.left - marginX).coerceIn(0f, 1f),
        (box.top - marginY).coerceIn(0f, 1f),
        (box.right + marginX).coerceIn(0f, 1f),
        (box.bottom + marginY).coerceIn(0f, 1f),
    )
    return spec.protectedRects.none { protected -> protected.intersects(padded) }
}

/**
 * True when a chip of [chipWidthDp] x [chipHeightDp] still fits inside the
 * painted rect with the documented 10..12dp inset. Used to hide only the
 * overlay (never the bitmap) on an extremely thin banner.
 */
internal fun counterFitsInsideArtwork(
    artworkWidthDp: Float,
    artworkHeightDp: Float,
    chipWidthDp: Float,
    chipHeightDp: Float,
): Boolean {
    if (artworkWidthDp <= 0f || artworkHeightDp <= 0f) return false
    val inset = min(GENRE_COUNTER_INSET_DP.toFloat(), 10f)
    return artworkWidthDp - 2 * inset >= chipWidthDp &&
        artworkHeightDp - 2 * inset >= chipHeightDp
}
