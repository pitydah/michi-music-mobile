package org.michimusic.mobile.artist.artwork

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Serialized settings transition for every scheduler instance in this process.
 * The preference mutation, WorkManager cancellation and optional replay are one
 * critical section. A second gate (ArtistArtworkWriteGate) protects Room writes;
 * this gate MUST NOT be used inside the worker to avoid cancellation deadlocks.
 */
internal object ArtistArtworkAutoControlGate {
    private val mutex = Mutex()

    suspend fun <T> guarding(block: suspend () -> T): T = mutex.withLock { block() }
}
