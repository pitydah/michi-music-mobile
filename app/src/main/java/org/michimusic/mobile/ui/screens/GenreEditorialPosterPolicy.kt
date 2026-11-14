package org.michimusic.mobile.ui.screens

/** UI7 Editorial's completed source sheets are finished posters, including title/frame.
 * They are never converted to portrait tiles, and never drive genre creation.
 */
internal data class EditorialPosterAsset(
    val slug: String,
    val filename: String,
    val widthPx: Int,
    val heightPx: Int,
) {
    val ratio: Float get() = widthPx.toFloat() / heightPx
}

internal const val editorialPosterManifestHeader = "# UI7_EDITORIAL_POSTER_MANIFEST_V1"
internal const val editorialPosterFolder = "ui7/editorial/posters"
private val posterHash = Regex("[a-f0-9]{64}")
private val posterSourceSheet = Regex("UI7_EDITORIAL_LAMINA_[0-9]{2}_[A-Za-z0-9_]+\\.png")

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
 * portrait Editorial slot is currently `PENDIENTE DE RECURSO VERTICAL APROBADO`.
 */
internal val EditorialPosterAsset.isVertical: Boolean
    get() = widthPx.toFloat() / heightPx.toFloat() < 1f

/**
 * Mount inset that keeps the approved baked frame away from the card's own clip.
 *
 * A card clipped with radius `r` needs its content inset by at least
 * `r * (1 - 1/sqrt(2))` (4.69dp for the 16dp MichiShapes.md radius) so the clip
 * never cuts the artwork. 8dp gives 1.7x clearance. The approved poster's frame
 * line is 2..10px inside its bitmap, so the mount also hides the near-black
 * bitmap corner that would otherwise read as a wedge on a lighter surface.
 */
internal const val EDITORIAL_POSTER_MOUNT_INSET_DP = 8

/**
 * Height of the full-width poster band inside a treatment-height card.
 *
 * The band keeps the whole poster (`Fit`, never a false vertical crop) and never
 * runs into the reserved count-chip surface. The *card* height comes from the
 * treatment, not from this ratio: that is what keeps a paired Pop (514x268) and
 * Rock (1027x280) at one equal exterior height instead of 88.6dp vs 46.3dp.
 */
internal fun editorialPosterBandHeightDp(
    innerWidthDp: Float,
    maxBandHeightDp: Float,
    posterRatio: Float,
): Float {
    if (!maxBandHeightDp.isFinite() || maxBandHeightDp <= 0f) return 0f
    if (!innerWidthDp.isFinite() || innerWidthDp <= 0f) return maxBandHeightDp
    if (!posterRatio.isFinite() || posterRatio <= 0f) return maxBandHeightDp
    return minOf(innerWidthDp / posterRatio, maxBandHeightDp)
}

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
): Map<String, EditorialPosterAsset> {
    fun reject(reason: String): Map<String, EditorialPosterAsset> {
        onRejected(reason)
        return emptyMap()
    }
    if (manifest.length > 160_000) return reject("oversized_manifest")
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
            approval != "USER_CONFIRMED_COMPLETE" || presentation != "POSTER_COMPLETE")
            return reject("line_${lineNumber}_integrity_or_approval")
        if (w !in 320..4096 || h !in 200..4096 || w.toLong() * h > 3_686_400L ||
            w.toDouble() / h !in 1.4..4.5) return reject("line_${lineNumber}_geometry")
        if (filename !in installed) return reject("line_${lineNumber}_asset_missing")
        if (!used.add(filename) || result.containsKey(slug)) return reject("line_${lineNumber}_duplicate")
        result[slug] = EditorialPosterAsset(slug, filename, w, h)
    }
    if (installed.any { it.endsWith(".webp") && it !in used }) return reject("unexpected_webp")
    if (result.isEmpty()) return reject("empty_catalog")
    return result
}
