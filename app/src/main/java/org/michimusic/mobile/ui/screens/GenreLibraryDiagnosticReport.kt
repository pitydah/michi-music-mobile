package org.michimusic.mobile.ui.screens

import org.michimusic.core.intelligence.MichiGenreAbsencePolicy

/**
 * MI-26 2.5: aggregated, on-demand UI7 diagnostic.
 *
 * Built ONLY from the already-loaded scoped genre index and the already-approved
 * artwork catalogs. It opens no file, runs no scan, calls no provider, and its
 * output contains no path, track id, title, artist or listening history: one row
 * per genre key with the original label, song count, evidence origin and the
 * exact reason a fallback is painted.
 */
internal data class GenreDiagnosticRow(
    val originalLabel: String,
    val canonicalKey: String,
    val songCount: Int,
    /** ESTRUCTURADA when verified individual tags exist, LITERAL otherwise. */
    val evidenceOrigin: String,
    val mosaicExact: Boolean,
    val editorialExact: Boolean,
    val fallbackCause: String,
)

internal object GenreLibraryDiagnosticReport {
    const val CSV_HEADER =
        "etiqueta_original,clave_canonica,canciones,origen_evidencia," +
            "mosaico_exacto,editorial_exacto,causa_fallback"

    fun build(
        genres: List<LibraryGenre>,
        mosaicKeys: Set<String>,
        editorialSlugs: Set<String>,
    ): List<GenreDiagnosticRow> = genres.map { genre ->
        if (genreIsUnclassified(genre.id)) {
            GenreDiagnosticRow(
                originalLabel = genre.title,
                canonicalKey = genre.id,
                songCount = genre.tracks.size,
                evidenceOrigin = "SIN_EVIDENCIA",
                mosaicExact = false,
                editorialExact = false,
                fallbackCause = "SIN_CLASIFICAR",
            )
        } else {
            val mosaic = genre.id in mosaicKeys
            val editorial = editorialArtworkSlug(genre.title)?.let { it in editorialSlugs } == true
            GenreDiagnosticRow(
                originalLabel = genre.title,
                canonicalKey = genre.id,
                songCount = genre.tracks.size,
                evidenceOrigin = if (genre.tracks.any { it.genreValues.isNotEmpty() })
                    "ESTRUCTURADA" else "LITERAL",
                mosaicExact = mosaic,
                editorialExact = editorial,
                fallbackCause = when {
                    mosaic && editorial -> "ARTE_EXACTO_AMBAS_VISTAS"
                    mosaic -> "MOSAICO_EXACTO_EDITORIAL_FALLBACK"
                    editorial -> "EDITORIAL_EXACTA_MOSAICO_FALLBACK"
                    else -> "SIN_ARTE_APROBADO"
                },
            )
        }
    }

    /** Aggregated CSV only; no persistent identifier ever reaches this text. */
    fun csv(rows: List<GenreDiagnosticRow>): String = buildString {
        appendLine(CSV_HEADER)
        rows.forEach { row ->
            appendLine(
                listOf(
                    csvField(row.originalLabel),
                    csvField(row.canonicalKey),
                    row.songCount.toString(),
                    row.evidenceOrigin,
                    if (row.mosaicExact) "SI" else "NO",
                    if (row.editorialExact) "SI" else "NO",
                    row.fallbackCause,
                ).joinToString(","),
            )
        }
    }

    /** The honest bucket is never reported as a musical style with artwork. */
    fun unclassifiedLabel(): String = MichiGenreAbsencePolicy.UNCLASSIFIED_LABEL

    private fun csvField(value: String): String {
        val clean = value.replace('\r', ' ').replace('\n', ' ')
        return if (clean.any { it == ',' || it == '"' }) {
            "\"" + clean.replace("\"", "\"\"") + "\""
        } else clean
    }
}
