package org.michimusic.data.metadata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.michimusic.core.metadata.MetadataCommittedLibraryChange
import org.michimusic.core.metadata.MetadataField
import org.michimusic.core.metadata.MetadataSnapshot
import org.michimusic.core.metadata.MetadataTarget
import org.michimusic.core.models.Track
import org.michimusic.data.cache.MichiDatabase
import org.michimusic.data.repository.MetadataCommittedTrackProjection

/**
 * A committed revision that mixes displayed scalars with extended tags (composer,
 * lyrics, MusicBrainz ids) is still one committed fact. Refusing it would leave the
 * Library showing values the source no longer holds, so only the projectable subset
 * is written and every other scalar is preserved untouched.
 */
class MetadataCommittedProjectionExtensionTest {
    @Test
    fun mixedProjectableAndExtendedFieldsProjectTheirSubset() {
        val change = change(
            transactionId = "tx-mixed",
            changedFields = setOf(
                MetadataField.TITLE,
                MetadataField.COMPOSER,
            ),
            known = mapOf(
                MetadataField.TITLE to listOf("After Midnight"),
                MetadataField.COMPOSER to listOf("Some Composer"),
            ),
        )

        val result = MetadataCommittedTrackProjection.apply(track(), change)
        assertTrue("expected Applied but got $result", result is MetadataCommittedTrackProjection.Result.Applied)
        val updated = (result as MetadataCommittedTrackProjection.Result.Applied).track

        assertEquals("After Midnight", updated.title)
        assertEquals("Artist One", updated.artist)
        assertEquals("Old Album", updated.album)
        assertEquals("Artist One", updated.albumArtist)
        assertEquals("Rock", updated.genre)
        assertEquals(4, updated.trackNumber)
        assertEquals(2020, updated.year)
        assertEquals(120L, updated.size)
        assertEquals("b".repeat(64), updated.contentHash)
        assertEquals("local_7", updated.id)
        assertEquals(URI, updated.filepath)
        assertEquals("cover_rock", updated.coverId)
        assertEquals("42", updated.albumId)
    }

    @Test
    fun extendedOnlyChangeKeepsEveryScalarAndUnionStaysExplicit() {
        assertTrue(MetadataCommittedTrackProjection.projectableFields.contains(MetadataField.GENRE))
        assertTrue(MetadataCommittedTrackProjection.extendedFields.contains(MetadataField.COMPOSER))
        assertTrue(MetadataCommittedTrackProjection.extendedFields.contains(MetadataField.LYRICS))
        assertTrue(
            MetadataCommittedTrackProjection.projectableFields
                .intersect(MetadataCommittedTrackProjection.extendedFields)
                .isEmpty(),
        )

        val change = change(
            transactionId = "tx-extended",
            changedFields = setOf(
                MetadataField.COMPOSER,
                MetadataField.LYRICS,
            ),
            known = mapOf(
                MetadataField.COMPOSER to listOf("Some Composer"),
                MetadataField.LYRICS to listOf("la la la"),
            ),
        )

        val result = MetadataCommittedTrackProjection.apply(track(), change)
        assertTrue("expected Applied but got $result", result is MetadataCommittedTrackProjection.Result.Applied)
        val updated = (result as MetadataCommittedTrackProjection.Result.Applied).track

        assertEquals("Before", updated.title)
        assertEquals("Artist One", updated.artist)
        assertEquals("Old Album", updated.album)
        assertEquals("Artist One", updated.albumArtist)
        assertEquals("Rock", updated.genre)
        assertEquals(4, updated.trackNumber)
        assertEquals(2020, updated.year)
        assertEquals("42", updated.albumId)
        assertEquals("cover_rock", updated.coverId)
        assertEquals(120L, updated.size)
        assertEquals("b".repeat(64), updated.contentHash)
    }

    private fun track(): Track = Track(
        id = "local_7",
        title = "Before",
        artist = "Artist One",
        album = "Old Album",
        albumArtist = "Artist One",
        albumId = "42",
        size = 100L,
        format = "FLAC",
        coverId = "cover_rock",
        trackNumber = 4,
        year = 2020,
        filepath = URI,
        sourceId = SOURCE,
        genre = "Rock",
    )

    private fun change(
        transactionId: String,
        changedFields: Set<MetadataField>,
        known: Map<MetadataField, List<String>>,
    ): MetadataCommittedLibraryChange = MetadataCommittedLibraryChange(
        transactionId = transactionId,
        sourceId = SOURCE,
        trackId = "local_7",
        uri = URI,
        changedFields = changedFields,
        resourceOperations = emptySet(),
        snapshot = MetadataSnapshot(
            target = MetadataTarget(
                sourceId = SOURCE,
                trackId = "local_7",
                uri = URI,
                evidenceToken = "post-readback",
            ),
            tagsVerified = true,
            known = known,
        ),
        previousRevision = 1L,
        committedRevision = 2L,
        committedBytes = 120L,
        committedSha256 = "b".repeat(64),
    )

    @Test
    fun multivalueFieldsProjectTheirDeterministicPrimaryDisplayValue() {
        val result = MetadataCommittedTrackProjection.apply(
            track(),
            change(
                "multivalue",
                setOf(MetadataField.TITLE, MetadataField.ARTIST, MetadataField.GENRE),
                mapOf(
                    MetadataField.TITLE to listOf("Title One", "Title Two"),
                    MetadataField.ARTIST to listOf("Artist One", "Artist Two"),
                    MetadataField.GENRE to listOf("Rock", "Jazz"),
                ),
            ),
        ) as MetadataCommittedTrackProjection.Result.Applied
        val updated = result.track
        assertEquals("Title One", updated.title)
        assertEquals("Artist One", updated.artist)
        assertEquals("Rock", updated.genre)
        assertEquals("Old Album", updated.album)
        assertEquals("Artist One", updated.albumArtist)
        assertEquals(4, updated.trackNumber)
        assertEquals(2020, updated.year)
        assertEquals("42", updated.albumId)
        assertEquals("cover_rock", updated.coverId)
        assertEquals(120L, updated.size)
        assertEquals("b".repeat(64), updated.contentHash)
    }

    private companion object {
        const val SOURCE = MichiDatabase.DEFAULT_DEVICE_LIBRARY_ID
        const val URI = "content://media/external_primary/audio/media/7"
    }
}
