package org.michimusic.mobile.ui.screens

/** UI7 Editorial's completed source sheets are finished posters, including title/frame.
 * They are never converted to portrait tiles, and never drive genre creation.
 */
internal data class EditorialPosterAsset(
    val slug: String,
    val filename: String,
    val widthPx: Int,
    val heightPx: Int,
    /** [editorialPosterApprovalState] or [editorialPosterPendingApprovalState]. */
    val approval: String = editorialPosterApprovalState,
    /** [editorialPosterPresentationState] or [editorialPosterPendingPresentationState]. */
    val presentation: String = editorialPosterPresentationState,
    val opticalMask: Ui7OpticalMask? = null,
    val counterSafeRect: NormalizedRect? = null,
) {
    val ratio: Float get() = widthPx.toFloat() / heightPx

    /** True only for the sheets that passed art review; pending ones never count. */
    val isVisuallyApproved: Boolean
        get() = approval == editorialPosterApprovalState &&
            presentation == editorialPosterPresentationState
}

internal const val editorialPosterManifestHeader = "# UI7_EDITORIAL_POSTER_MANIFEST_V1"
internal const val editorialPosterFolder = "ui7/editorial/posters"
internal const val editorialPosterApprovalState = "USER_CONFIRMED_COMPLETE"
internal const val editorialPosterPresentationState = "POSTER_COMPLETE"
/** Bytes verified and published, poster artwork still awaiting visual review. */
internal const val editorialPosterPendingApprovalState = "IMPORTED_PENDING_VISUAL_APPROVAL"
internal const val editorialPosterPendingPresentationState = "POSTER_IMPORTED_PENDING"
private const val maxPosterManifestChars = 196_608
private val posterHash = Regex("[a-f0-9]{64}")
private val posterSourceSheet = Regex("UI7_EDITORIAL_LAMINA_[0-9]{2}_[A-Za-z0-9_]+\\.png")

/** The exact (approval, presentation) pairs a poster row may declare. */
private val editorialPosterAcceptedStates = setOf(
    editorialPosterApprovalState to editorialPosterPresentationState,
    editorialPosterPendingApprovalState to editorialPosterPendingPresentationState,
)

/** Finished posters use the approved genre identity, not a Production asset path. */
internal fun editorialPosterSlug(genreTitle: String): String? =
    editorialArtworkSlug(genreTitle)

internal fun editorialPosterForTitle(
    genreTitle: String,
    posters: Map<String, EditorialPosterAsset>,
): EditorialPosterAsset? = editorialPosterSlug(genreTitle)?.let(posters::get)

/**
 * A poster is an authentic *vertical* resource when its declared proportions are
 * portrait. The approved pack is 1.4..4.5:1 (horizontal sheets only), so every
 * portrait Editorial slot currently records
 * [GenreArtworkPresentationSpec.pendingVerticalAsset] in the technical report.
 */
internal val EditorialPosterAsset.isVertical: Boolean
    get() = widthPx.toFloat() / heightPx.toFloat() < 1f

/** Posters >= 2.4:1 are the approved panoramic sheets; the layout keeps them in
 * their positional slot (a poster never widens a cell on its own) so Editorial
 * cannot drift into a wall of full-width banners.
 */

/** Fail closed atomically: a malformed row invalidates the whole poster catalog.
 * File bytes and SHA-256 are checked at Android build time, not on UI thread.
 */
internal fun editorialApprovedPosters(
    manifest: String,
    installed: Set<String>,
    onRejected: (String) -> Unit = {},
    opticalMasks: Map<String, Ui7OpticalMask> = emptyMap(),
): Map<String, EditorialPosterAsset> {
    fun reject(reason: String): Map<String, EditorialPosterAsset> {
        onRejected(reason)
        return emptyMap()
    }
    if (manifest.length > maxPosterManifestChars) return reject("oversized_manifest")
    val rows = manifest.lineSequence().iterator()
    if (!rows.hasNext() || rows.next() != editorialPosterManifestHeader) return reject("invalid_header")
    val result = linkedMapOf<String, EditorialPosterAsset>()
    val used = hashSetOf<String>()
    var lineNumber = 1
    for (line in rows) {
        lineNumber++
        if (line.isBlank() || line.startsWith('#')) continue
        val parts = line.split('\t')
        if (parts.size != 8) return reject("line_${lineNumber}_columns")
        val slug = parts[0]; val filename = parts[1]; val sha = parts[2]
        val width = parts[3]; val height = parts[4]; val sourceSheet = parts[5]
        val approval = parts[6]; val presentation = parts[7]
        val w = width.toIntOrNull() ?: return reject("line_${lineNumber}_width")
        val h = height.toIntOrNull() ?: return reject("line_${lineNumber}_height")
        if (slug !in editorialSourceKeys || filename != "genre_${slug}_editorial.webp")
            return reject("line_${lineNumber}_genre_identity")
        if (!posterHash.matches(sha) || !posterSourceSheet.matches(sourceSheet) ||
            (approval to presentation) !in editorialPosterAcceptedStates)
            return reject("line_${lineNumber}_integrity_or_approval")
        if (w !in 320..4096 || h !in 200..4096 || w.toLong() * h > 3_686_400L ||
            w.toDouble() / h !in 1.4..4.5) return reject("line_${lineNumber}_geometry")
        if (filename !in installed) return reject("line_${lineNumber}_asset_missing")
        if (!used.add(filename) || result.containsKey(slug)) return reject("line_${lineNumber}_duplicate")
        val mask = opticalMasks[filename]?.takeIf {
            it.sourceWidthPx == w && it.sourceHeightPx == h
        }
        result[slug] = EditorialPosterAsset(
            slug = slug,
            filename = filename,
            widthPx = w,
            heightPx = h,
            approval = approval,
            presentation = presentation,
            opticalMask = mask,
            counterSafeRect = ui7EditorialCounterSafeRegion,
        )
    }
    if (installed.any { it.endsWith(".webp") && it !in used }) return reject("unexpected_webp")
    if (result.isEmpty()) return reject("empty_catalog")
    return result
}
