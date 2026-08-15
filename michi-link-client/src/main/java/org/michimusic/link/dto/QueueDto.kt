package org.michimusic.link.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class QueueDto(
    val tracks: List<QueueTrackDto> = emptyList(),
    @SerialName("current_index") val currentIndex: Int = -1,
    @SerialName("total_duration") val totalDuration: Long = 0L,
    @SerialName("queue_id") val queueId: String = "",
)

@Serializable
data class QueueTrackDto(
    @SerialName("track_id") val trackId: String,
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val duration: Long = 0L,
    @SerialName("cover_id") val coverId: String = "",
    @SerialName("download_path") val downloadPath: String = "",
)

@Serializable
data class QueueJumpRequestDto(
    val index: Int,
)

@Serializable
data class QueueRepeatRequestDto(
    val mode: String, // "off", "one", "all"
)

@Serializable
data class QueueRepeatResponseDto(
    @SerialName("repeat_mode") val repeatMode: String,
)

@Serializable
data class QueueShuffleRequestDto(
    val enabled: Boolean,
)

@Serializable
data class QueueShuffleResponseDto(
    @SerialName("shuffle_enabled") val shuffleEnabled: Boolean,
)
