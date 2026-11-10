package org.michimusic.mobile.ui.screens

/** Queue occurrence identity is distinct from the canonical track ID. */
internal data class QueueActionSelection(
    val index: Int,
    val trackId: String,
    val orderedTrackIds: List<String>,
    val localEntryId: String?,
    val endpointId: String,
    val remote: Boolean,
)

/**
 * Reject obsolete menu actions after any queue mutation, output switch, or mode change.
 * A local QueueEntry ID distinguishes A-B-A even when its canonical track IDs repeat.
 * A remote queue does not expose per-occurrence IDs yet; its order snapshot is the
 * strongest available check, not a claim of durable identity.
 */
internal fun queueActionSelectionIsCurrent(
    selected: QueueActionSelection,
    currentTrackIds: List<String>,
    currentEntryIds: List<String>,
    currentEndpointId: String,
    remote: Boolean,
): Boolean {
    if (selected.endpointId != currentEndpointId || selected.remote != remote) return false
    if (selected.index !in currentTrackIds.indices) return false
    if (selected.orderedTrackIds != currentTrackIds) return false
    if (selected.trackId != currentTrackIds[selected.index]) return false
    if (remote) return true
    if (selected.localEntryId != null) {
        return currentEntryIds.getOrNull(selected.index) == selected.localEntryId
    }
    // Legacy/connecting local queues may not expose QueueEntry IDs yet.
    // Preserve positional playback and validate the complete ordered snapshot.
    return true
}

/** A second command must not race an unconfirmed remote queue mutation. */
internal fun queueActionsEnabled(
    remote: Boolean,
    controllerReady: Boolean,
    remoteConnected: Boolean,
    remoteCommandPending: Boolean,
): Boolean = if (remote) remoteConnected && !remoteCommandPending else controllerReady

/** Confirmation is bound to the displayed queue, not whatever endpoint is active later. */
internal data class QueueClearRequest(
    val endpointId: String,
    val remote: Boolean,
    val orderedTrackIds: List<String>,
    val localEntryIds: List<String>,
)

internal fun queueClearRequestIsCurrent(
    request: QueueClearRequest,
    currentTrackIds: List<String>,
    currentEntryIds: List<String>,
    endpointId: String,
    remote: Boolean,
): Boolean = request.endpointId == endpointId && request.remote == remote &&
    request.orderedTrackIds == currentTrackIds &&
    (remote || request.localEntryIds == currentEntryIds)
