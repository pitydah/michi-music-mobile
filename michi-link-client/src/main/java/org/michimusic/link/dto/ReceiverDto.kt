package org.michimusic.link.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ReceiverDto(
    val id: String = "",
    val name: String = "",
    @SerialName("device_type") val deviceType: String = "",
    val volume: Int = 50,
    @SerialName("is_active") val isActive: Boolean = false,
    @SerialName("session_id") val sessionId: String? = null,
)

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
    @SerialName("is_active") val isActive: Boolean = false,
)

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
