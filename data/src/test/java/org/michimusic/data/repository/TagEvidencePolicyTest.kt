package org.michimusic.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TagEvidencePolicyTest {
    @Test fun genreDoesNotMaskOtherMissingFields() {
        assertTrue(TagEvidencePolicy.needsProbe("Jazz", "Tema", "Unknown Artist", "Disco"))
        assertTrue(TagEvidencePolicy.needsProbe("", "Tema", "Artista", "Disco"))
        assertFalse(TagEvidencePolicy.needsProbe("Rock", "Tema", "Artista", "Disco"))
    }

    @Test fun yearMustBeUnambiguous() {
        assertEquals(1841, TagEvidencePolicy.exactYear("1841-09-12"))
        assertEquals(2022, TagEvidencePolicy.exactYear("2022"))
        assertEquals(null, TagEvidencePolicy.exactYear("20xx-01-01"))
    }

    @Test fun retainsStructuredGenresWithoutInventedDelimiters() {
        val fields = EmbeddedTagFields(genres = listOf("R&B/Soul", "Jazz", "Jazz"))
        val saved = TagEvidencePolicy.record("", "local_12", 900, 100, fields)
        assertEquals("R&B/Soul; Jazz", saved.genre)
        assertEquals("R&B/Soul\u0000Jazz", saved.genreValues)
    }

    @Test fun multiGenreCompatibilityFieldRetainsEveryActualValue() {
        val fields = EmbeddedTagFields(genres = listOf("Jazz", "Bossa Nova", "Jazz"))
        val row = TagEvidencePolicy.record("", "local_7", 300, 44, fields)
        assertEquals("Jazz; Bossa Nova", row.genre)
        assertEquals("Jazz\u0000Bossa Nova", row.genreValues)
    }

    @Test fun ownerMakesTracksIndependent() {
        val a = TagEvidencePolicy.record("", "track_1", 10, 20, null)
        val b = TagEvidencePolicy.record("tree_27", "track_1", 10, 20, null)
        assertTrue(a.sourceId != b.sourceId)
        assertEquals("", a.genre)
    }

    @Test fun invalidatesBySizeDateAndParserVersion() {
        val a = TagEvidencePolicy.record("", "local_1", 400, 100, null)
        assertTrue(a.matchesMediaStore(400, 100))
        assertFalse(a.matchesMediaStore(401, 100))
        assertFalse(a.matchesMediaStore(400, 101))
        assertFalse(a.matchesMediaStore(400, 0))
        assertFalse(a.copy(parserVersion = 1).matchesMediaStore(400, 100))
    }

    @Test fun persistsExtendedFieldsWithoutChangingTrackContract() {
        val f = EmbeddedTagFields(
            genres = listOf("Classical"), composer = "Clara Schumann",
            discNumber = 2, discTotal = 4, date = "1841-09-12",
        )
        val a = TagEvidencePolicy.record("saf", "id", 2048, 200, f)
        assertEquals("Clara Schumann", a.composer)
        assertEquals(2, a.discNumber)
        assertEquals(4, a.discTotal)
        assertEquals("1841-09-12", a.date)
    }
}
