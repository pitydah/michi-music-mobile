package org.michimusic.mobile.playback

import java.util.UUID

enum class RemoteCommandStatus {
    PENDING,
    ACCEPTED,
    CONFIRMED,
    REJECTED,
    TIMED_OUT,
    UNSUPPORTED,
    DISCONNECTED,
    FAILED,
}

data class RemoteCommandRecord(
    val commandId: String = UUID.randomUUID().toString(),
    val endpointId: String,
    val sessionId: String? = null,
    val action: String,
    val param: String? = null,
    val status: RemoteCommandStatus = RemoteCommandStatus.PENDING,
    val resultMessage: String? = null,
    val timestampMs: Long = System.currentTimeMillis()
)

sealed interface QueuePlaybackOutcome {
    val operationId: String
    val endpointId: String
    val requestedTrackCount: Int
    val requestedIndex: Int

    data class Confirmed(
        override val operationId: String,
        override val endpointId: String,
        override val requestedTrackCount: Int,
        override val requestedIndex: Int,
        val message: String = "Reproducción confirmada"
    ) : QueuePlaybackOutcome

    data class Accepted(
        override val operationId: String,
        override val endpointId: String,
        override val requestedTrackCount: Int,
        override val requestedIndex: Int,
        val message: String = "Orden recibida; sincronización pendiente"
    ) : QueuePlaybackOutcome

    data class Rejected(
        override val operationId: String,
        override val endpointId: String,
        override val requestedTrackCount: Int,
        override val requestedIndex: Int,
        val reason: String
    ) : QueuePlaybackOutcome

    data class Failed(
        override val operationId: String,
        override val endpointId: String,
        override val requestedTrackCount: Int,
        override val requestedIndex: Int,
        val error: String
    ) : QueuePlaybackOutcome

    data class Disconnected(
        override val operationId: String,
        override val endpointId: String,
        override val requestedTrackCount: Int,
        override val requestedIndex: Int,
        val reason: String = "Dispositivo no conectado"
    ) : QueuePlaybackOutcome
}

