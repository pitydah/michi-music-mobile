package org.michimusic.link.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class PlaybackStateDto(
    val state: String = "stopped",
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    @SerialName("cover_id") val coverId: String = "",
    @SerialName("cover_url") val coverUrl: String = "",
    val duration: Long = 0L,
    val position: Long = 0L,
    val volume: Int = 50,
    val muted: Boolean = false,
    @SerialName("track_id") val trackId: String = "",
    @SerialName("queue_index") val queueIndex: Int = -1,
    @SerialName("queue_size") val queueSize: Int = 0,
    @SerialName("source_name") val sourceName: String = "",
    @SerialName("output_name") val outputName: String = "",
    @SerialName("device_id") val deviceId: String = "",
    @SerialName("is_local") val isLocal: Boolean = false,
)

@Serializable
data class PlaybackControlRequestDto(
    val command: String,
    val value: String = "",
)
