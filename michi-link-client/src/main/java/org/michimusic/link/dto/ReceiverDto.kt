package org.michimusic.link.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.michimusic.core.models.ReceiverCapabilities
import org.michimusic.core.models.ReceiverDevice
import org.michimusic.core.models.ReceiverIdentity
import org.michimusic.core.models.ReceiverRuntimeState
import org.michimusic.core.models.RoomDevice

@Serializable
data class ReceiverDto(
    val id: String = "",
    val name: String = "",
    @SerialName("device_type") val deviceType: String = "michi_stream_standard",
    val volume: Int = 50,
    @SerialName("is_active") val isActive: Boolean = false,
    @SerialName("session_id") val sessionId: String? = null,
    val online: Boolean = true,
    val paired: Boolean = true,
    val compatible: Boolean = true,
    val health: String = "HEALTHY",
    @SerialName("is_playing") val isPlaying: Boolean = false,
    @SerialName("output_connector") val outputConnector: String = "jack_3_5",
    val ip: String = "",
    @SerialName("supported_codecs") val supportedCodecs: List<String> = listOf("pcm_s16le"),
    @SerialName("sample_rates") val sampleRates: List<Int> = listOf(48000),
    @SerialName("bit_depths") val bitDepths: List<Int> = listOf(16),
    val channels: List<Int> = listOf(2),
) {
    fun toReceiverDevice(): ReceiverDevice {
        return ReceiverDevice(
            identity = ReceiverIdentity(
                id = id,
                name = name,
                deviceType = deviceType,
            ),
            capabilities = ReceiverCapabilities(
                codecs = supportedCodecs,
                sampleRates = sampleRates,
                bitDepths = bitDepths,
                channels = channels,
                outputConnectors = listOf(outputConnector),
            ),
            runtime = ReceiverRuntimeState(
                online = online,
                paired = paired,
                compatible = compatible,
                health = health,
                activeSessionId = sessionId,
                isPlaying = isPlaying || isActive,
                volume = volume,
                outputConnector = outputConnector,
                ip = ip,
            )
        )
    }
}

@Serializable
data class ReceiverSessionRequest(
    @SerialName("track_id") val trackId: String = "",
)

@Serializable
data class ReceiverVolumeRequest(
    val volume: Int,
)

@Serializable
data class RoomDto(
    val id: String = "",
    val name: String = "",
    @SerialName("receiver_count") val receiverCount: Int = 0,
    @SerialName("receiver_ids") val receiverIds: List<String> = emptyList(),
    @SerialName("is_active") val isActive: Boolean = false,
    val volume: Int = 50,
) {
    fun toRoomDevice(): RoomDevice {
        return RoomDevice(
            id = id,
            name = name,
            receiverIds = receiverIds,
            isActive = isActive,
            volume = volume,
        )
    }
}

@Serializable
data class RoomCreateRequest(
    val name: String,
    @SerialName("receiver_ids") val receiverIds: List<String> = emptyList(),
)

@Serializable
data class RoomPlayRequest(
    @SerialName("track_id") val trackId: String,
    @SerialName("position_ms") val positionMs: Long = 0L,
    @SerialName("start_playing") val startPlaying: Boolean = true,
)
