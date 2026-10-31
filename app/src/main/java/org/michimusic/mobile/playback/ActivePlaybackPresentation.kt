package org.michimusic.mobile.playback

import org.michimusic.core.models.Track
import org.michimusic.player.PlayerState

data class ActivePlaybackPresentation(
    val currentTrack: Track?,
    val isPlaying: Boolean,
    val position: Long,
    val duration: Long,
    val queue: List<Track>,
    val queueIndex: Int,
    val repeatMode: Int,
    val shuffleMode: Int,
    val endpoint: PlaybackEndpoint,
    val isRemoteActive: Boolean,
    val isStreamReceiver: Boolean,
    val endpointDescription: String,
    val statusMessage: String? = null,
) {
    val hasActiveTrack: Boolean get() = currentTrack != null

    val progressFraction: Float
        get() = if (duration > 0) (position.toFloat() / duration.toFloat()).coerceIn(0f, 1f) else 0f
}

/**
 * Pure, canonical mapper: establishes the Single Source of Truth for playback presentation (K46D-49).
 *
 * Rules:
 * 1. Authority 1: Remote Host (`isRemoteActive` && not STREAM_RECEIVER):
 *    - Strictly consume remote track and state from [sessionState].
 *    - NEVER fall back to [localState.currentTrack] or phone player state.
 *    - If remote host is not playing or has no track, reports `currentTrack = sessionState.currentTrack` (which may be null).
 * 2. Authority 2: Michi Stream (`isRemoteActive` && STREAM_RECEIVER):
 *    - Mobile decoder is the audio source, phone speaker muted.
 *    - Consume local player engine state ([localState]), but destination is explicitly labeled with receiver name.
 * 3. Authority 3: Local Phone:
 *    - Consume [sessionState] or [localState] directly.
 */
fun resolvePlaybackPresentation(
    sessionState: PlaybackSessionState,
    localState: PlayerState?
): ActivePlaybackPresentation {
    val activeEp = sessionState.activeEndpoint
    val isStream = sessionState.isRemoteActive &&
        !activeEp.isLocal &&
        (activeEp.type == EndpointType.STREAM_RECEIVER || activeEp.capabilities.contains("AUDIO_OUTPUT"))

    return when {
        isStream -> {
            // Michi Stream ReceiverLite
            val track = sessionState.currentTrack ?: localState?.currentTrack
            ActivePlaybackPresentation(
                currentTrack = track,
                isPlaying = sessionState.isPlaying || (localState?.isPlaying == true),
                position = sessionState.position.takeIf { it > 0 } ?: (localState?.position ?: 0L),
                duration = sessionState.duration.takeIf { it > 0 } ?: (localState?.duration ?: 0L),
                queue = if (sessionState.queue.isNotEmpty()) sessionState.queue else (localState?.queue ?: emptyList()),
                queueIndex = if (sessionState.queueIndex >= 0) sessionState.queueIndex else (localState?.queueIndex ?: -1),
                repeatMode = sessionState.repeatMode,
                shuffleMode = sessionState.shuffleMode,
                endpoint = activeEp,
                isRemoteActive = true,
                isStreamReceiver = true,
                endpointDescription = activeEp.name,
                statusMessage = sessionState.statusMessage ?: "Transmitiendo a ${activeEp.name} (altavoz local silenciado)"
            )
        }
        sessionState.isRemoteActive -> {
            // Remote Playback Host (Desktop Player / Server) - STRICT REMOTE AUTHORITY (no fallback to phone track)
            ActivePlaybackPresentation(
                currentTrack = sessionState.currentTrack,
                isPlaying = sessionState.isPlaying,
                position = sessionState.position,
                duration = sessionState.duration,
                queue = sessionState.queue,
                queueIndex = sessionState.queueIndex,
                repeatMode = sessionState.repeatMode,
                shuffleMode = sessionState.shuffleMode,
                endpoint = activeEp,
                isRemoteActive = true,
                isStreamReceiver = false,
                endpointDescription = activeEp.name,
                statusMessage = sessionState.statusMessage ?: "Controlando ${activeEp.name}"
            )
        }
        else -> {
            // Local Phone
            val track = sessionState.currentTrack ?: localState?.currentTrack
            ActivePlaybackPresentation(
                currentTrack = track,
                isPlaying = localState?.isPlaying ?: sessionState.isPlaying,
                position = sessionState.position.takeIf { it > 0 } ?: (localState?.position ?: 0L),
                duration = sessionState.duration.takeIf { it > 0 } ?: (localState?.duration ?: 0L),
                queue = if (sessionState.queue.isNotEmpty()) sessionState.queue else (localState?.queue ?: emptyList()),
                queueIndex = if (sessionState.queueIndex >= 0) sessionState.queueIndex else (localState?.queueIndex ?: -1),
                repeatMode = sessionState.repeatMode,
                shuffleMode = sessionState.shuffleMode,
                endpoint = PlaybackEndpoint.LocalPhone,
                isRemoteActive = false,
                isStreamReceiver = false,
                endpointDescription = "Este teléfono",
                statusMessage = sessionState.statusMessage
            )
        }
    }
}
