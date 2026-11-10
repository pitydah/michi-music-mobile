package org.michimusic.mobile.ui.components

import java.util.LinkedHashMap

/**
 * Per-scope memory of already verified artist artwork selections.
 * No disk IO, image decoding, network calls or cross-library persistence.
 * Access-order eviction prevents an arbitrary SnapshotStateMap hash iteration from
 * removing a recently used portrait. Scope/revision invalidation belongs to remember().
 */
internal class MichiArtworkMemory<K, V>(private val limit: Int = 24) {
    init { require(limit > 0) { "Artwork memory limit must be positive" } }

    private val entries = LinkedHashMap<K, V>(limit, 0.75f, true)

    operator fun get(key: K): V? = entries[key]

    fun put(key: K, value: V) {
        entries[key] = value
        while (entries.size > limit) {
            val eldest = entries.keys.firstOrNull() ?: break
            entries.remove(eldest)
        }
    }

    fun remove(key: K): V? = entries.remove(key)

    fun clear() = entries.clear()

    val size: Int get() = entries.size
}

/** ALL combines independently revised libraries; never show an old portrait hint before
 * the current consensus has been resolved. Only a single, revision-scoped library can
 * safely reuse the local hint during its fresh persisted-cache lookup. */
internal fun michiArtworkHintAllowed(libraryId: String?, hasArtworkStore: Boolean): Boolean =
    libraryId != null && hasArtworkStore
