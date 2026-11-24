package org.michimusic.data.metadata

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Mutex

/**
 * ME-13 D/T15: one process-wide scope per exact source identity. Two service
 * instances cannot both mutate the same provider item; the second call is
 * refused before any backup, journal transition or destructive open.
 */
internal object MetadataSaveLocks {
    private val locks = ConcurrentHashMap<String, Mutex>()

    fun key(sourceId: String, trackId: String, uri: String): String =
        "$sourceId\u0000$trackId\u0000$uri"

    fun lock(key: String): Mutex = locks.computeIfAbsent(key) { Mutex() }

    fun release(key: String, mutex: Mutex) {
        if (mutex.isLocked) return
        locks.remove(key, mutex)
    }
}
