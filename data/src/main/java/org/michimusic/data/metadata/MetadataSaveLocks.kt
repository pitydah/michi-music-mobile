package org.michimusic.data.metadata

import kotlinx.coroutines.sync.Mutex

/**
 * F4/P1-1: the journal is single and app-wide, so there is one process-wide
 * mutation lease. It is never removed from the map (no ABA) and it is shared
 * by every source identity, including URI aliases and different track ids.
 */
internal object MetadataSaveLocks {
    private val gate = Mutex()

    fun key(sourceId: String, trackId: String, uri: String): String = "metadata-journal"

    fun lock(key: String): Mutex = gate

    fun release(key: String, mutex: Mutex) = Unit

    suspend fun <T> tryRun(onBusy: () -> T, block: suspend () -> T): T {
        if (!gate.tryLock()) return onBusy()
        return try { block() } finally { gate.unlock() }
    }
}
