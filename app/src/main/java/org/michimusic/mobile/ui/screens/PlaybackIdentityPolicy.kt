package org.michimusic.mobile.ui.screens

import org.michimusic.core.models.Track

/**
 * Media3 repeat values, mirrored here so the UI can reason about them without depending on
 * the player module's Media3 types. Local state copies `Player.repeatMode` verbatim and the
 * remote mapping in `PlaybackSessionManager.applyRemoteState` uses the same numbers
 * ("one" -> 1, "all" -> 2), so both sides agree.
 */
internal const val REPEAT_MODE_OFF = 0
internal const val REPEAT_MODE_ONE = 1
internal const val REPEAT_MODE_ALL = 2

/**
 * Queue position the UI is allowed to act on.
 *
 * A queue may legitimately hold the same song twice, so the position reported by the player is
 * the only identity that tells the two entries apart. Returning null instead of 0 keeps a stale
 * or unknown index from silently playing the first song in the list.
 */
internal fun resolvedQueueIndex(queueSize: Int, reportedIndex: Int): Int? =
    reportedIndex.takeIf { it in 0 until queueSize }

/** Position of [trackId] inside [tracks], or null when it already left that list. */
internal fun indexOfTrackOrNull(tracks: List<Track>, trackId: String): Int? =
    tracks.indexOfFirst { it.id == trackId }.takeIf { it >= 0 }

/**
 * Track the player will advance to, or null when that cannot be promised.
 *
 * Shuffle picks the next entry at random, so announcing one would be a guess; repeat-one repeats
 * the current entry, and repeat-all wraps around to the first one.
 */
internal fun nextQueueTrack(
    queue: List<Track>,
    queueIndex: Int,
    repeatMode: Int,
    shuffleEnabled: Boolean,
): Track? {
    if (shuffleEnabled) return null
    val index = resolvedQueueIndex(queue.size, queueIndex) ?: return null
    if (repeatMode == REPEAT_MODE_ONE) return queue[index]
    queue.getOrNull(index + 1)?.let { return it }
    return if (repeatMode == REPEAT_MODE_ALL) queue.firstOrNull() else null
}

/**
 * Keeps the CoverFlow selection pointing at an album that still exists.
 *
 * The selected album is remembered across library changes, so a rescan that removes it would
 * leave the carousel and the detail panel showing different albums. Falling back to the first
 * album keeps both views on the same object.
 */
internal fun reconcileSelectedAlbumId(selectedId: String?, albumIds: List<String>): String? = when {
    albumIds.isEmpty() -> null
    selectedId != null && selectedId in albumIds -> selectedId
    else -> albumIds.first()
}
