package org.michimusic.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TagGenreBoundsTest {
    @Test fun thirtyTwoIndependentFieldsAreAcceptedVerbatim() {
        val tags = (1..32).map { "Genre $it" }
        assertEquals(tags, TagGenreBounds.safeValues(EmbeddedTagFields(genres = tags)))
    }

    @Test fun thirtyThreeFieldsRejectWholeSetWithoutPartialSidecar() {
        val fields = EmbeddedTagFields(genres = (1..33).map { "Genre $it" },
            composer = "Composer preserved")
        assertNull(TagGenreBounds.safeValues(fields))
        val row = TagEvidencePolicy.record("owner", "id", 400, 100, fields)
        assertEquals("", row.genre)
        assertEquals("", row.genreValues)
        assertEquals("Composer preserved", row.composer)
    }

    @Test fun literalSemicolonIsOneValueAndRepeatedTagsAreDeduplicated() {
        val fields = EmbeddedTagFields(genres = listOf("Jazz; Fusion", "Jazz; Fusion"))
        assertEquals(listOf("Jazz; Fusion"), TagGenreBounds.safeValues(fields))
        assertEquals("Jazz; Fusion", TagEvidencePolicy.record("", "x", 100, 5, fields).genre)
    }

    @Test fun rejectsTooManyBytesAndUntrustedControlsWithoutPartialGenres() {
        assertNull(TagGenreBounds.safeValues(EmbeddedTagFields(
            genres = List(5) { "a".repeat(2_000) + it })))
        assertNull(TagGenreBounds.safeValues(EmbeddedTagFields(genres = listOf("Rock\u0000Metal"))))
        assertNull(TagGenreBounds.safeValues(EmbeddedTagFields(genres = listOf("Jazz\uFFFD"))))
        assertNull(TagGenreBounds.safeValues(EmbeddedTagFields(genres = List(257) { "Rock" })))
    }

    @Test fun missingTagAndBlankFieldsDoNotCreateGenre() {
        assertTrue(TagGenreBounds.safeValues(null).orEmpty().isEmpty())
        assertTrue(TagGenreBounds.safeValues(EmbeddedTagFields(genres = listOf(" ", "\t"))).orEmpty().isEmpty())
    }
}
