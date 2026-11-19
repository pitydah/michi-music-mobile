package org.michimusic.mobile.ui.screens

import org.michimusic.core.models.Track
import org.michimusic.mobile.screens.searchTextMatches
import org.michimusic.mobile.screens.trackSearchMatches
import org.michimusic.mobile.screens.searchTrackIdentity

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

/** Live track guard: preserve epoch checks at caller and use identical fields to the ViewModel. */
internal fun searchTrackActionPermitted(query: String, filter: String, track: Track): Boolean =
    filter in setOf("ALL", "TRACKS", "LOSSLESS", "DOWNLOADED") &&
        trackSearchMatches(track, query)

/** Only one exact source/library/file may be played; ambiguous identities fail closed. */
internal fun safeSearchQueueTrackIndex(tracks: List<Track>, candidate: Track): Int? {
    val wanted = searchTrackIdentity(candidate)
    var resolved = -1
    tracks.forEachIndexed { index, track ->
        if (searchTrackIdentity(track) == wanted) {
            if (resolved >= 0) return null
            resolved = index
        }
    }
    return resolved.takeIf { it >= 0 }
}

/** Badge and progress state must not mark another library's identical ID as playing. */
internal fun sameSearchTrackIdentity(active: Track?, candidate: Track): Boolean =
    active != null && searchTrackIdentity(active) == searchTrackIdentity(candidate)

/** A response is usable only by the exact request that produced it (ABA-safe). */
internal fun searchSnapshotCurrent(
    resultQuery: String, resultFilter: String, resultEpoch: Long,
    query: String, filter: String, activeEpoch: Long,
): Boolean = resultEpoch >= 0L && resultEpoch == activeEpoch &&
    resultQuery == query.trim() && resultFilter == filter
