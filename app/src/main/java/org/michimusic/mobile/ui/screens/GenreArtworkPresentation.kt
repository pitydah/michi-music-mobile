package org.michimusic.mobile.ui.screens

/**
 * Shared presentation contract for the two approved UI7 genre views.
 *
 * Both views render the same approved inventory (312 crops + 312 complete
 * posters), but they are two different visual identities and must not be reduced
 * to a single treatment:
 *
 * - [GenreArtworkPresentation.LEGACY_COMPLETE]: the approved WebP is a *finished*
 *   artwork. Its baked title, frame and composition are the authority, so Compose
 *   draws neither a second title nor a border, and the bitmap is never cropped.
 * - [GenreArtworkPresentation.COMPOSE_READY]: a verified text-free/frame-free
 *   derivative (the offline Editorial production pack). Compose owns the title and
 *   the border because the asset provably does not contain them.
 * - [GenreArtworkPresentation.FALLBACK]: no verified artwork, or unverified
 *   metadata. The card keeps its accessible name/count and draws a live Compose
 *   title; it never infers a title from a filename or a pixel size.
 *
 * [GenreTitlePresentation.EMBEDDED_TITLE] is only selected from verified catalog
 * metadata (the manifest's `USER_CONFIRMED_COMPLETE` / `POSTER_COMPLETE` approval
 * recorded by the user), never from a filename or a width/height guess.
 *
 * Geometry here is intentionally *not* derived from an asset's native ratio:
 * artwork ratios range 1.15..4.5:1, so ratio-driven cells produced 88.6dp next to
 * 46.3dp cards in the same Editorial pair. Heights come from the treatment; the
 * artwork keeps its whole frame inside that treatment.
 */
internal enum class GenreArtworkPresentation { LEGACY_COMPLETE, COMPOSE_READY, FALLBACK }

internal enum class GenreTitlePresentation { EMBEDDED_TITLE, COMPOSE_TITLE }

internal enum class GenreOverlayAnchor { TOP_START, TOP_END, BOTTOM_START, BOTTOM_END }

/** Rectangle in the 0..1 coordinate space of the *displayed* artwork. */
internal data class NormalizedRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    init {
        require(left.isFinite() && top.isFinite() && right.isFinite() && bottom.isFinite()) {
            "normalized bounds must be finite"
        }
        require(left in 0f..1f && top in 0f..1f && right in 0f..1f && bottom in 0f..1f) {
            "normalized bounds must stay inside 0..1"
        }
        require(left <= right && top <= bottom) { "normalized bounds are inverted" }
    }

    val width: Float get() = right - left
    val height: Float get() = bottom - top

    fun contains(other: NormalizedRect): Boolean =
        other.left >= left && other.top >= top && other.right <= right && other.bottom <= bottom

    fun intersects(other: NormalizedRect): Boolean =
        left < other.right && other.left < right && top < other.bottom && other.top < bottom

    companion object {
        val FULL = NormalizedRect(0f, 0f, 1f, 1f)
    }
}

/**
 * Per-card presentation decision. [opticalFrame] is the part of the displayed
 * artwork that a clip/mount must preserve; [safeCounterRegion] is the verified
 * area where the count chip may sit without covering the face, instrument,
 * baked title or frame.
 */
internal data class GenreArtworkPresentationSpec(
    val presentation: GenreArtworkPresentation,
    val title: GenreTitlePresentation,
    val counterAnchor: GenreOverlayAnchor,
    val opticalFrame: NormalizedRect = NormalizedRect.FULL,
    val safeCounterRegion: NormalizedRect = NormalizedRect.FULL,
    /** The portrait slot has no approved vertical artwork yet. */
    val pendingVerticalAsset: Boolean = false,
)

/**
 * Counter chip tokens. The chip is metadata (one line), 8dp/4dp padding, 7dp
 * radius, translucent obsidian and 12dp from the optical border; it is never a
 * detached footer band with its own background or separator.
 */
internal const val GENRE_COUNTER_INSET_DP = 12
internal const val GENRE_COUNTER_RADIUS_DP = 7
internal const val GENRE_COUNTER_HORIZONTAL_PADDING_DP = 8
internal const val GENRE_COUNTER_VERTICAL_PADDING_DP = 4
/** Minimum breathing room between the count chip and the artwork/title. */
internal const val GENRE_COUNTER_BREATHING_DP = 8

/** `MichiTypography.metadata` lineHeight is 16sp; two extra dp absorb font metrics. */
internal fun genreCounterHeightDp(fontScale: Float): Float =
    16f * fontScale.coerceIn(1f, 3f) + 2f * GENRE_COUNTER_VERTICAL_PADDING_DP + 2f

/** Surface kept free under an artwork band for the chip, its inset and its gap. */
internal fun genreCounterReserveDp(fontScale: Float): Float =
    GENRE_COUNTER_INSET_DP + genreCounterHeightDp(fontScale) + GENRE_COUNTER_BREATHING_DP

/**
 * Verified Mosaico counter region for the approved crop family.
 *
 * The 312 approved crops place their baked genre title bottom-left and their
 * subject (face, ears, instrument) across the centre. The top-right band is
 * background in every inspected card, including the calibration set
 * (Rock/Pop/New Wave/Soft Rock). The region is the documented family policy;
 * per-asset OCR/pixel probing at runtime is forbidden, so an asset that is not
 * covered by this policy keeps the conservative fallback placement instead.
 */
private val mosaicFamilySafeCounterRegion = NormalizedRect(0.40f, 0.04f, 0.97f, 0.34f)

/**
 * Measured optical inset of the approved Editorial poster family.
 *
 * Offline inspection of the calibration sheets (New Wave 1030x254, Pop 514x268,
 * Rock 1027x280, Soft Rock 518x305) found the baked frame line 2..10px inside the
 * bitmap edge, i.e. 0.4%..2.0% of the width. 1.2% is the family value used to
 * keep any mount/clip outside the approved frame; the renderer also mounts the
 * poster 8dp inside the card, which removes the corner region entirely.
 */
private val editorialFamilyOpticalFrame = NormalizedRect(0.012f, 0.012f, 0.988f, 0.988f)

/**
 * Mosaico: an approved crop is a complete artwork (baked title + frame), so the
 * card never draws a second title. A missing asset is a conservative fallback
 * with a live Compose title. The count sits top-right inside the artwork.
 */
internal fun mosaicPresentationSpec(asset: MosaicArtworkAsset?): GenreArtworkPresentationSpec =
    if (asset == null) {
        GenreArtworkPresentationSpec(
            presentation = GenreArtworkPresentation.FALLBACK,
            title = GenreTitlePresentation.COMPOSE_TITLE,
            counterAnchor = GenreOverlayAnchor.BOTTOM_START,
        )
    } else {
        GenreArtworkPresentationSpec(
            presentation = GenreArtworkPresentation.LEGACY_COMPLETE,
            title = GenreTitlePresentation.EMBEDDED_TITLE,
            counterAnchor = GenreOverlayAnchor.TOP_END,
            safeCounterRegion = mosaicFamilySafeCounterRegion,
        )
    }

/**
 * Editorial: an approved poster is a complete poster, so Compose draws no title
 * and no border. Paired portrait slots still hold a landscape sheet, because the
 * approved pack contains no vertical artwork; the card is marked
 * `PENDIENTE DE RECURSO VERTICAL APROBADO` instead of faking a vertical crop.
 */
internal fun editorialPresentationSpec(
    poster: EditorialPosterAsset?,
    treatment: GenreEditorialTreatment,
): GenreArtworkPresentationSpec =
    if (poster == null) {
        GenreArtworkPresentationSpec(
            presentation = GenreArtworkPresentation.FALLBACK,
            title = GenreTitlePresentation.COMPOSE_TITLE,
            counterAnchor = GenreOverlayAnchor.BOTTOM_START,
        )
    } else {
        GenreArtworkPresentationSpec(
            presentation = GenreArtworkPresentation.LEGACY_COMPLETE,
            title = GenreTitlePresentation.EMBEDDED_TITLE,
            counterAnchor = if (treatment == GenreEditorialTreatment.PORTRAIT_RIGHT) {
                GenreOverlayAnchor.BOTTOM_END
            } else {
                GenreOverlayAnchor.BOTTOM_START
            },
            opticalFrame = editorialFamilyOpticalFrame,
            pendingVerticalAsset = treatment != GenreEditorialTreatment.COVER &&
                !poster.isVertical,
        )
    }

/**
 * True when a [chipWidthDp] x [chipHeightDp] chip placed at the spec's anchor
 * stays inside the documented safe region. Callers use this to decide between
 * the in-artwork placement and the conservative continuous-surface placement.
 */
internal fun counterFitsSafeRegion(
    spec: GenreArtworkPresentationSpec,
    artworkWidthDp: Float,
    artworkHeightDp: Float,
    chipWidthDp: Float,
    chipHeightDp: Float,
): Boolean {
    if (!artworkWidthDp.isFinite() || !artworkHeightDp.isFinite() ||
        artworkWidthDp <= 0f || artworkHeightDp <= 0f ||
        !chipWidthDp.isFinite() || !chipHeightDp.isFinite()
    ) return false
    val insetDp = GENRE_COUNTER_INSET_DP.toFloat()
    val right = (artworkWidthDp - insetDp) / artworkWidthDp
    val left = (artworkWidthDp - insetDp - chipWidthDp) / artworkWidthDp
    val insetY = GENRE_COUNTER_BREATHING_DP.toFloat()
    val top = (insetY + GENRE_COUNTER_INSET_DP) / artworkHeightDp
    val bottom = (insetY + GENRE_COUNTER_INSET_DP + chipHeightDp) / artworkHeightDp
    if (left < 0f || top < 0f || right > 1f || bottom > 1f) return false
    val chip = NormalizedRect(left, top, right, bottom)
    return spec.safeCounterRegion.contains(chip)
}
