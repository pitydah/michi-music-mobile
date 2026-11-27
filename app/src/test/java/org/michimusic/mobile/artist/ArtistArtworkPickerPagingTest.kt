package org.michimusic.mobile.artist

import org.junit.Assert.assertEquals
import org.junit.Test

/** Paging never performs provider calls, and cannot silently strand item nine. */
class ArtistArtworkPickerPagingTest {
    @Test fun nineReceivedCandidatesBecomeReachableWithTwoExplicitExpansions() {
        val first = nextArtworkCandidateLimit(9, 0)
        assertEquals(4, first)
        val second = nextArtworkCandidateLimit(9, first)
        assertEquals(8, second)
        assertEquals(9, nextArtworkCandidateLimit(9, second))
    }

    @Test fun pagingIsBoundedByReturnedResultsAndResetsForAZeroResult() {
        assertEquals(0, nextArtworkCandidateLimit(0, 0))
        assertEquals(3, nextArtworkCandidateLimit(3, 0))
        assertEquals(3, nextArtworkCandidateLimit(3, 3))
    }
}
