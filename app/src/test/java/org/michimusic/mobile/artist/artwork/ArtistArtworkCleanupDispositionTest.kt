package org.michimusic.mobile.artist.artwork

import org.junit.Assert.assertEquals
import org.junit.Test

/** S1-D42: never convert an unfinished durable journal into a terminal SUCCESS. */
class ArtistArtworkCleanupDispositionTest {
    private fun expect(
        expected: ArtistArtworkCleanupDisposition,
        stopped: Boolean = false,
        pending: Boolean = false,
        failed: Boolean = false,
    ) = assertEquals(expected, artistArtworkCleanupDisposition(stopped, pending, failed))

    @Test fun emptyJournalIsSuccess() = expect(ArtistArtworkCleanupDisposition.SUCCESS)
    @Test fun pendingGracePeriodIsRetry() = expect(ArtistArtworkCleanupDisposition.RETRY, pending = true)
    @Test fun pendingIoFailureIsRetry() = expect(ArtistArtworkCleanupDisposition.RETRY, pending = true, failed = true)
    @Test fun daoExceptionIsRetry() = expect(ArtistArtworkCleanupDisposition.RETRY, failed = true)
    @Test fun stoppedWorkerIsRetry() = expect(ArtistArtworkCleanupDisposition.RETRY, stopped = true)
    @Test fun stoppedWithPendingRowsIsRetry() = expect(ArtistArtworkCleanupDisposition.RETRY, stopped = true, pending = true)
    @Test fun stoppedOnExceptionIsRetry() = expect(ArtistArtworkCleanupDisposition.RETRY, stopped = true, failed = true)
    @Test fun allSignalsAreRetry() = expect(ArtistArtworkCleanupDisposition.RETRY, stopped = true, pending = true, failed = true)
    @Test fun failedEvenIfNoRowsReadIsRetry() = expect(ArtistArtworkCleanupDisposition.RETRY, failed = true, pending = false)
    @Test fun successOnlyWhenNoSignalIsSet() {
        for (s in listOf(false,true)) for (p in listOf(false,true)) for (f in listOf(false,true)) {
            val actual = artistArtworkCleanupDisposition(s,p,f)
            assertEquals(if (!s && !p && !f) ArtistArtworkCleanupDisposition.SUCCESS
                         else ArtistArtworkCleanupDisposition.RETRY, actual)
        }
    }
}
