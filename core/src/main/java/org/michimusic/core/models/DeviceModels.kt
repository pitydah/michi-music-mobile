package org.michimusic.core.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ReceiverIdentity(
    val id: String,
    val name: String,
    @SerialName("device_type") val deviceType: String = "michi_stream_standard",
    val firmware: String = "1.0.0",
)

@Serializable
data class ReceiverCapabilities(
    val codecs: List<String> = listOf("pcm_s16le"),
    @SerialName("sample_rates") val sampleRates: List<Int> = listOf(48000),
    @SerialName("bit_depths") val bitDepths: List<Int> = listOf(16),
    val channels: List<Int> = listOf(2),
    @SerialName("output_connectors") val outputConnectors: List<String> = listOf("jack_3_5"),
)

@Serializable
data class ReceiverRuntimeState(
    val online: Boolean = false,
    val paired: Boolean = false,
    val compatible: Boolean = true,
    val health: String = "HEALTHY",
    @SerialName("active_session_id") val activeSessionId: String? = null,
    @SerialName("is_playing") val isPlaying: Boolean = false,
    val volume: Int = 50,
    @SerialName("output_connector") val outputConnector: String = "jack_3_5",
    val ip: String = "",
    @SerialName("last_seen_epoch_ms") val lastSeenEpochMs: Long = 0L,
)

@Serializable
data class ReceiverDevice(
    val identity: ReceiverIdentity,
    val capabilities: ReceiverCapabilities = ReceiverCapabilities(),
    val runtime: ReceiverRuntimeState = ReceiverRuntimeState(),
) {
    val id: String get() = identity.id
    val name: String get() = identity.name
    val deviceType: String get() = identity.deviceType
}

enum class ReceiverStatus {
    READY,
    PLAYING,
    CONNECTING,
    RECONNECTING,
    UNAVAILABLE,
    PAIRING_REQUIRED,
    INCOMPATIBLE,
}

fun ReceiverDevice.deriveStatus(
    isConnecting: Boolean = false,
    isRecovering: Boolean = false
): ReceiverStatus {
    return when {
        !runtime.online -> ReceiverStatus.UNAVAILABLE
        !runtime.paired -> ReceiverStatus.PAIRING_REQUIRED
        !runtime.compatible -> ReceiverStatus.INCOMPATIBLE
        isRecovering -> ReceiverStatus.RECONNECTING
        isConnecting -> ReceiverStatus.CONNECTING
        runtime.activeSessionId != null && runtime.isPlaying -> ReceiverStatus.PLAYING
        else -> ReceiverStatus.READY
    }
}

@Serializable
data class RoomDevice(
    val id: String,
    val name: String,
    @SerialName("receiver_ids") val receiverIds: List<String> = emptyList(),
    @SerialName("is_active") val isActive: Boolean = false,
    val volume: Int = 50,
)

sealed interface PlaybackTarget {
    data object LocalPhone : PlaybackTarget
    data class Receiver(val receiverId: String, val name: String = "") : PlaybackTarget
    data class Room(val roomId: String, val name: String = "") : PlaybackTarget
}
