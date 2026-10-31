package org.michimusic.mobile.playback

import org.michimusic.core.models.Track

/**
 * User-selected or pre-approved action when modifying an active remote playback queue (K49-03).
 */
enum class RemoteQueueAction {
    REPLACE,
    APPEND,
    CANCEL,
}

/**
 * Cryptographically unique, time-bounded authorization ticket issued during preflight (K49B-04).
 * Prevents race conditions, concurrent drift, and stale authorization replays.
 */
data class RemoteQueueAuthorizationTicket(
    val ticketId: String = java.util.UUID.randomUUID().toString(),
    val endpointId: String,
    val sessionEpoch: Long,
    val snapshotTrackCount: Int,
    val snapshotTrackFingerprint: String? = null,
    val createdAtMs: Long = System.currentTimeMillis(),
    val validDurationMs: Long = 60_000L,
) {
    val isExpired: Boolean get() = (System.currentTimeMillis() - createdAtMs) > validDurationMs
}

/**
 * Pre-dispatch authorization evaluation state for remote queue mutations.
 */
sealed interface RemoteQueuePreflightDecision {
    /** Target destination has no active queue or matches proposed queue; safe to proceed immediately. */
    data class ProceedDirectly(
        val action: RemoteQueueAction = RemoteQueueAction.REPLACE
    ) : RemoteQueuePreflightDecision

    /**
     * Target destination has an active queue or is currently playing.
     * User MUST explicitly choose to REPLACE, APPEND, or CANCEL before any network mutations occur.
     */
    data class ConfirmationRequired(
        val destinationId: String,
        val destinationName: String,
        val remoteActiveTrackTitle: String?,
        val remoteQueueSize: Int,
        val proposedTracks: List<Track>,
        val proposedStartIndex: Int,
        val ticket: RemoteQueueAuthorizationTicket = RemoteQueueAuthorizationTicket(
            endpointId = destinationId,
            sessionEpoch = 0L,
            snapshotTrackCount = remoteQueueSize,
            snapshotTrackFingerprint = remoteActiveTrackTitle,
        ),
    ) : RemoteQueuePreflightDecision

    /** Operation rejected before preflight (e.g. empty queue). */
    data class Rejected(val reason: String) : RemoteQueuePreflightDecision
}

/**
 * Domain policy evaluating whether an impending remote queue dispatch requires
 * interactive user consent prior to issuing destructive REPLACE commands (K49-03, K49B-04).
 */
object RemoteQueuePreflightPolicy {

    fun evaluate(
        destinationId: String,
        destinationName: String,
        remoteQueueSize: Int,
        remoteActiveTrackTitle: String?,
        remotePlaying: Boolean,
        proposedTracks: List<Track>,
        proposedStartIndex: Int,
        sessionEpoch: Long = 0L,
        snapshotFingerprint: String? = null,
    ): RemoteQueuePreflightDecision {
        if (proposedTracks.isEmpty()) {
            return RemoteQueuePreflightDecision.Rejected("La cola solicitada no contiene pistas")
        }

        // If the target host has an active queue (> 0 tracks) or is actively playing,
        // an unprompted REPLACE would destroy existing listening state. Require explicit confirmation!
        if (remoteQueueSize > 0 || remotePlaying) {
            val ticket = RemoteQueueAuthorizationTicket(
                endpointId = destinationId,
                sessionEpoch = sessionEpoch,
                snapshotTrackCount = remoteQueueSize,
                snapshotTrackFingerprint = snapshotFingerprint ?: remoteActiveTrackTitle,
            )
            return RemoteQueuePreflightDecision.ConfirmationRequired(
                destinationId = destinationId,
                destinationName = destinationName,
                remoteActiveTrackTitle = remoteActiveTrackTitle,
                remoteQueueSize = remoteQueueSize,
                proposedTracks = proposedTracks,
                proposedStartIndex = proposedStartIndex.coerceIn(0, proposedTracks.lastIndex),
                ticket = ticket,
            )
        }

        // Clean slate on remote host: proceed directly
        return RemoteQueuePreflightDecision.ProceedDirectly(RemoteQueueAction.REPLACE)
    }
}
