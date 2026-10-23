package org.michimusic.mobile.playback

import java.util.UUID

enum class RemoteCommandStatus {
    PENDING,
    ACCEPTED,
    CONFIRMED,
    REJECTED,
    TIMED_OUT,
    UNSUPPORTED,
    DISCONNECTED
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
