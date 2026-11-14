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
 * All regions are normalized to the source image. The renderer converts them to
 * the displayed artwork rect from the real layout size, never from a screen-wide
 * assumption.
 */
internal enum class Ui7ArtworkKind { LEGACY_COMPLETE, COMPOSE_READY, MISSING }

internal enum class Ui7Treatment {
    MOSAIC_GRID,
    EDITORIAL_COVER,
    EDITORIAL_PORTRAIT_LEFT,
    EDITORIAL_PORTRAIT_RIGHT,
    SINGLE_COLUMN,
}

internal enum class Ui7AssetApproval { APPROVED, PROVISIONAL, NOT_APPROVED }

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
 * [verified] is false when the offline inspection could not establish four
 * plausible radii/insets; the caller must fall back rather than guess.
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
    val approvedTreatments: Set<Ui7Treatment>,
    val embeddedTitleVerified: Boolean,
    val frameIncluded: Boolean,
    val opticalMask: Ui7OpticalMask?,
    val protectedRects: List<NormalizedRect>,
    val counterSafeRect: NormalizedRect?,
    val approval: Ui7AssetApproval,
) {
    val hasVerifiedContour: Boolean
        get() = frameIncluded && opticalMask?.verified == true
}

/** Bottom-end chip region documented for the Mosaico crop family. */
internal val ui7MosaicCounterSafeRegion = NormalizedRect(0.55f, 0.35f, 0.985f, 0.985f)

/** Bottom-end chip region for a finished Editorial poster. */
internal val ui7EditorialCounterSafeRegion = NormalizedRect(0.55f, 0.35f, 0.985f, 0.985f)

/** The visible baked title of a Mosaico crop sits bottom-start. */
private val mosaicEmbeddedTitleRect = NormalizedRect(0f, 0.72f, 0.58f, 0.99f)

/** Mosaico crop approved for the homogeneous grid. */
internal fun mosaicVisualSpec(
    asset: MosaicArtworkAsset?,
    treatment: Ui7Treatment,
): Ui7VisualAssetSpec? {
    if (asset == null) return null
    val mask = asset.opticalMask
    val verified = mask?.verified == true
    val treatments = if (verified) {
        when (treatment) {
            Ui7Treatment.MOSAIC_GRID, Ui7Treatment.SINGLE_COLUMN ->
                setOf(Ui7Treatment.MOSAIC_GRID, Ui7Treatment.SINGLE_COLUMN)
            else -> emptySet()
        }
    } else {
        emptySet()
    }
    return Ui7VisualAssetSpec(
        genreKey = asset.genreKey,
        fileName = asset.filename,
        widthPx = asset.widthPx,
        heightPx = asset.heightPx,
        kind = Ui7ArtworkKind.LEGACY_COMPLETE,
        approvedTreatments = treatments,
        embeddedTitleVerified = true,
        frameIncluded = true,
        opticalMask = mask,
        protectedRects = listOf(mosaicEmbeddedTitleRect),
        counterSafeRect = if (verified) {
            asset.counterSafeRect ?: ui7MosaicCounterSafeRegion
        } else {
            null
        },
        approval = if (verified) Ui7AssetApproval.APPROVED else Ui7AssetApproval.PROVISIONAL,
    )
}

/** Editorial poster approved for its target treatment, when compatible. */
internal fun editorialVisualSpec(
    poster: EditorialPosterAsset?,
    treatment: GenreEditorialTreatment,
): Ui7VisualAssetSpec? {
    if (poster == null) return null
    val mask = poster.opticalMask
    val verified = mask?.verified == true
    val ui7Treatment = treatment.toUi7Treatment()
    val portrait = ui7Treatment == Ui7Treatment.EDITORIAL_PORTRAIT_LEFT ||
        ui7Treatment == Ui7Treatment.EDITORIAL_PORTRAIT_RIGHT
    val approved = if (!verified || (portrait && !poster.isVertical)) {
        emptySet()
    } else {
        setOf(ui7Treatment)
    }
    return Ui7VisualAssetSpec(
        genreKey = poster.slug,
        fileName = poster.filename,
        widthPx = poster.widthPx,
        heightPx = poster.heightPx,
        kind = Ui7ArtworkKind.LEGACY_COMPLETE,
        approvedTreatments = approved,
        embeddedTitleVerified = true,
        frameIncluded = true,
        opticalMask = mask,
        protectedRects = emptyList(),
        counterSafeRect = if (verified) {
            poster.counterSafeRect ?: ui7EditorialCounterSafeRegion
        } else {
            null
        },
        approval = if (verified) Ui7AssetApproval.APPROVED else Ui7AssetApproval.PROVISIONAL,
    )
}

internal fun GenreEditorialTreatment.toUi7Treatment(): Ui7Treatment = when (this) {
    GenreEditorialTreatment.COVER -> Ui7Treatment.EDITORIAL_COVER
    GenreEditorialTreatment.PORTRAIT_LEFT -> Ui7Treatment.EDITORIAL_PORTRAIT_LEFT
    GenreEditorialTreatment.PORTRAIT_RIGHT -> Ui7Treatment.EDITORIAL_PORTRAIT_RIGHT
}

/**
 * Normative decision of §8.1: the single place that decides between the approved
 * asset and the view-specific Compose fallback. It never inspects pixels; it
 * consults the offline optical metadata, the approved treatments and the pure
 * fit/counter arithmetic.
 */
internal enum class Ui7RenderOutcome {
    /** Exact approved asset: draw the single optical contour plus the numeric chip. */
    ASSET,

    /** No catalog row for this genre: the library still shows a fallback card. */
    NO_ASSET,

    /** Row exists but the optical contour was not measured: never guess a frame. */
    UNVERIFIED_CONTOUR,

    /** Editorial portrait slot without an authentic vertical variant. */
    NEEDS_APPROVED_PORTRAIT,

    /** Editorial cover whose approved sheet cannot fill the 192dp box intact. */
    NEEDS_APPROVED_COVER,

    /** Mosaico source whose ratio cannot fill the 7:5 / 8:5 cell without a band. */
    UNSUPPORTED_ASPECT,

    /** The chip would leave the verified bottom-end region or touch protected art. */
    COUNTER_COLLISION,
}

/** §8.1 decision tree, shared by both views and by the pure tests. */
internal fun ui7RenderOutcome(
    spec: Ui7VisualAssetSpec?,
    treatment: Ui7Treatment,
    cardWidthDp: Float,
    cardHeightDp: Float,
    sourceRatio: Float,
    chipWidthDp: Float,
    chipHeightDp: Float,
): Ui7RenderOutcome {
    if (spec == null) return Ui7RenderOutcome.NO_ASSET
    if (!spec.frameIncluded || spec.opticalMask?.verified != true) {
        return Ui7RenderOutcome.UNVERIFIED_CONTOUR
    }
    val portrait = treatment == Ui7Treatment.EDITORIAL_PORTRAIT_LEFT ||
        treatment == Ui7Treatment.EDITORIAL_PORTRAIT_RIGHT
    if (!spec.approvedTreatments.contains(treatment)) {
        return if (portrait) {
            Ui7RenderOutcome.NEEDS_APPROVED_PORTRAIT
        } else {
            Ui7RenderOutcome.NEEDS_APPROVED_COVER
        }
    }
    if (!genreArtworkFitsWithoutDamage(cardWidthDp, cardHeightDp, sourceRatio)) {
        return when {
            portrait -> Ui7RenderOutcome.NEEDS_APPROVED_PORTRAIT
            treatment == Ui7Treatment.EDITORIAL_COVER -> Ui7RenderOutcome.NEEDS_APPROVED_COVER
            else -> Ui7RenderOutcome.UNSUPPORTED_ASPECT
        }
    }
    if (!counterPlacementIsSafe(spec, cardWidthDp, cardHeightDp, chipWidthDp, chipHeightDp)) {
        return Ui7RenderOutcome.COUNTER_COLLISION
    }
    return Ui7RenderOutcome.ASSET
}

/**
 * Quadratic-corner outline. The source packs are a rounded square: a circular
 * `RoundedCornerShape` either cuts the baked stroke at the four corners or leaves
 * opaque wedges outside it. The quadratic control point at the actual corner
 * reproduces the measured intercepts.
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
        val width = size.width
        val height = size.height
        if (width <= 0f || height <= 0f || sourceWidthPx <= 0 || sourceHeightPx <= 0) {
            return Outline.Rectangle(androidx.compose.ui.geometry.Rect(0f, 0f, width, height))
        }
        val sx = width / sourceWidthPx.toFloat()
        val sy = height / sourceHeightPx.toFloat()
        val left = (mask.leftInsetPx * sx).coerceIn(0f, width / 2f)
        val top = (mask.topInsetPx * sy).coerceIn(0f, height / 2f)
        val right = (width - mask.rightInsetPx * sx).coerceIn(width / 2f, width)
        val bottom = (height - mask.bottomInsetPx * sy).coerceIn(height / 2f, height)
        val maxRadiusX = max(0f, (right - left) / 2f)
        val maxRadiusY = max(0f, (bottom - top) / 2f)
        val limit = min(maxRadiusX, maxRadiusY)
        fun radius(px: Float): Float = px.coerceIn(0f, max(2f, limit))
        val rtl = radius(mask.topLeftRadiusPx * sx)
        val rtr = radius(mask.topRightRadiusPx * sx)
        val rbr = radius(mask.bottomRightRadiusPx * sx)
        val rbl = radius(mask.bottomLeftRadiusPx * sx)
        val path = Path().apply {
            moveTo(left + rtl, top)
            lineTo(right - rtr, top)
            quadraticTo(right, top, right, top + rtr)
            lineTo(right, bottom - rbr)
            quadraticTo(right, bottom, right - rbr, bottom)
            lineTo(left + rbl, bottom)
            quadraticTo(left, bottom, left, bottom - rbl)
            lineTo(left, top + rtl)
            quadraticTo(left, top, left + rtl, top)
            close()
        }
        return Outline.Generic(path)
    }
}
