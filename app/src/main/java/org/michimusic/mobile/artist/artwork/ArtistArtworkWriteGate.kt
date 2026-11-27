package org.michimusic.mobile.artist.artwork

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * D17 — short process-local serialization of confirmed SAF root commits, automatic
 * opt-out, manual photo CAS, framing CAS, and automatic Room artwork writes.
 * Provider requests and downloads MUST remain outside the gate.
 * This is not a cross-process lock or a substitute for Room identity/binding CAS.
 */
internal object ArtistArtworkWriteGate {
    private val mutex = Mutex()
    suspend fun <T> guarding(block: suspend () -> T): T = mutex.withLock { block() }
}
