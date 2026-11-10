package org.michimusic.data.repository

/**
 * Pure reordering policy for a manual playlist: the unresolved/remote IDs retain
 * their existing absolute slots; only currently resolved tracks are permuted.
 * Invalid, stale, ambiguous or duplicate identities fail closed.
 */
internal fun reorderedVisiblePlaylistIds(
    persistedIds: List<String>,
    currentVisibleIds: List<String>,
    fromIndex: Int,
    toIndex: Int,
): List<String>? {
    if (fromIndex !in currentVisibleIds.indices || toIndex !in currentVisibleIds.indices) return null
    val visibleSet = currentVisibleIds.toSet()
    if (visibleSet.size != currentVisibleIds.size) return null
    if (persistedIds.filter { it in visibleSet } != currentVisibleIds) return null
    val desired = currentVisibleIds.toMutableList()
    desired.add(toIndex, desired.removeAt(fromIndex))
    val cursor = desired.iterator()
    return persistedIds.map { stored -> if (stored in visibleSet) cursor.next() else stored }
}
