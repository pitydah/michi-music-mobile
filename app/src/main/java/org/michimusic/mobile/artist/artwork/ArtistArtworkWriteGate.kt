package org.michimusic.mobile.artist.artwork

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Process-local mutual exclusion between preference opt-out and a Room auto-write.
 * This is NOT the authoritative Room identity guard: it protects only preference
 * changes initiated in the same Android process as the WorkManager worker.
 */
internal object ArtistArtworkWriteGate {
    private val mutex = Mutex()
    suspend fun <T> guarding(block: suspend () -> T): T = mutex.withLock { block() }
}
