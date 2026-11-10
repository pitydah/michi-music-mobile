package org.michimusic.mobile.ui.screens

import org.michimusic.mobile.screens.searchTextMatches

/** Only an index that belongs to the exact current result list may be played. */
internal fun safeSearchQueueIndex(candidate: Int, count: Int): Int? =
    candidate.takeIf { count > 0 && it >= 0 && it < count }

/** Resolve identity against the current queue, not a captured dialog snapshot. */
internal fun safeSearchQueueTrackIdIndex(ids: List<String>, selectedId: String): Int? {
    if (selectedId.isBlank()) return null
    val first = ids.indexOf(selectedId)
    // Duplicate IDs from different sources are ambiguous; never guess the first.
    if (first < 0 || ids.lastIndexOf(selectedId) != first) return null
    return safeSearchQueueIndex(first, ids.size)
}

/** Live query guard: old results remain visible during SearchViewModel's debounce. */
internal fun searchActionPermitted(query: String, filter: String, kind: String, matchedText: String): Boolean {
    // The ViewModel and click guard MUST share Unicode normalization. Trailing
    // whitespace is normalized rather than rejecting an otherwise valid query.
    if (!searchTextMatches(matchedText, query)) return false
    return when (kind) {
        "ARTIST" -> filter == "ALL" || filter == "ARTISTS"
        "ALBUM" -> filter == "ALL" || filter == "ALBUMS"
        "TRACK" -> filter in setOf("ALL", "TRACKS", "LOSSLESS", "DOWNLOADED")
        else -> false
    }
}

/** A response is usable only by the exact request that produced it (ABA-safe). */
internal fun searchSnapshotCurrent(
    resultQuery: String, resultFilter: String, resultEpoch: Long,
    query: String, filter: String, activeEpoch: Long,
): Boolean = resultEpoch >= 0L && resultEpoch == activeEpoch &&
    resultQuery == query.trim() && resultFilter == filter
