package org.michimusic.mobile.artist.artwork

import org.junit.Assert.assertEquals
import org.junit.Test

internal class ArtistCleanupStartupActionTest {
    @Test fun newUserDoesNotOpenAnEmptyDatabase() = assertEquals(
        ArtistCleanupStartupAction.SKIP, artistCleanupStartupAction(false, 0))
    @Test fun noWorkForEmptyJournal() = assertEquals(
        ArtistCleanupStartupAction.SKIP, artistCleanupStartupAction(true, 0))
    @Test fun pendingJournalSchedulesRecovery() = assertEquals(
        ArtistCleanupStartupAction.ENQUEUE, artistCleanupStartupAction(true, 1))
    @Test fun morePendingRowsRemainScheduled() = assertEquals(
        ArtistCleanupStartupAction.ENQUEUE, artistCleanupStartupAction(true, 64))
    @Test fun missingDatabaseNeverCreatesOne() = assertEquals(
        ArtistCleanupStartupAction.SKIP, artistCleanupStartupAction(false, 12))
    @Test fun negativeCountNeverSchedulesWork() = assertEquals(
        ArtistCleanupStartupAction.SKIP, artistCleanupStartupAction(true, -1))
}
