package org.michimusic.data.cache

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure structural contract; the Room migration is validated in OpenCode. */
class MetadataPersonEvidenceTest {
    private fun row(
        artistCount: Int = 3, artistValues: String = "A\u0000A\u0000Jazz; Fusion",
        albumArtistCount: Int = 2, albumArtistValues: String = "X\u0000Y",
        sourceId: String = "",
    ) = MetadataPersonEvidence(sourceId, "track", 123L, 11L, "a".repeat(64),
        artistCount, artistValues, albumArtistCount, albumArtistValues)

    @Test fun localEmptyOwnerAndDuplicateNamesAreLossless() {
        val evidence = row()
        assertTrue(evidence.structurallyValid())
        assertEquals(listOf("A", "A", "Jazz; Fusion"), evidence.artists())
        assertEquals(listOf("X", "Y"), evidence.albumArtists())
    }

    @Test fun nonlocalOwnerDoesNotChangeListSemantics() {
        assertEquals(listOf("X", "Y"), row(sourceId="safroot_42").albumArtists())
    }

    @Test fun absentListIsNotAnInferredArtist() {
        val evidence=row(artistCount=0,artistValues="")
        assertTrue(evidence.structurallyValid())
        assertEquals(emptyList<String>(), evidence.artists())
    }

    @Test fun rejectsAmbiguousCountAndBlankEntries() {
        assertTrue(row(artistCount=4).artists() == null)
        assertFalse(row(artistCount=4).structurallyValid())
        assertTrue(row(artistCount=2,artistValues="A\u0000").artists() == null)
        assertFalse(row(albumArtistCount=2,albumArtistValues="X").structurallyValid())
    }
}
