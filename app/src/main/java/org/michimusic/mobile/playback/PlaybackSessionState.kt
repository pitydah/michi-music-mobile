package org.michimusic.mobile.playback

import org.michimusic.core.models.Track

enum class EndpointType {
    LOCAL_PHONE,
    DESKTOP_PLAYER,
    SERVER,
    STREAM_RECEIVER,
    ROOM,
    UNKNOWN,
}

enum class StreamErrorReason {
    FORMAT_UNSUPPORTED,
    RECEIVER_NEGOTIATION_FAILED,
    RTP_START_FAILED,
    SESSION_EXPIRED,
    NETWORK_LOST,
}

data class PlaybackEndpoint(
    val id: String,
    val name: String,
    val type: EndpointType,
    val isLocal: Boolean = false,
    val isConnected: Boolean = false,
    val capabilities: Set<String> = emptySet(),
) {
    companion object {
        val LocalPhone = PlaybackEndpoint(
            id = "local_phone",
            name = "Este teléfono",
            type = EndpointType.LOCAL_PHONE,
            isLocal = true,
            isConnected = true,
            capabilities = setOf("PLAYBACK", "LOCAL_OUTPUT"),
        )
    }
}

data class PlaybackSessionState(
    val activeEndpoint: PlaybackEndpoint = PlaybackEndpoint.LocalPhone,
    val availableEndpoints: List<PlaybackEndpoint> = listOf(PlaybackEndpoint.LocalPhone),
    val currentTrack: Track? = null,
    val isPlaying: Boolean = false,
    val position: Long = 0L,
    val duration: Long = 0L,
    val queue: List<Track> = emptyList(),
    val queueIndex: Int = -1,
    val repeatMode: Int = 0, // 0 = off, 1 = all, 2 = one
    val shuffleMode: Int = 0, // 0 = off, 1 = all
    val isRemoteActive: Boolean = false,
    val remoteVolume: Int = 50,
    val statusMessage: String? = null,
    val lastSessionError: StreamErrorReason? = null,
)
