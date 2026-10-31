package org.michimusic.mobile.playback

import org.michimusic.core.models.Track
import org.michimusic.link.dto.PlaybackStateDto
import org.michimusic.link.dto.QueueTrackDto

enum class RemoteQueueSyncStatus {
    CONFIRMED_PLAYING,
    QUEUE_SYNCED,
    COMMAND_ACCEPTED,
    MISMATCH_COUNT,
    MISMATCH_ORDER,
    MISMATCH_ACTIVE_TRACK,
    INDEX_OUT_OF_BOUNDS,
    STATE_NOT_PLAYING,
    TELEMETRY_UNAVAILABLE,
}

data class RemoteQueueVerificationResult(
    val status: RemoteQueueSyncStatus,
    val isFullyConfirmed: Boolean,
    val note: String,
)

/**
 * Pure verifier enforcing strict identity, order, and playback state for remote queue playback.
 * Resolves K47-01: No false CONFIRMED on partial matches, extra tracks, or unconfirmed playback.
 */
object RemoteQueueConfirmation {

    fun verify(
        requestedTracks: List<Track>,
        requestedIndex: Int,
        remoteQueueTracks: List<QueueTrackDto>?,
        remotePlaybackState: PlaybackStateDto?,
    ): RemoteQueueVerificationResult {
        if (remoteQueueTracks == null || remotePlaybackState == null) {
            return RemoteQueueVerificationResult(
                status = RemoteQueueSyncStatus.TELEMETRY_UNAVAILABLE,
                isFullyConfirmed = false,
                note = "Telemetría de cola o estado no disponible en host remoto"
            )
        }

        if (requestedTracks.isEmpty()) {
            return RemoteQueueVerificationResult(
                status = RemoteQueueSyncStatus.MISMATCH_COUNT,
                isFullyConfirmed = false,
                note = "Cola solicitada vacía"
            )
        }

        val validIndex = requestedIndex.coerceIn(0, requestedTracks.lastIndex)
        val expectedTrack = requestedTracks[validIndex]

        // 1. Exact track count (no extra or missing tracks)
        if (remoteQueueTracks.size != requestedTracks.size) {
            return RemoteQueueVerificationResult(
                status = RemoteQueueSyncStatus.MISMATCH_COUNT,
                isFullyConfirmed = false,
                note = "Longitud de cola no coincide exactamente (${remoteQueueTracks.size} en host vs ${requestedTracks.size} solicitadas)"
            )
        }

        // 2. Exact track IDs in exact order
        for (i in requestedTracks.indices) {
            val reqId = requestedTracks[i].id
            val remId = remoteQueueTracks[i].trackId
            if (reqId != remId) {
                return RemoteQueueVerificationResult(
                    status = RemoteQueueSyncStatus.MISMATCH_ORDER,
                    isFullyConfirmed = false,
                    note = "Orden o identidad de cola discrepante en posición $i: esperada '$reqId', recibida '$remId'"
                )
            }
        }

        // 3. Active index bounds
        val remoteIndex = remotePlaybackState.queueIndex
        if (remoteIndex !in remoteQueueTracks.indices) {
            return RemoteQueueVerificationResult(
                status = RemoteQueueSyncStatus.INDEX_OUT_OF_BOUNDS,
                isFullyConfirmed = false,
                note = "Índice de cola remoto fuera de límites: $remoteIndex (tamaño ${remoteQueueTracks.size})"
            )
        }

        // 4. Active track identity at remoteIndex
        val remoteActiveTrack = remoteQueueTracks[remoteIndex]
        if (remoteActiveTrack.trackId != expectedTrack.id || remoteIndex != validIndex) {
            return RemoteQueueVerificationResult(
                status = RemoteQueueSyncStatus.MISMATCH_ACTIVE_TRACK,
                isFullyConfirmed = false,
                note = "Pista activa remota ('${remoteActiveTrack.trackId}' en índice $remoteIndex) no coincide con pista solicitada ('${expectedTrack.id}' en índice $validIndex)"
            )
        }

        // 5. Playing state: Must be actively playing to claim CONFIRMED_PLAYING
        if (!remotePlaybackState.playing) {
            return RemoteQueueVerificationResult(
                status = RemoteQueueSyncStatus.QUEUE_SYNCED,
                isFullyConfirmed = false,
                note = "Cola sincronizada exactamente, pero host remoto no está en reproducción (en pausa o buffering)"
            )
        }

        return RemoteQueueVerificationResult(
            status = RemoteQueueSyncStatus.CONFIRMED_PLAYING,
            isFullyConfirmed = true,
            note = "Cola y reproducción confirmadas exactamente en host remoto"
        )
    }
}
