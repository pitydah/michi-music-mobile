package org.michimusic.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.michimusic.core.metadata.MetadataCommittedLibraryChange
import org.michimusic.core.metadata.MetadataField
import org.michimusic.core.metadata.MetadataResourceOperation
import org.michimusic.core.metadata.MetadataSnapshot
import org.michimusic.core.metadata.MetadataTarget
import org.michimusic.core.models.Track

class MetadataCommittedTrackProjectionTest {
    private val uri = "content://media/external/audio/media/7"
    private val before = Track(
        id = "local_7",
        title = "Before",
        artist = "Artist",
        album = "Old Album",
        albumId = "42",
        size = 100,
        filepath = uri,
        contentHash = "a".repeat(64),
        sourceId = "",
    )

    @Test fun exactLocalChangePatchesOnlyVerifiedFieldsAndClearsStaleAlbumGrouping() {
        val change = change(
            fields = setOf(MetadataField.TITLE, MetadataField.ALBUM),
            known = mapOf(
                MetadataField.TITLE to listOf("After"),
                MetadataField.ALBUM to listOf("New Album"),
            ),
        )

        val result = MetadataCommittedTrackProjection.apply(before, change)

        assertTrue(result is MetadataCommittedTrackProjection.Result.Applied)
        val after = (result as MetadataCommittedTrackProjection.Result.Applied).track
        assertEquals("After", after.title)
        assertEquals("New Album", after.album)
        assertEquals("", after.albumId)
        assertEquals("Artist", after.artist)
        assertEquals(120L, after.size)
        assertEquals("b".repeat(64), after.contentHash)
    }

    @Test fun resourceOnlyChangePreservesScalarsButRefreshesPhysicalIdentity() {
        val change = change(
            fields = emptySet(),
            resources = setOf(MetadataResourceOperation.ID3_APIC_REPLACE),
            known = mapOf(MetadataField.TITLE to listOf("Before")),
        )

        val result = MetadataCommittedTrackProjection.apply(before, change)

        val after = (result as MetadataCommittedTrackProjection.Result.Applied).track
        assertEquals(before.title, after.title)
        assertEquals(before.albumId, after.albumId)
        assertEquals(120L, after.size)
        assertEquals("b".repeat(64), after.contentHash)
    }

    @Test fun mismatchedUriAndMultivalueChangedFieldAreRejected() {
        assertTrue(
            MetadataCommittedTrackProjection.apply(
                before.copy(filepath = "content://media/other"),
                change(setOf(MetadataField.TITLE), known = mapOf(
                    MetadataField.TITLE to listOf("One", "Two"),
                )),
            ) is MetadataCommittedTrackProjection.Result.Refused,
        )
        assertTrue(
            MetadataCommittedTrackProjection.apply(
                before,
                change(setOf(MetadataField.TITLE), known = mapOf(
                    MetadataField.TITLE to listOf("One", "Two"),
                )),
            ) is MetadataCommittedTrackProjection.Result.Refused,
        )
    }

    private fun change(
        fields: Set<MetadataField>,
        resources: Set<MetadataResourceOperation> = emptySet(),
        known: Map<MetadataField, List<String>>,
    ) = MetadataCommittedLibraryChange(
        transactionId = "tx",
        sourceId = "lib_device_local",
        trackId = before.id,
        uri = uri,
        changedFields = fields,
        resourceOperations = resources,
        snapshot = MetadataSnapshot(
            target = MetadataTarget(
                sourceId = "lib_device_local",
                trackId = before.id,
                uri = uri,
                evidenceToken = "cold-read",
            ),
            tagsVerified = true,
            known = known,
        ),
        previousRevision = 1,
        committedRevision = 2,
        committedBytes = 120,
        committedSha256 = "b".repeat(64),
    )
}
