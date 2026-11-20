package org.michimusic.mobile.screens

import org.michimusic.core.models.Track

/**
 * Query predicates shared by the search pipeline and the PERF-05 harness.
 *
 * Extracted from `SearchViewModel.executeSearch`, where five branches repeated the same
 * `title.lowercase().contains(lower)` expression. Keeping one definition means the cost
 * measured by the harness is the cost the production path actually pays, and that a
 * future change to the matching rule lands in exactly one place.
 */
internal fun trackTitleMatches(track: Track, lowerQuery: String): Boolean =
    searchTextMatches(track.title, lowerQuery)

/** Album membership uses the album name, mirroring the album grouping in the view model. */
internal fun trackAlbumMatches(track: Track, lowerQuery: String): Boolean =
    searchTextMatches(track.album, lowerQuery)

/** One semantic predicate for result production and the live click guard. */
internal fun trackSearchMatches(track: Track, query: String): Boolean {
    val needle = normalizedSearchText(query)
    if (needle.length < 2) return false
    return normalizedSearchText(track.title).contains(needle) ||
        normalizedSearchText(track.artist).contains(needle) ||
        normalizedSearchText(track.album).contains(needle)
}

/** A title match outranks an artist match, which outranks an album match. */
internal fun trackSearchRank(track: Track, normalizedQuery: String): Int {
    fun boosted(rank: Int, bonus: Int) = if (rank == Int.MAX_VALUE) rank else rank + bonus
    return minOf(
        searchMatchRank(track.title, normalizedQuery),
        boosted(searchMatchRank(track.artist, normalizedQuery), 4),
        boosted(searchMatchRank(track.album, normalizedQuery), 8),
    )
}

/** Group albums by structured identity: separators may occur in real tags. */
internal fun groupSearchAlbums(tracks: List<Track>): Map<Pair<String, String>, List<Track>> =
    tracks.groupBy { it.album to it.artist }
