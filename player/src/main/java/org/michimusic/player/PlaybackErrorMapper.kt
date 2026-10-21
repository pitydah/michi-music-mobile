package org.michimusic.player

import androidx.media3.common.PlaybackException

object PlaybackErrorMapper {
    fun mapPlaybackError(error: PlaybackException): PlaybackStatus.Error {
        val errorType = when (error.errorCode) {
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
            PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED -> PlaybackErrorType.DECODER_INIT_FAILED

            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED -> PlaybackErrorType.CODEC_UNSUPPORTED

            PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND -> PlaybackErrorType.MEDIA_STORE_UNAVAILABLE

            PlaybackException.ERROR_CODE_IO_NO_PERMISSION -> PlaybackErrorType.URI_REVOKED

            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> PlaybackErrorType.NETWORK_TIMEOUT

            PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED,
            PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED -> PlaybackErrorType.OUTPUT_DISCONNECTED

            else -> PlaybackErrorType.UNKNOWN
        }

        val recoverable = when (errorType) {
            PlaybackErrorType.CODEC_UNSUPPORTED,
            PlaybackErrorType.URI_REVOKED,
            PlaybackErrorType.MEDIA_STORE_UNAVAILABLE -> false
            else -> true
        }

        return PlaybackStatus.Error(
            errorType = errorType,
            message = error.message ?: "Error de reproducción (${error.errorCode})",
            recoverable = recoverable,
            errorCode = error.errorCode,
        )
    }
}
