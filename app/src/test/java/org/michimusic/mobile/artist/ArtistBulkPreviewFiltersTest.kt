package org.michimusic.mobile.artist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ArtistBulkPreviewFiltersTest {
    private val rows = ArtistBulkDisposition.entries.mapIndexed { index, disposition ->
        ArtistBulkPreviewRow("Artist-$index", listOf("album-$index"), 1, disposition)
    }

    @Test fun allFilterReturnsAllRowsInSourceOrder() {
        assertEquals(rows, artistBulkFilteredRows(rows, ArtistBulkReviewFilter.ALL))
    }

    @Test fun verifiedFilterContainsOnlyExplicitReviewEligibleRows() {
        val result = artistBulkFilteredRows(rows, ArtistBulkReviewFilter.READY)
        assertEquals(1, result.size)
        assertEquals(ArtistBulkDisposition.ELIGIBLE_FOR_EXPLICIT_REVIEW, result.single().disposition)
    }

    @Test fun pendingFilterContainsOnlyActionableStates() {
        assertEquals(setOf(ArtistBulkDisposition.IDENTITY_REQUIRED,
            ArtistBulkDisposition.MEMBERSHIP_RECONFIRMATION,
            ArtistBulkDisposition.PROFILE_MISSING),
            artistBulkFilteredRows(rows, ArtistBulkReviewFilter.REVIEW).map { it.disposition }.toSet())
    }

    @Test fun conflictFilterPreservesEveryBlockedOrUnreliableState() {
        val selected = artistBulkFilteredRows(rows, ArtistBulkReviewFilter.BLOCKED)
        assertEquals(setOf(ArtistBulkDisposition.IDENTITY_CONFLICT,
            ArtistBulkDisposition.INVALID_SCOPE,
            ArtistBulkDisposition.DUPLICATE_SCOPE,
            ArtistBulkDisposition.READ_ERROR), selected.map { it.disposition }.toSet())
    }

    @Test fun filtersAreExhaustiveAndNonOverlapping() {
        val filters = ArtistBulkReviewFilter.entries.filter { it != ArtistBulkReviewFilter.ALL }
        val total = filters.sumOf { artistBulkReviewFilterCount(rows, it) }
        assertEquals(rows.size, total)
        assertTrue(rows.all { row -> filters.count { artistBulkReviewFilterMatches(row,it) } == 1 })
    }

    @Test fun emptyInputYieldsZeroCounts() {
        ArtistBulkReviewFilter.entries.forEach { filter ->
            assertEquals(0, artistBulkReviewFilterCount(emptyList(), filter))
            assertTrue(artistBulkFilteredRows(emptyList(), filter).isEmpty())
        }
    }
}
