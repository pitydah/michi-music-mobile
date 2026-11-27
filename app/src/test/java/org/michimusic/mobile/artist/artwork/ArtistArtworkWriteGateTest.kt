package org.michimusic.mobile.artist.artwork

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** D17: a SAF preference commit cannot pass a manual Room commit in the same process. */
class ArtistArtworkWriteGateTest {
    @Test fun rootChangeWaitsUntilInFlightRoomWriteCompletes() = runBlocking {
        withTimeout(5_000) {
            val enteredWrite = CompletableDeferred<Unit>()
            val finishWrite = CompletableDeferred<Unit>()
            val startedRootChange = CompletableDeferred<Unit>()
            val enteredRootChange = CompletableDeferred<Unit>()
            val writer = async(Dispatchers.Default) {
                ArtistArtworkWriteGate.guarding {
                    enteredWrite.complete(Unit)
                    finishWrite.await()
                }
            }
            enteredWrite.await()
            val rootChange = async(Dispatchers.Default) {
                startedRootChange.complete(Unit)
                ArtistArtworkWriteGate.guarding { enteredRootChange.complete(Unit) }
            }
            startedRootChange.await()
            assertFalse(enteredRootChange.isCompleted)
            finishWrite.complete(Unit)
            writer.await()
            rootChange.await()
            assertTrue(enteredRootChange.isCompleted)
        }
    }

    @Test fun failedWriteReleasesTheRootCommitGate() = runBlocking {
        val failure = runCatching {
            ArtistArtworkWriteGate.guarding<Unit> { error("forced write failure") }
        }
        assertTrue(failure.isFailure)
        assertTrue(ArtistArtworkWriteGate.guarding { true })
    }
}
