package org.michimusic.link.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class QueueReorderRequest(
    @SerialName("from_index") val fromIndex: Int,
    @SerialName("to_index") val toIndex: Int,
)

@Serializable
data class QueueSaveRequest(
    val name: String,
    @SerialName("track_ids") val trackIds: List<String>,
)

@Serializable
data class SavedQueueDto(
    val id: String,
    val name: String,
    @SerialName("track_count") val trackCount: Int = 0,
    @SerialName("saved_at") val savedAt: String = "",
)

@Serializable
data class QueueTransferRequest(
    @SerialName("track_ids") val trackIds: List<String>,
    @SerialName("current_index") val currentIndex: Int,
    @SerialName("position_ms") val positionMs: Long,
    val source: String,
    @SerialName("queue_id") val queueId: String? = null,
)

@Serializable
data class QueueTransferResponse(
    val success: Boolean = false,
    @SerialName("session_id") val sessionId: String = "",
)
