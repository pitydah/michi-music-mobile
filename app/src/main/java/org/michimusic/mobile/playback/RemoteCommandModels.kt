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
    OBSOLETE,
}

data class RemoteCommandRecord(
    val commandId: String = UUID.randomUUID().toString(),
    val endpointId: String,
    val sessionId: String? = null,
    val epoch: Long = 0L,
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
    val epoch: Long get() = 0L

    data class Confirmed(
        override val operationId: String,
        override val endpointId: String,
        override val requestedTrackCount: Int,
        override val requestedIndex: Int,
        override val epoch: Long = 0L,
        val message: String = "Reproducción confirmada"
    ) : QueuePlaybackOutcome

    data class Accepted(
        override val operationId: String,
        override val endpointId: String,
        override val requestedTrackCount: Int,
        override val requestedIndex: Int,
        override val epoch: Long = 0L,
        val message: String = "Orden recibida; sincronización pendiente"
    ) : QueuePlaybackOutcome

    data class Rejected(
        override val operationId: String,
        override val endpointId: String,
        override val requestedTrackCount: Int,
        override val requestedIndex: Int,
        override val epoch: Long = 0L,
        val reason: String
    ) : QueuePlaybackOutcome

    data class Failed(
        override val operationId: String,
        override val endpointId: String,
        override val requestedTrackCount: Int,
        override val requestedIndex: Int,
        override val epoch: Long = 0L,
        val error: String
    ) : QueuePlaybackOutcome

    data class Disconnected(
        override val operationId: String,
        override val endpointId: String,
        override val requestedTrackCount: Int,
        override val requestedIndex: Int,
        override val epoch: Long = 0L,
        val reason: String = "Dispositivo no conectado"
    ) : QueuePlaybackOutcome

    data class Obsolete(
        override val operationId: String,
        override val endpointId: String,
        override val requestedTrackCount: Int,
        override val requestedIndex: Int,
        override val epoch: Long = 0L,
        val reason: String = "Operación descartada por cambio de destino o sesión"
    ) : QueuePlaybackOutcome
}

