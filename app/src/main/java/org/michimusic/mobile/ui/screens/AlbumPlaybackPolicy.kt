package org.michimusic.mobile.ui.screens

import org.michimusic.core.models.Track

/** Never substitute a different track if the requested identity is unavailable. */
internal data class AlbumQueueSelection(val tracks: List<Track>, val startIndex: Int)

/** Shared by album CTA, shuffle and track tap; only currently playable IDs enter the queue. */
internal fun albumQueueSelection(
    orderedTracks: List<Track>,
    playableTrackIds: Set<String>,
    requestedTrackId: String? = null,
    shuffle: Boolean = false,
): AlbumQueueSelection? {
    val available = orderedTracks.distinctBy { it.id }
        .filter { it.id in playableTrackIds }
    if (available.isEmpty()) return null
    // Shuffle is an ALBUM action, independent of whether track 1 is available.
    if (shuffle) return AlbumQueueSelection(available.shuffled(), 0)
    val start = if (requestedTrackId == null) 0 else available.indexOfFirst { it.id == requestedTrackId }
    return if (start >= 0) AlbumQueueSelection(available, start) else null
}
