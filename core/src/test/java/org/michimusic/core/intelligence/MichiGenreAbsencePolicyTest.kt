package org.michimusic.core.intelligence

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MI-26 P0 2.1: one canonical absence policy instead of five divergent lists.
 * A missing marker is not a musical genre; `Other`/`Various` are containers and
 * stay visible as literal values; a real but uncatalogued style is untouched.
 */
class MichiGenreAbsencePolicyTest {
    private val policy = MichiGenreAbsencePolicy

    @Test
    fun everyAbsenceMarkerIsMissingRegardlessOfCaseOrDiacritics() {
        val markers = listOf(
            "", "Unknown Genre", "unknown", "<unknown>", "[unknown]", "Unspecified",
            "not specified", "undefined", "Sin género", "sin genero", "SIN GÉNERO",
            "Sin clasificar", "sin clasificación", "No Genre", "no genero", "none",
            "N/A", "unclassified", "desconocido", "?",
        )
        markers.forEach { marker ->
            assertEquals("marker <$marker>", MichiGenreAbsencePolicy.Kind.MISSING, policy.classify(marker))
            assertTrue(policy.isMissing(marker))
            assertFalse("must not be assignable: <$marker>", policy.isAssignableLabel(marker))
        }
    }

    @Test
    fun otherAndVariousAreContainersNotAbsencesAndNeverAssignable() {
        listOf("Other", "others", "Otros", "Various", "Various Artists", "varios").forEach {
            assertEquals(MichiGenreAbsencePolicy.Kind.CONTAINER, policy.classify(it))
            // Preserved as visible data, so it is not an absence marker...
            assertFalse(policy.isMissing(it))
            // ...but it can never become a proposed/edited musical genre.
            assertFalse(policy.isAssignableLabel(it))
        }
    }

    @Test
    fun realStylesUnknownToTheCatalogAreNotMissing() {
        listOf("Kuduro", "Jangle Pop", "Electronic", "Electronica", "Afrobeat",
            "Afrobeats", "Hip/Hop", "Hip-Hop", "R B", "R&B", "Rock/Pop",
            "Rock; Jazz", "Dark Wave", "high energy").forEach {
            assertEquals("style <$it>", MichiGenreAbsencePolicy.Kind.LABEL, policy.classify(it))
            assertFalse(policy.isMissing(it))
            assertTrue(policy.isAssignableLabel(it))
        }
    }

    @Test
    fun disambiguationRefusesMissingMarkersButKeepsUnknownStyles() {
        listOf("Unknown Genre", "Sin género", "Unspecified", "No Genre", "Other").forEach {
            // `Other` is a container: it still groups literally, never disappears.
            if (MichiGenreAbsencePolicy.isMissing(it)) {
                assertEquals(null, MichiGenreDisambiguationPolicy.resolve(it))
            }
        }
        assertEquals("other", MichiGenreDisambiguationPolicy.key("Other"))
        assertEquals("kuduro", MichiGenreDisambiguationPolicy.key("Kuduro"))
        assertEquals("jangle pop", MichiGenreDisambiguationPolicy.key("Jangle Pop"))
        assertEquals("rock; jazz", MichiGenreDisambiguationPolicy.key("Rock; Jazz"))
        assertEquals("rock/pop", MichiGenreDisambiguationPolicy.key("Rock/Pop"))
    }

    @Test
    fun markersListMatchesTheFoldedPolicyForRoomAndUsesNoBlankEntry() {
        val markers = policy.missingMarkers
        assertFalse(markers.isEmpty())
        assertFalse(markers.any { it.isBlank() })
        assertEquals(markers.sorted(), markers)
        markers.forEach { assertEquals(MichiGenreAbsencePolicy.Kind.MISSING, policy.classify(it)) }
        assertTrue(markers.contains("sin genero"))
        assertTrue(markers.contains("unknown genre"))
        assertTrue(markers.contains("no genre"))
    }
}
