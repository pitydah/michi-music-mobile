package org.michimusic.link.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class PlaybackStateDto(
    val state: String = "stopped",
    val playing: Boolean = false,
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    @SerialName("cover_id") val coverId: String = "",
    @SerialName("cover_url") val coverUrl: String = "",
    val duration: Long = 0L,
    @SerialName("duration_ms") val durationMs: Long = -1L,
    val position: Long = 0L,
    @SerialName("position_ms") val positionMs: Long = -1L,
    val volume: Int = 50,
    val muted: Boolean = false,
    val shuffle: Boolean = false,
    val repeat: String = "",
    @SerialName("track_id") val trackId: String = "",
    @SerialName("current_track") val currentTrack: CurrentTrackDto? = null,
    @SerialName("queue_index") val queueIndex: Int = -1,
    @SerialName("queue_size") val queueSize: Int = 0,
    @SerialName("source_name") val sourceName: String = "",
    @SerialName("output_name") val outputName: String = "",
    @SerialName("device_id") val deviceId: String = "",
    @SerialName("is_local") val isLocal: Boolean = false,
    @SerialName("updated_at") val updatedAt: String = "",
) {
    val effectiveState: String get() {
        if (state != "stopped") return state
        return if (playing) "playing" else "paused"
    }
    val effectiveTitle: String get() = title.ifEmpty { currentTrack?.title ?: "" }
    val effectiveArtist: String get() = artist.ifEmpty { currentTrack?.artist ?: "" }
    val effectivePosition: Long get() = if (positionMs >= 0) positionMs else position
    val effectiveDuration: Long get() = if (durationMs >= 0) durationMs else duration
    val effectiveVolume: Int get() = volume.coerceIn(0, 100)
    val effectiveCoverId: String get() = coverId.ifEmpty { currentTrack?.coverId ?: "" }
}

@Serializable
data class CurrentTrackDto(
    val id: String = "",
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val duration: Long = 0L,
    @SerialName("cover_id") val coverId: String = "",
)

@Serializable
data class PlaybackControlRequestDto(
    val command: String,
    val value: String = "",
    @SerialName("position_ms") val positionMs: Long? = null,
    val volume: Int? = null,
)
