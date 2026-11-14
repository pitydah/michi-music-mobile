package org.michimusic.core.intelligence

/**
 * MI-13: authority guard for local-only intelligence playback commands.
 * A remote RECEIVER is not necessarily a remote playback HOST. Its session
 * can still fall through PlaybackSessionManager.playQueue to AudioController.
 * Therefore !isRemoteActive alone is not sufficient to authorize a command.
 * This guard does no IO, no polling, and never changes Michi Control state.
 */
object MichiLocalPlaybackGate {
    const val LOCAL_ENDPOINT_ID = "local_phone"

    fun permits(
        endpointId: String,
        endpointIsLocal: Boolean,
        remoteActive: Boolean,
        handoffStable: Boolean,
    ): Boolean = endpointId == LOCAL_ENDPOINT_ID && endpointIsLocal &&
        !remoteActive && handoffStable && MichiPlaybackIntentFence.permits()
}
