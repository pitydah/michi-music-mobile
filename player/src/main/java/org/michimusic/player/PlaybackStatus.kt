package org.michimusic.player

sealed interface PlaybackStatus {
    data object Idle : PlaybackStatus
    data object Preparing : PlaybackStatus
    data object Buffering : PlaybackStatus
    data object Playing : PlaybackStatus
    data object Paused : PlaybackStatus
    data object Ended : PlaybackStatus
    data class Error(
        val errorType: PlaybackErrorType,
        val message: String,
        val recoverable: Boolean = true,
        val errorCode: Int = 0,
    ) : PlaybackStatus
}

enum class PlaybackErrorType {
    DECODER_INIT_FAILED,
    CODEC_UNSUPPORTED,
    MEDIA_STORE_UNAVAILABLE,
    URI_REVOKED,
    NETWORK_TIMEOUT,
    OUTPUT_DISCONNECTED,
    UNKNOWN,
}
