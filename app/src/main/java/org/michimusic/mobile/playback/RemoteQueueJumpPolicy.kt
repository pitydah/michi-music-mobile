package org.michimusic.mobile.playback

/** Only an observed host queue can confirm a requested positional jump. */
internal fun remoteQueueJumpWasObserved(
    requestedIndex: Int,
    requestedTrackId: String,
    observedIndex: Int,
    observedTrackId: String?,
): Boolean = requestedIndex >= 0 && requestedIndex == observedIndex &&
    requestedTrackId == observedTrackId

/** The current host API DELETE addresses track IDs, not distinct queue-entry IDs. */
internal fun remoteQueueRemovalUnambiguous(trackIds: List<String>, index: Int): Boolean =
    index in trackIds.indices && trackIds.count { it == trackIds[index] } == 1
