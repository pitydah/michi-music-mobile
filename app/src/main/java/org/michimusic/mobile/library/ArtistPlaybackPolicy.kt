package org.michimusic.mobile.library

import org.michimusic.core.models.Track

/** An identity-based queue receipt. Never coerce a missing track index to zero. */
data class ArtistQueueSelection(val tracks: List<Track>, val index: Int)

/**
 * A bounded, ID-exact queue containing ONLY tracks currently eligible according
 * to the scoped repository. Offline siblings are skipped, never passed to Media3.
 * A missing or ineligible clicked ID MUST NOT start the first other song.
 */
fun artistQueueSelection(
    tracks: List<Track>,
    selectedTrackId: String,
    playableTrackIds: Set<String>,
): ArtistQueueSelection? {
    if (selectedTrackId.isBlank() || selectedTrackId !in playableTrackIds) return null
    val queue = artistPlayableQueue(tracks, playableTrackIds)
    return queue.indexOfFirst { it.id == selectedTrackId }
        .takeIf { it >= 0 }?.let { index -> ArtistQueueSelection(queue, index) }
}

/** Preserve album/artist order and track IDs, removing only explicitly unavailable items. */
fun artistPlayableQueue(tracks: List<Track>, playableTrackIds: Set<String>): List<Track> =
    tracks.asSequence().filter { it.id in playableTrackIds }
        .distinctBy { it.id }.toList()

/** Same exact-id policy for album first-track and play-all entry points. */
fun artistFirstPlayableSelection(
    tracks: List<Track>, playableTrackIds: Set<String>,
): ArtistQueueSelection? = artistPlayableQueue(tracks, playableTrackIds)
    .takeIf { it.isNotEmpty() }?.let { ArtistQueueSelection(it, 0) }
