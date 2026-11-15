package org.michimusic.mobile.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.michimusic.core.models.Track

/** MI-26 2.5: aggregated diagnostic over the loaded index, never a scan. */
class GenreLibraryDiagnosticReportTest {
    private fun track(id: String, genre: String, values: List<String> = emptyList()) =
        Track(id = id, title = "T$id", genre = genre, genreValues = values, filepath = "content://$id")

    private fun genre(id: String, title: String, vararg tracks: Track) =
        LibraryGenre(id = id, title = title, tracks = tracks.toList(), albumCount = 0)

    @Test
    fun reportsExactArtworkEvidenceOriginAndFallbackCause() {
        val rows = GenreLibraryDiagnosticReport.build(
            genres = listOf(
                genre("darkwave", "Dark Wave", track("1", "Dark Wave", listOf("Dark Wave"))),
                genre("jangle pop", "Jangle Pop", track("2", "Jangle Pop")),
                genre(
                    org.michimusic.core.intelligence.MichiGenreAbsencePolicy.UNCLASSIFIED_KEY,
                    org.michimusic.core.intelligence.MichiGenreAbsencePolicy.UNCLASSIFIED_LABEL,
                    track("3", ""),
                ),
            ),
            mosaicKeys = setOf("darkwave"),
            editorialSlugs = setOf("darkwave"),
        )

        assertEquals(3, rows.size)
        val darkwave = rows[0]
        assertEquals("darkwave", darkwave.canonicalKey)
        assertEquals(1, darkwave.songCount)
        assertEquals("ESTRUCTURADA", darkwave.evidenceOrigin)
        assertTrue(darkwave.mosaicExact)
        assertTrue(darkwave.editorialExact)
        assertEquals("ARTE_EXACTO_AMBAS_VISTAS", darkwave.fallbackCause)

        val jangle = rows[1]
        assertFalse(jangle.mosaicExact)
        assertFalse(jangle.editorialExact)
        assertEquals("LITERAL", jangle.evidenceOrigin)
        assertEquals("SIN_ARTE_APROBADO", jangle.fallbackCause)

        val unknown = rows[2]
        assertEquals("SIN_EVIDENCIA", unknown.evidenceOrigin)
        assertEquals("SIN_CLASIFICAR", unknown.fallbackCause)
        assertFalse(unknown.mosaicExact)
    }

    @Test
    fun csvIsAggregatedAndContainsNoPathOrTrackIdentity() {
        val rows = GenreLibraryDiagnosticReport.build(
            genres = listOf(genre("darkwave", "Dark Wave", track("track-1", "Dark Wave"))),
            mosaicKeys = setOf("darkwave"),
            editorialSlugs = emptySet(),
        )
        val csv = GenreLibraryDiagnosticReport.csv(rows)
        val lines = csv.trim().lines()
        assertEquals(GenreLibraryDiagnosticReport.CSV_HEADER, lines.first())
        assertEquals(2, lines.size)
        assertTrue(lines[1].startsWith("Dark Wave,darkwave,1,LITERAL,SI,NO,"))
        assertEquals("MOSAICO_EXACTO_EDITORIAL_FALLBACK", lines[1].substringAfterLast(','))
        assertFalse(csv.contains("content://"))
        assertFalse(csv.contains("track-1"))
        assertFalse(csv.contains("T1"))
    }

    @Test
    fun commasInALiteralLabelAreQuotedWithoutBreakingTheRow() {
        val rows = GenreLibraryDiagnosticReport.build(
            genres = listOf(genre("rock, pop", "Rock, Pop", track("1", "Rock, Pop"))),
            mosaicKeys = emptySet(),
            editorialSlugs = emptySet(),
        )
        val line = GenreLibraryDiagnosticReport.csv(rows).trim().lines()[1]
        assertTrue(line.startsWith("\"Rock, Pop\","))
    }
}
