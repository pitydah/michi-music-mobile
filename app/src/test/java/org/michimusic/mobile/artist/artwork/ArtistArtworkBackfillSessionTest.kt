package org.michimusic.mobile.artist.artwork

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ArtistArtworkBackfillSessionTest {
    @Test fun oneTapEnqueuesAtMost48ArtistsAcrossFourPages() = runBlocking {
        var calls = 0
        val report = ArtistArtworkBackfillSession.run({ true }) {
            calls++
            ArtistArtworkBackfillSession.Page(inspected = 64, queued = 12, exhausted = false)
        }
        assertEquals(4, calls)
        assertEquals(256, report.inspected)
        assertEquals(48, report.queued)
        assertEquals(4, report.batches)
        assertEquals(false, report.exhausted)
    }

    @Test fun exhaustedPageEndsReviewWithoutAdditionalQueries() = runBlocking {
        var calls = 0
        val report = ArtistArtworkBackfillSession.run({ true }) {
            calls++
            if (calls == 1) ArtistArtworkBackfillSession.Page(64, 4, false)
            else ArtistArtworkBackfillSession.Page(17, 3, true)
        }
        assertEquals(2, calls)
        assertEquals(81, report.inspected)
        assertEquals(7, report.queued)
        assertEquals(true, report.exhausted)
    }

    @Test fun reviewDoesNotLoopWhenNoMoreRows() = runBlocking {
        var calls = 0
        val report = ArtistArtworkBackfillSession.run({ true }) {
            calls++
            ArtistArtworkBackfillSession.Page(0, 0, false)
        }
        assertEquals(1, calls)
        assertEquals(true, report.exhausted)
    }

    @Test fun revocationBetweenPagesStopsBeforeAnotherRead() = runBlocking {
        var allowed = true
        var calls = 0
        var cancelled = false
        try {
            ArtistArtworkBackfillSession.run({ allowed }) {
                calls++
                allowed = false
                ArtistArtworkBackfillSession.Page(64, 12, false)
            }
        } catch (_: CancellationException) { cancelled = true }
        assertTrue(cancelled)
        assertEquals(1, calls)
    }

    @Test fun malformedPageCannotBypassSessionBudgets() = runBlocking {
        var rejected = false
        try {
            ArtistArtworkBackfillSession.run({ true }) {
                ArtistArtworkBackfillSession.Page(65, 13, false)
            }
        } catch (_: IllegalArgumentException) { rejected = true }
        assertTrue(rejected)
    }

    @Test fun skippedCandidatesDoNotTriggerProviderWork() = runBlocking {
        val report = ArtistArtworkBackfillSession.run({ true }) {
            ArtistArtworkBackfillSession.Page(64, 0, false)
        }
        assertEquals(0, report.queued)
        assertEquals(4, report.batches)
    }
    @Test fun returningToSameSourceWithNewEpochRevokesReview() = runBlocking {
        // A(7) -> B(8) -> A(9): comparing only sourceId would accept the last A.
        var stamp = "source-A" to 7L
        val entered = stamp
        var pages = 0
        var cancelled = false
        try {
            ArtistArtworkBackfillSession.run({ stamp == entered }) {
                pages++
                stamp = "source-A" to 9L
                ArtistArtworkBackfillSession.Page(64, 12, false)
            }
        } catch (_: CancellationException) { cancelled = true }
        assertTrue(cancelled)
        assertEquals(1, pages)
    }

    @Test fun aNewRootGenerationMayStartOnlyItsOwnNewExplicitReview() = runBlocking {
        var stamp = "source-A" to 9L
        val entered = stamp
        var pages = 0
        val report = ArtistArtworkBackfillSession.run({ stamp == entered }) {
            pages++
            ArtistArtworkBackfillSession.Page(1, 1, true)
        }
        assertEquals(1, pages)
        assertEquals(1, report.queued)
        assertEquals(true, report.exhausted)
    }

    @Test fun revocationBeforeFirstPagePerformsNoQuery() = runBlocking {
        var stamp = "source-A" to 7L
        val entered = stamp
        stamp = "source-B" to 8L
        var queries = 0
        var cancelled = false
        try {
            ArtistArtworkBackfillSession.run({ stamp == entered }) {
                queries++
                ArtistArtworkBackfillSession.Page(64, 10, false)
            }
        } catch (_: CancellationException) { cancelled = true }
        assertTrue(cancelled)
        assertEquals(0, queries)
    }

}
