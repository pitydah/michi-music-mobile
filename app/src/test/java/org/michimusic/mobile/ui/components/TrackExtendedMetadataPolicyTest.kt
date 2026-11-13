package org.michimusic.mobile.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.michimusic.data.repository.ExtendedTrackMetadata

class TrackExtendedMetadataPolicyTest {
    private fun metadata() = ExtendedTrackMetadata(
        sourceId = "saf-a", trackId = "track-1", composer = "B. Gibb",
        trackNumber = 2, trackTotal = 11, discNumber = 1, discTotal = 1,
        date = "1987", genres = listOf("Pop rock"),
    )

    @Test fun extendedFieldsKeepOriginalNumbersAndComposer() {
        val rows = TrackExtendedMetadataPolicy.rows(metadata())
        assertEquals(listOf("Compositor", "Fecha original", "Pista", "Disco", "Género original"),
            rows.map { it.label })
        assertEquals(listOf("B. Gibb", "1987", "2 / 11", "1 / 1", "Pop rock"),
            rows.map { it.value })
    }

    @Test fun literalSemicolonDoesNotProduceTwoRowsOrCategories() {
        val rows = TrackExtendedMetadataPolicy.rows(metadata().copy(genres = listOf("Jazz; Fusion")))
        assertEquals("Jazz; Fusion", rows.last().value)
        assertEquals(1, rows.count { it.label == "Género original" })
    }

    @Test fun separateOriginalGenresAppearWithoutChangingSpelling() {
        val rows = TrackExtendedMetadataPolicy.rows(metadata().copy(genres = listOf("R&B/Soul", "Jazz")))
        assertEquals("R&B/Soul · Jazz", rows.last().value)
    }

    @Test fun invalidTrackTotalNeverInventsAnOrdinal() {
        val rows = TrackExtendedMetadataPolicy.rows(metadata().copy(trackNumber = 9, trackTotal = 3,
            discNumber = null, discTotal = 2))
        assertEquals("9", rows.first { it.label == "Pista" }.value)
        assertFalse(rows.any { it.label == "Disco" })
    }

    @Test fun malformedGenreListIsOmittedEntirely() {
        val rows = TrackExtendedMetadataPolicy.rows(metadata().copy(genres = (1..33).map { "Style $it" }))
        assertFalse(rows.any { it.label == "Género original" })
        val bad = TrackExtendedMetadataPolicy.rows(metadata().copy(genres = listOf("Rock\u0000Pop")))
        assertFalse(bad.any { it.label == "Género original" })
    }

    @Test fun emptyOrUnsafeValuesDoNotShowPrivateControlCharacters() {
        val rows = TrackExtendedMetadataPolicy.rows(metadata().copy(composer = "\u001bsecret",
            date = "", genres = emptyList()))
        assertFalse(rows.any { it.label == "Compositor" || it.label == "Fecha original" ||
            it.label == "Género original" })
    }
}
