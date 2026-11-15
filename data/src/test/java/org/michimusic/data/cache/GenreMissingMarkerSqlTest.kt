package org.michimusic.data.cache

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.michimusic.core.intelligence.MichiGenreAbsencePolicy

/**
 * The Room UPDATE guard cannot call Kotlin, so it carries a literal marker list.
 * This test keeps it aligned with the ONE core absence policy: a marker accepted
 * by extraction must not be rejected by the index, and a container (`Other`,
 * `Various`) must never be overwritten as if it were missing.
 */
class GenreMissingMarkerSqlTest {
    @Test
    fun roomMarkersCoverEverySharedAbsenceMarker() {
        val sqlMarkers = GenreMissingMarkerSql.MARKERS
        MichiGenreAbsencePolicy.missingMarkers.forEach { marker ->
            assertTrue(
                "Room guard is missing the shared marker <$marker>",
                "'$marker'" in sqlMarkers,
            )
        }
    }

    @Test
    fun accentedProviderSpellingsAreAlsoCovered() {
        listOf("'sin género'", "'sin clasificación'", "'género desconocido'").forEach {
            assertTrue("missing accented marker $it", it in GenreMissingMarkerSql.MARKERS)
        }
    }

    @Test
    fun nonMusicalContainersAreNotTreatedAsMissingByTheGuard() {
        listOf("'other'", "'others'", "'various'", "'varios'").forEach {
            assertFalse("container $it must stay visible", it in GenreMissingMarkerSql.MARKERS)
        }
    }

    @Test
    fun guardCoversEmptyAndWhitespaceOnlyGenres() {
        assertTrue("TRIM(genre) = ''" in GenreMissingMarkerSql.IS_MISSING)
        assertTrue("LOWER(TRIM(genre)) IN (" in GenreMissingMarkerSql.IS_MISSING)
    }
}
