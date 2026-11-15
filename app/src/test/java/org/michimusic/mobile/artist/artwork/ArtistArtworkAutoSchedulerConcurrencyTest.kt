package org.michimusic.mobile.artist.artwork

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking

/** Deterministic concurrency contract; actual WorkManager behavior requires Robolectric. */
class ArtistArtworkAutoSchedulerConcurrencyTest {
    @Test fun aLaterOptInCannotOvertakeAnOlderCancellation() = runBlocking {
        val cancellationEntered = CompletableDeferred<Unit>()
        val releaseCancellation = CompletableDeferred<Unit>()
        val log = mutableListOf<String>()
        val older = async(start = CoroutineStart.UNDISPATCHED) {
            ArtistArtworkAutoControlGate.guarding {
                log += "disable"
                cancellationEntered.complete(Unit)
                releaseCancellation.await()
                log += "cancel-completed"
            }
        }
        cancellationEntered.await()
        val newer = async(start = CoroutineStart.UNDISPATCHED) {
            ArtistArtworkAutoControlGate.guarding { log += "enable-and-replay" }
        }
        assertTrue("Enable must not cross a pending cancel", "enable-and-replay" !in log)
        releaseCancellation.complete(Unit)
        older.await()
        newer.await()
        assertTrue(log == listOf("disable", "cancel-completed", "enable-and-replay"))
    }
}
