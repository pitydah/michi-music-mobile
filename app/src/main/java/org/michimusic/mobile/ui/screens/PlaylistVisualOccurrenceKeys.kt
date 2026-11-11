package org.michimusic.mobile.ui.screens

import org.michimusic.core.models.Track

/** Prevents duplicate Compose lazy keys if external/legacy data is malformed.
 * The canonical local playlist store currently deduplicates trackId values;
 * this display-only ordinal MUST NOT be persisted as an entry identity. */
internal fun playlistVisualOccurrenceKeys(tracks: List<Track>): List<String> {
    val counts = mutableMapOf<Pair<String, String>, Int>()
    return tracks.map { track ->
        val owner = track.sourceId to track.id
        val ordinal = counts[owner] ?: 0
        counts[owner] = ordinal + 1
        // Length prefixes prevent delimiters embedded in real IDs from aliasing.
        "${owner.first.length}:${owner.first}:${owner.second.length}:${owner.second}:$ordinal"
    }
}
