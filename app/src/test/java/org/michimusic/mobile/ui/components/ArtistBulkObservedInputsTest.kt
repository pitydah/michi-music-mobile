package org.michimusic.mobile.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.michimusic.mobile.artist.ArtistMembershipOccurrence

class ArtistBulkObservedInputsTest {
    private fun occurrence(credit: String, album: String, track: String, library: String? = "one") =
        ArtistMembershipOccurrence(libraryId = library, albumId = album,
            literalCredit = credit, trackIdentity = track)

    @Test fun caseVariantsKeepIndependentExactAlbums() {
        val result = artistBulkCreditsFromOccurrences("one", listOf(
            occurrence("A-ha", "album-1", "t1"),
            occurrence("a-ha", "album-2", "t2"),
            occurrence("A-ha", "album-3", "t3"),
        )) as ArtistBulkObservedInputs.Ready
        assertEquals(2, result.credits.size)
        assertEquals(listOf("album-1", "album-3"), result.credits.first { it.credit == "A-ha" }.albumIds)
        assertEquals(listOf("album-2"), result.credits.first { it.credit == "a-ha" }.albumIds)
    }

    @Test fun repeatedTrackDoesNotInflateCounts() {
        val result = artistBulkCreditsFromOccurrences("one", listOf(
            occurrence("A-ha", "a1", "t1"), occurrence("A-ha", "a1", "t1"),
        )) as ArtistBulkObservedInputs.Ready
        assertEquals(1, result.credits.single().trackCount)
        assertEquals(listOf("a1"), result.credits.single().albumIds)
    }

    @Test fun crossLibraryRecordBlocksWholePreview() {
        assertTrue(artistBulkCreditsFromOccurrences("one", listOf(
            occurrence("A-ha", "a1", "t1"), occurrence("Björk", "a2", "t2", "two"),
        )) is ArtistBulkObservedInputs.InvalidMembership)
    }

    @Test fun allLibrariesNeverBecomeOneOwner() {
        assertTrue(artistBulkCreditsFromOccurrences("SCOPE_ALL", listOf(
            occurrence("A-ha", "a1", "t1", null),
        )) is ArtistBulkObservedInputs.InvalidMembership)
    }

    @Test fun contradictoryTrackCreditBlocksAll() {
        assertTrue(artistBulkCreditsFromOccurrences("one", listOf(
            occurrence("A-ha", "a1", "t1"), occurrence("a-ha", "a1", "t1"),
        )) is ArtistBulkObservedInputs.InvalidMembership)
    }

    @Test fun trackReassignedToDifferentAlbumBlocksAll() {
        assertTrue(artistBulkCreditsFromOccurrences("one", listOf(
            occurrence("A-ha", "a1", "t1"), occurrence("A-ha", "a2", "t1"),
        )) is ArtistBulkObservedInputs.InvalidMembership)
    }

    @Test fun blankAlbumOrTrackCannotReachRoom() {
        assertTrue(artistBulkCreditsFromOccurrences("one", listOf(
            occurrence("A-ha", "", "t1"),
        )) is ArtistBulkObservedInputs.InvalidMembership)
        assertTrue(artistBulkCreditsFromOccurrences("one", listOf(
            occurrence("A-ha", "a1", ""),
        )) is ArtistBulkObservedInputs.InvalidMembership)
    }
}
