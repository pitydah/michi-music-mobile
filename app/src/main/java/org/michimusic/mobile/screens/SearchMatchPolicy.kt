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
