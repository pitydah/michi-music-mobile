package org.michimusic.core.metadata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MetadataStructuredCombinedRefreshPolicyTest {
    private val required = MetadataStructuredGenreRefreshPolicy.requiredEvidenceFields
    private fun delta(
        genre: List<String> = listOf("Jazz; Fusion", "Bebop"),
        artist: List<String> = listOf("Artist A", "Artist A"),
        albumArtist: List<String> = listOf("Ensemble"),
        unknown: Set<MetadataField> = emptySet(),
    ): MetadataSourceIndexDelta.Delta {
        val values = required.associateWith { emptyList<String>() }.toMutableMap()
        values[MetadataField.GENRE] = genre
        values[MetadataField.ARTIST] = artist
        values[MetadataField.ALBUM_ARTIST] = albumArtist
        values[MetadataField.TITLE] = listOf("Song")
        values[MetadataField.ALBUM] = listOf("Record")
        return MetadataSourceIndexDelta.Delta(
            MetadataTarget("safroot_1", "id1", "content://test/document/1", "token"),
            "uuid", "a".repeat(64), values, unknown, 512L)
    }

    @Test fun supportsTwoSimultaneousListsWithoutLosingOrderDuplicatesOrPunctuation() {
        val result = MetadataStructuredCombinedRefreshPolicy.inspect(delta())
            as MetadataStructuredCombinedRefreshPolicy.Result.Prepared
        assertEquals(listOf("Jazz; Fusion", "Bebop"), result.genres)
        assertEquals("Jazz; Fusion\u0000Bebop", result.genreValues)
        assertEquals(listOf("Artist A", "Artist A"), result.artists)
        assertEquals("Artist A\u0000Artist A", result.artistValues)
        assertEquals("Artist A", result.artist)
        assertFalse(result.mayPublishNow)
    }

    @Test fun multiAlbumArtistAlsoWorks() {
        val r = MetadataStructuredCombinedRefreshPolicy.inspect(delta(
            artist=listOf("A"),albumArtist=listOf("Band", "Orchestra")))
            as MetadataStructuredCombinedRefreshPolicy.Result.Prepared
        assertEquals("Band\u0000Orchestra", r.albumArtistValues)
        assertEquals("A", r.artist)
    }

    @Test fun genreOnlyIsNotCombined() {
        val r = MetadataStructuredCombinedRefreshPolicy.inspect(delta(artist=listOf("A")))
        assertEquals(MetadataStructuredCombinedRefreshPolicy.Result.Blocked(
            MetadataStructuredCombinedRefreshPolicy.Reason.WRONG_FIELDS), r)
    }

    @Test fun artistsOnlyAreNotCombined() {
        val r = MetadataStructuredCombinedRefreshPolicy.inspect(delta(genre=listOf("Jazz")))
        assertEquals(MetadataStructuredCombinedRefreshPolicy.Result.Blocked(
            MetadataStructuredCombinedRefreshPolicy.Reason.WRONG_FIELDS), r)
    }

    @Test fun missingRequiredFieldFailsEvenIfOtherFieldsLookCorrect() {
        val d = delta().let { it.copy(observedValues = it.observedValues - MetadataField.COMPOSER) }
        assertEquals(MetadataStructuredCombinedRefreshPolicy.Result.Blocked(
            MetadataStructuredCombinedRefreshPolicy.Reason.PARTIAL_EVIDENCE),
            MetadataStructuredCombinedRefreshPolicy.inspect(d))
    }

    @Test fun unverifiedRequiredFieldFailsClosed() {
        assertEquals(MetadataStructuredCombinedRefreshPolicy.Result.Blocked(
            MetadataStructuredCombinedRefreshPolicy.Reason.PARTIAL_EVIDENCE),
            MetadataStructuredCombinedRefreshPolicy.inspect(delta(unknown=setOf(MetadataField.TRACK_TOTAL))))
    }

    @Test fun malformedValuesCannotBePublished() {
        val bad = delta(genre=listOf("Jazz", "with\u0000NUL"))
        assertEquals(MetadataStructuredCombinedRefreshPolicy.Result.Blocked(
            MetadataStructuredCombinedRefreshPolicy.Reason.INVALID_GENRES),
            MetadataStructuredCombinedRefreshPolicy.inspect(bad))
        val tooMany = delta(artist=List(33) { "A$it" })
        assertEquals(MetadataStructuredCombinedRefreshPolicy.Result.Blocked(
            MetadataStructuredCombinedRefreshPolicy.Reason.INVALID_PEOPLE),
            MetadataStructuredCombinedRefreshPolicy.inspect(tooMany))
    }

    @Test fun noOtherScalarOrUnknownValueMayBeFlattened() {
        val d = delta().let { it.copy(observedValues = it.observedValues +
            (MetadataField.TITLE to listOf("One", "Two"))) }
        assertEquals(MetadataStructuredCombinedRefreshPolicy.Result.Blocked(
            MetadataStructuredCombinedRefreshPolicy.Reason.WRONG_FIELDS),
            MetadataStructuredCombinedRefreshPolicy.inspect(d))
    }

    @Test fun multiComposerCannotBeFlattenedIntoSingleEvidence() {
        val d = delta().let { it.copy(observedValues = it.observedValues +
            (MetadataField.COMPOSER to listOf("Composer 1", "Composer 2"))) }
        assertEquals(MetadataStructuredCombinedRefreshPolicy.Result.Blocked(
            MetadataStructuredCombinedRefreshPolicy.Reason.OTHER_MULTIVALUE),
            MetadataStructuredCombinedRefreshPolicy.inspect(d))
    }

    @Test fun malformedNumberCausesRefusalEvenWithTwoValidLists() {
        val d = delta().let { it.copy(observedValues = it.observedValues +
            (MetadataField.TRACK_NUMBER to listOf("track A"))) }
        assertEquals(MetadataStructuredCombinedRefreshPolicy.Result.Blocked(
            MetadataStructuredCombinedRefreshPolicy.Reason.WRONG_FIELDS),
            MetadataStructuredCombinedRefreshPolicy.inspect(d))
    }

    @Test fun invalidShaAndUriAreRejected() {
        val d = delta().copy(sourceSha256="fake")
        assertEquals(MetadataStructuredCombinedRefreshPolicy.Result.Blocked(
            MetadataStructuredCombinedRefreshPolicy.Reason.INVALID_TARGET),
            MetadataStructuredCombinedRefreshPolicy.inspect(d))
        assertTrue(MetadataStructuredCombinedRefreshPolicy.inspect(
            delta().copy(target=MetadataTarget("", "id", "https://bad", "x")))
            is MetadataStructuredCombinedRefreshPolicy.Result.Blocked)
    }
}
