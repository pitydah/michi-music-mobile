package org.michimusic.mobile.ui.screens

/**
 * UI7-665: the difference between "the bytes are here" and "a human approved the art".
 *
 * The pack imported on 2026-11-17 grew from 312 to 665 crops per view. Every new row was
 * verified byte by byte, but none of it has been through art review, so the manifest
 * records [mosaicPendingApprovalState] / [editorialPosterPendingApprovalState] for them.
 * That state has to survive parsing, reach the diagnostics and be queryable afterwards,
 * otherwise a build could present unreviewed artwork as certified simply because the file
 * parsed. This policy is the single place that answers the question.
 *
 * It never changes what the UI renders: pending art is still shown, because it is real
 * artwork for a real genre. What it refuses is the claim of approval.
 */
internal const val ui7CatalogInventoryPerView = 665

/** Positions withdrawn from the Drive batch (7 duplicates of already published rows). */
internal val ui7WithdrawnCatalogPositions = setOf(557, 576, 589, 590, 591, 592, 607)

/** 1..672 minus the withdrawn ones: the exact inventory the pack must contain. */
internal val ui7ExpectedCatalogPositions: Set<Int> =
    (1..672).toSet() - ui7WithdrawnCatalogPositions

internal data class Ui7ArtworkPackReport(
    val view: String,
    val total: Int,
    val visuallyApproved: Int,
    val pendingVisualApproval: Int,
) {
    /**
     * True only when every row is present and every row passed review. A pending row
     * never counts as approved, no matter how valid its SHA-256 is.
     */
    val certificationReady: Boolean
        get() = total == ui7CatalogInventoryPerView &&
            pendingVisualApproval == 0 &&
            visuallyApproved == total

    /** Short, log-safe line. Never includes paths or URLs. */
    fun summary(): String =
        "$view total=$total approved=$visuallyApproved pending=$pendingVisualApproval " +
            "certified=$certificationReady"
}

internal fun mosaicPackReport(artwork: Map<String, MosaicArtworkAsset>): Ui7ArtworkPackReport {
    val approved = artwork.values.count { it.isVisuallyApproved }
    return Ui7ArtworkPackReport(
        view = "MOSAIC",
        total = artwork.size,
        visuallyApproved = approved,
        pendingVisualApproval = artwork.size - approved,
    )
}

internal fun editorialPackReport(
    posters: Map<String, EditorialPosterAsset>,
): Ui7ArtworkPackReport {
    val approved = posters.values.count { it.isVisuallyApproved }
    return Ui7ArtworkPackReport(
        view = "EDITORIAL",
        total = posters.size,
        visuallyApproved = approved,
        pendingVisualApproval = posters.size - approved,
    )
}

/**
 * The publication gate. Integration is finished when both packs hold the full inventory;
 * certification additionally requires that no row is still pending review. Callers must
 * keep these two verdicts apart instead of collapsing them into one boolean.
 */
internal data class Ui7ArtworkPublicationReport(
    val mosaic: Ui7ArtworkPackReport,
    val editorial: Ui7ArtworkPackReport,
) {
    /** Both packs contain every position the inventory declares. */
    val integrationComplete: Boolean
        get() = mosaic.total == ui7CatalogInventoryPerView &&
            editorial.total == ui7CatalogInventoryPerView

    /** Published and review-complete. Not implied by [integrationComplete]. */
    val certificationReady: Boolean
        get() = mosaic.certificationReady && editorial.certificationReady
}

internal fun ui7ArtworkPublicationReport(
    artwork: Map<String, MosaicArtworkAsset>,
    posters: Map<String, EditorialPosterAsset>,
): Ui7ArtworkPublicationReport =
    Ui7ArtworkPublicationReport(mosaicPackReport(artwork), editorialPackReport(posters))
