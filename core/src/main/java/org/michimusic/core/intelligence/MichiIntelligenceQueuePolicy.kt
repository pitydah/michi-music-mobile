package org.michimusic.core.intelligence

import org.michimusic.core.models.Track
import org.michimusic.core.models.TrackSource

/**
 * MI-16: last-mile policy for a recommendation queue, not the generic player.
 * A UI-only eligibility check is NOT authority: Media3/Michi Control must
 * recheck the originally issued session receipt right at command dispatch.
 * Bounded to six locally owned tracks; no MediaStore, Room, network or worker.
 */
object MichiIntelligenceQueuePolicy {
    const val MAX_QUEUE = 6

    fun permits(
        tracks: List<Track>,
        startIndex: Int,
        expectedSessionEpoch: Long,
        actualSessionEpoch: Long,
        expectedIntentGeneration: Long,
        expectedOutputRevision: Long,
        actualOutputRevision: Long,
        sessionEndpointId: String,
        sessionEndpointLocal: Boolean,
        sessionRemoteActive: Boolean,
        handoffStable: Boolean,
        remoteSelected: Boolean,
        outputEndpointId: String,
        outputLocal: Boolean,
    ): Boolean {
        if (tracks.isEmpty() || tracks.size > MAX_QUEUE ||
            startIndex !in tracks.indices || expectedSessionEpoch != actualSessionEpoch ||
            expectedOutputRevision != actualOutputRevision ||
            !MichiPlaybackIntentFence.permits(expectedIntentGeneration) ||
            remoteSelected || !outputLocal ||
            outputEndpointId != MichiLocalPlaybackGate.LOCAL_ENDPOINT_ID ||
            !MichiLocalPlaybackGate.permits(
                sessionEndpointId, sessionEndpointLocal, sessionRemoteActive, handoffStable)) return false
        val ids = HashSet<String>(tracks.size)
        for (track in tracks) {
            if (track.source != TrackSource.LOCAL || track.id.isBlank() ||
                track.id.length > 1024 || track.sourceId.length > 1024 ||
                track.filepath.isBlank() || !ids.add(track.id)) return false
        }
        return true
    }
}
