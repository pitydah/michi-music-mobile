package org.michimusic.mobile.ui.screens

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import kotlin.math.max
import kotlin.math.min

/**
 * UI7 visual contract model — one artwork, one silhouette, one frame.
 *
 * The approved WebP is the finished artwork: its baked frame and title are the
 * authority. Compose owns the click target, the real genre name/count and the
 * numeric chip; it must not add a second border, matte or title around a
 * [Ui7ArtworkKind.LEGACY_COMPLETE] resource.
 *
 * Two independent axes are modelled here and **must not be mixed**:
 *
 * - [Ui7AssetAvailability] answers "is there a packaged, decodable artwork for
 *   this real genre?". Only a real integrity/decode failure may withhold paint.
 * - [Ui7VisualQualityFlags] records artistic debt (unverified contour, aspect
 *   band, unverified chip region, missing vertical variant). Debt is reported and
 *   never replaces a legitimate WebP with a generic card.
 */
internal enum class Ui7ArtworkKind { LEGACY_COMPLETE, COMPOSE_READY, MISSING }

/**
 * Asset/file availability for one genre card.
 *
 * [LOADING_CATALOG] is a transient reading state: it must never publish an empty
 * catalog as final. [IMAGE_DECODE_SUCCESS] / [IMAGE_DECODE_FAILURE] are runtime
 * observations reported by Coil; only the failure of *that* resource falls back,
 * it never invalidates a sibling.
 */
internal enum class Ui7AssetAvailability {
    LOADING_CATALOG,
    CATALOG_REJECTED,
    NO_ASSET_FOR_GENRE,
    ASSET_PACKAGED,
    IMAGE_DECODE_SUCCESS,
    IMAGE_DECODE_FAILURE,
    ;

    /** The artwork may be painted (integrity is proven; quality is not required). */
    val paintsArtwork: Boolean
        get() = this == ASSET_PACKAGED || this == IMAGE_DECODE_SUCCESS
}

/**
 * Artistic QA flags. Recorded per card, aggregated only in DEBUG diagnostics and
 * never used to veto an otherwise decodable resource.
 *
 * @property aspectDebtPerSideDp visible residual band when `ContentScale.Fit` keeps
 * the real proportions inside a fixed cell; above [GENRE_ASPECT_DEBT_THRESHOLD_DP]
 * it blocks *artistic approval*, not rendering.
 */
internal data class Ui7VisualQualityFlags(
    val opticalMaskUnverified: Boolean = false,
    val aspectDebtPerSideDp: Float = 0f,
    val counterRegionUnverified: Boolean = false,
    val needsPortraitVariant: Boolean = false,
) {
    val aspectDebt: Boolean get() = aspectDebtPerSideDp >= GENRE_ASPECT_DEBT_THRESHOLD_DP

    val hasDebt: Boolean
        get() = opticalMaskUnverified || aspectDebt || counterRegionUnverified || needsPortraitVariant
}

/** Rectangle in the 0..1 coordinate space of the displayed artwork. */
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

    /** Safety margin expressed as a normalized inset, used by counter collision checks. */
    fun insetBy(leftFraction: Float, topFraction: Float, rightFraction: Float,
                bottomFraction: Float): NormalizedRect? {
        val l = left + leftFraction
        val t = top + topFraction
        val r = right - rightFraction
        val b = bottom - bottomFraction
        if (l > r || t > b) return null
        return NormalizedRect(l, t, r, b)
    }

    companion object {
        val FULL = NormalizedRect(0f, 0f, 1f, 1f)
    }
}

/**
 * Offline-measured optical frame. The values are source-image pixels, produced by
 * the read-only analyser documented in the technical report. The renderer uses a
 * quadratic-corner outline (the source frame is a rounded square, not a circular
 * arc) so the baked stroke is preserved instead of being cut by an arbitrary
 * `RoundedCornerShape` radius.
 *
 * [verified] false means the offline inspection could not establish four
 * plausible radii/insets. The asset is still painted, clipped by
 * [provisionalUi7OpticalMask], and the card records `mask_pending`.
 */
internal data class Ui7OpticalMask(
    val sourceWidthPx: Int,
    val sourceHeightPx: Int,
    val leftInsetPx: Float,
    val topInsetPx: Float,
    val rightInsetPx: Float,
    val bottomInsetPx: Float,
    val topLeftRadiusPx: Float,
    val topRightRadiusPx: Float,
    val bottomRightRadiusPx: Float,
    val bottomLeftRadiusPx: Float,
    val verified: Boolean,
)

/**
 * Conservative shape for a resource whose optical frame is not certified yet:
 * a 2px inset and a 5% corner radius of the short side. It clips only the
 * bitmap's own outer margin, never paints a second line, and is replaced by the
 * measured contour as soon as `verified=1` exists.
 */
internal fun provisionalUi7OpticalMask(widthPx: Int, heightPx: Int): Ui7OpticalMask {
    val radius = min(widthPx, heightPx) * 0.05f
    return Ui7OpticalMask(
        sourceWidthPx = widthPx,
        sourceHeightPx = heightPx,
        leftInsetPx = 2f,
        topInsetPx = 2f,
        rightInsetPx = 2f,
        bottomInsetPx = 2f,
        topLeftRadiusPx = radius,
        topRightRadiusPx = radius,
        bottomRightRadiusPx = radius,
        bottomLeftRadiusPx = radius,
        verified = false,
    )
}

/**
 * The single presentation record for one approved asset. It never creates a
 * second source of truth: dimensions and hashes still come from the manifest,
 * while framing/regions come from the static optical metadata.
 */
internal data class Ui7VisualAssetSpec(
    val genreKey: String,
    val fileName: String,
    val widthPx: Int,
    val heightPx: Int,
    val kind: Ui7ArtworkKind,
    val embeddedTitleVerified: Boolean,
    val frameIncluded: Boolean,
    val opticalMask: Ui7OpticalMask?,
    val protectedRects: List<NormalizedRect>,
    val counterSafeRect: NormalizedRect?,
) {
    val ratio: Float get() = widthPx.toFloat() / heightPx.toFloat()

    val hasVerifiedContour: Boolean
        get() = frameIncluded && opticalMask?.verified == true

    /**
     * Mask used to clip the *painted art rect*. A verified measurement wins; an
     * unverified resource keeps painting with the provisional shape.
     */
    val clipMask: Ui7OpticalMask
        get() = opticalMask?.takeIf { hasVerifiedContour }
            ?: provisionalUi7OpticalMask(widthPx, heightPx)
}

/** Bottom-end chip region documented for the Mosaico crop family. */
internal val ui7MosaicCounterSafeRegion = NormalizedRect(0.55f, 0.35f, 0.985f, 0.985f)

/** Bottom-end chip region for a finished Editorial poster. */
internal val ui7EditorialCounterSafeRegion = NormalizedRect(0.55f, 0.35f, 0.985f, 0.985f)

/** The visible baked title of a Mosaico crop sits bottom-start. */
private val mosaicEmbeddedTitleRect = NormalizedRect(0f, 0.72f, 0.58f, 0.99f)

/**
 * Mosaico crop spec. Availability never depends on the optical row: an
 * unverified mask is a QA flag, and the numeric chip always has a documented
 * provisional region.
 */
internal fun mosaicVisualSpec(asset: MosaicArtworkAsset?): Ui7VisualAssetSpec? {
    if (asset == null) return null
    return Ui7VisualAssetSpec(
        genreKey = asset.genreKey,
        fileName = asset.filename,
        widthPx = asset.widthPx,
        heightPx = asset.heightPx,
        kind = Ui7ArtworkKind.LEGACY_COMPLETE,
        embeddedTitleVerified = true,
        frameIncluded = true,
        opticalMask = asset.opticalMask,
        protectedRects = listOf(mosaicEmbeddedTitleRect),
        counterSafeRect = asset.counterSafeRect ?: ui7MosaicCounterSafeRegion,
    )
}

/**
 * Editorial poster spec. The approved pack contains only horizontal finished
 * posters (1.4..4.5:1), so a poster is never asked to become a portrait: the
 * planner derives a banner or a proportional compact/pair from its real ratio.
 */
internal fun editorialVisualSpec(poster: EditorialPosterAsset?): Ui7VisualAssetSpec? {
    if (poster == null) return null
    return Ui7VisualAssetSpec(
        genreKey = poster.slug,
        fileName = poster.filename,
        widthPx = poster.widthPx,
        heightPx = poster.heightPx,
        kind = Ui7ArtworkKind.LEGACY_COMPLETE,
        embeddedTitleVerified = true,
        frameIncluded = true,
        opticalMask = poster.opticalMask,
        protectedRects = emptyList(),
        counterSafeRect = poster.counterSafeRect ?: ui7EditorialCounterSafeRegion,
    )
}

/** The decision for one card: availability plus recorded debt. */
internal data class Ui7ArtworkDecision(
    val availability: Ui7AssetAvailability,
    val spec: Ui7VisualAssetSpec?,
    val quality: Ui7VisualQualityFlags,
) {
    val paintsArtwork: Boolean get() = availability.paintsArtwork && spec != null
}

/**
 * Single decision point of both views.
 *
 * There is deliberately no veto here beyond a missing packaged asset: a ratio
 * mismatch is reported as [Ui7VisualQualityFlags.aspectDebtPerSideDp], an
 * unmeasured contour as [Ui7VisualQualityFlags.opticalMaskUnverified] and an
 * unverified chip region as [Ui7VisualQualityFlags.counterRegionUnverified].
 * The renderer paints the WebP in every one of those cases.
 */
internal fun ui7ArtworkDecision(
    spec: Ui7VisualAssetSpec?,
    cellWidthDp: Float,
    cellHeightDp: Float,
    chipWidthDp: Float = 0f,
    chipHeightDp: Float = 0f,
    needsPortraitVariant: Boolean = false,
): Ui7ArtworkDecision {
    if (spec == null) {
        return Ui7ArtworkDecision(
            availability = Ui7AssetAvailability.NO_ASSET_FOR_GENRE,
            spec = null,
            quality = Ui7VisualQualityFlags(needsPortraitVariant = needsPortraitVariant),
        )
    }
    val art = genreArtworkFitRect(cellWidthDp, cellHeightDp, spec.ratio)
    val debt = art?.let { aspectDebtPerSideDp(cellWidthDp, cellHeightDp, it) } ?: 0f
    val counterUnverified = art != null && chipWidthDp > 0f && chipHeightDp > 0f &&
        !counterPlacementIsSafe(spec, art.width, art.height, chipWidthDp, chipHeightDp)
    return Ui7ArtworkDecision(
        availability = Ui7AssetAvailability.ASSET_PACKAGED,
        spec = spec,
        quality = Ui7VisualQualityFlags(
            opticalMaskUnverified = !spec.hasVerifiedContour,
            aspectDebtPerSideDp = debt,
            counterRegionUnverified = counterUnverified,
            needsPortraitVariant = needsPortraitVariant,
        ),
    )
}

/**
 * One-shot catalog reading state.
 *
 * [Loading] is transient and must never be published as an empty/final catalog:
 * the Editorial wall materialises only once [Ready] arrives, and Mosaico renders
 * neutral skeleton cells with the real genre count meanwhile.
 */
internal sealed interface Ui7Catalog<out T> {
    data object Loading : Ui7Catalog<Nothing>
    data class Ready<T>(val entries: Map<String, T>) : Ui7Catalog<T>
    data class Rejected(val reason: String) : Ui7Catalog<Nothing>

    fun entriesOrEmpty(): Map<String, T> = when (this) {
        is Ready -> entries
        else -> emptyMap()
    }
}

/** The rect and clip for one paint, derived from the real measured cell. */
internal data class Ui7ArtworkFrame(
    val rect: GenreArtworkRect,
    val shape: Shape,
) {
    val widthDp: Float get() = rect.width
    val heightDp: Float get() = rect.height
}

/**
 * Fit the source inside [cellWidthDp] x [cellHeightDp] without distortion and
 * attach the clip to the painted rect (never to the outer cell, so a residual
 * band can never look like a second, misaligned frame).
 */
internal fun ui7ArtworkFrame(
    spec: Ui7VisualAssetSpec,
    cellWidthDp: Float,
    cellHeightDp: Float,
): Ui7ArtworkFrame? {
    val rect = genreArtworkFitRect(cellWidthDp, cellHeightDp, spec.ratio) ?: return null
    return Ui7ArtworkFrame(
        rect = rect,
        shape = Ui7OpticalFrameShape(spec.widthPx, spec.heightPx, spec.clipMask),
    )
}

/**
 * Pure contour arithmetic for one painted rect: source-pixel insets/radii mapped
 * with **one** uniform scale to `size`. Keeping it pure lets the JVM tests verify
 * that X and Y never receive different scales (the historical double/misaligned
 * frame defect) without Android graphics stubs.
 */
internal data class Ui7OpticalContour(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val topLeftRadius: Float,
    val topRightRadius: Float,
    val bottomRightRadius: Float,
    val bottomLeftRadius: Float,
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
}

internal fun ui7OpticalContour(
    size: Size,
    sourceWidthPx: Int,
    sourceHeightPx: Int,
    mask: Ui7OpticalMask,
): Ui7OpticalContour? {
    val width = size.width
    val height = size.height
    if (width <= 0f || height <= 0f || sourceWidthPx <= 0 || sourceHeightPx <= 0) return null
    val scale = min(width / sourceWidthPx.toFloat(), height / sourceHeightPx.toFloat())
    val left = (mask.leftInsetPx * scale).coerceIn(0f, width / 2f)
    val top = (mask.topInsetPx * scale).coerceIn(0f, height / 2f)
    val right = (width - mask.rightInsetPx * scale).coerceIn(width / 2f, width)
    val bottom = (height - mask.bottomInsetPx * scale).coerceIn(height / 2f, height)
    val limit = min(max(0f, (right - left) / 2f), max(0f, (bottom - top) / 2f))
    fun radius(px: Float): Float = px.coerceIn(0f, max(2f, limit))
    return Ui7OpticalContour(
        left = left,
        top = top,
        right = right,
        bottom = bottom,
        topLeftRadius = radius(mask.topLeftRadiusPx * scale),
        topRightRadius = radius(mask.topRightRadiusPx * scale),
        bottomRightRadius = radius(mask.bottomRightRadiusPx * scale),
        bottomLeftRadius = radius(mask.bottomLeftRadiusPx * scale),
    )
}

/**
 * Quadratic-corner outline. The source packs are a rounded square: a circular
 * `RoundedCornerShape` either cuts the baked stroke at the four corners or leaves
 * opaque wedges outside it. The quadratic control point at the actual corner
 * reproduces the measured intercepts.
 *
 * One uniform scale is applied to X and Y (the measured mask lives in source
 * pixels), so the contour stays aligned with the bitmap when the cell ratio
 * differs from the source ratio.
 */
internal class Ui7OpticalFrameShape(
    private val sourceWidthPx: Int,
    private val sourceHeightPx: Int,
    private val mask: Ui7OpticalMask,
) : Shape {
    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density,
    ): Outline {
        val contour = ui7OpticalContour(size, sourceWidthPx, sourceHeightPx, mask)
            ?: return Outline.Rectangle(
                androidx.compose.ui.geometry.Rect(0f, 0f, size.width, size.height),
            )
        val path = Path().apply {
            moveTo(contour.left + contour.topLeftRadius, contour.top)
            lineTo(contour.right - contour.topRightRadius, contour.top)
            quadraticTo(contour.right, contour.top,
                contour.right, contour.top + contour.topRightRadius)
            lineTo(contour.right, contour.bottom - contour.bottomRightRadius)
            quadraticTo(contour.right, contour.bottom,
                contour.right - contour.bottomRightRadius, contour.bottom)
            lineTo(contour.left + contour.bottomLeftRadius, contour.bottom)
            quadraticTo(contour.left, contour.bottom,
                contour.left, contour.bottom - contour.bottomLeftRadius)
            lineTo(contour.left, contour.top + contour.topLeftRadius)
            quadraticTo(contour.left, contour.top, contour.left + contour.topLeftRadius, contour.top)
            close()
        }
        return Outline.Generic(path)
    }
}
