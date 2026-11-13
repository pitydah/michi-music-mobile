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

/** Reuse the strict genre->art mapping already shared by the derived-art resolver. */
internal fun editorialPosterSlug(genreTitle: String): String? =
    editorialProductionAssetPath(genreTitle, GenreEditorialTreatment.COVER, 2)
        ?.substringAfterLast("/genre_")?.removeSuffix("_cover.webp")

internal fun editorialPosterForTitle(
    genreTitle: String,
    posters: Map<String, EditorialPosterAsset>,
): EditorialPosterAsset? = editorialPosterSlug(genreTitle)?.let(posters::get)

/** Posters >= 2.4:1 span both columns; compact horizontal posters occupy one. */
internal fun editorialPosterSpan(columns: Int, poster: EditorialPosterAsset?): Int {
    require(columns >= 1)
    return if (columns > 1 && poster != null && poster.ratio >= 2.4f) columns else 1
}

/** Fail closed atomically: a malformed row invalidates the whole poster catalog.
 * File bytes and SHA-256 are checked at Android build time, not on UI thread.
 */
internal fun editorialApprovedPosters(manifest: String, installed: Set<String>): Map<String, EditorialPosterAsset> {
    if (manifest.length > 160_000) return emptyMap()
    val rows = manifest.lineSequence().iterator()
    if (!rows.hasNext() || rows.next() != editorialPosterManifestHeader) return emptyMap()
    val result = linkedMapOf<String, EditorialPosterAsset>()
    val used = hashSetOf<String>()
    for (line in rows) {
        if (line.isBlank() || line.startsWith('#')) continue
        val parts = line.split('\t')
        if (parts.size != 8) return emptyMap()
        val slug = parts[0]; val filename = parts[1]; val sha = parts[2]
        val width = parts[3]; val height = parts[4]; val sourceSheet = parts[5]
        val approval = parts[6]; val presentation = parts[7]
        val w = width.toIntOrNull() ?: return emptyMap()
        val h = height.toIntOrNull() ?: return emptyMap()
        if (slug !in editorialSourceKeys || filename != "genre_${slug}_editorial.webp" ||
            !posterHash.matches(sha) || !posterSourceSheet.matches(sourceSheet) ||
            approval != "USER_CONFIRMED_COMPLETE" || presentation != "POSTER_COMPLETE" ||
            w !in 320..4096 || h !in 200..4096 || w.toLong() * h > 3_686_400L ||
            w.toDouble() / h !in 1.4..4.5 || filename !in installed ||
            !used.add(filename) || result.containsKey(slug)
        ) return emptyMap()
        result[slug] = EditorialPosterAsset(slug, filename, w, h)
    }
    if (installed.any { it.endsWith(".webp") && it !in used }) return emptyMap()
    return result
}
