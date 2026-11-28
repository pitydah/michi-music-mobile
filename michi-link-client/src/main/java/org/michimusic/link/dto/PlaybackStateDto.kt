package org.michimusic.link.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class PlaybackStateDto(
    val state: String = "",
    val playing: Boolean = false,
    /** Server engine lifecycle; authoritative when present. */
    val lifecycle: String = "",
    @SerialName("generation_id") val generationId: Long = 0L,
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
    @SerialName("queue_id") val queueId: String = "",
    @SerialName("last_error") val lastError: String? = null,
    val restored: Boolean = false,
) {
    /**
     * MIM-08: the producer sends `state=playing|paused` plus a richer
     * `lifecycle`. Never turn an explicit stopped/ended engine into "paused";
     * only infer when the server omitted both fields.
     */
    val effectiveState: String get() = when (lifecycle.trim().lowercase()) {
        "audio_flowing", "playing" -> "playing"
        "paused" -> "paused"
        "stopped", "stopping", "idle" -> "stopped"
        "ended" -> "ended"
        "preparing" -> "preparing"
        "buffering" -> "buffering"
        "failed" -> "error"
        "" -> when (state.trim().lowercase()) {
            "playing", "paused", "stopped", "ended", "preparing", "buffering", "error" ->
                state.trim().lowercase()
            else -> if (playing) "playing" else "stopped"
        }
        else -> state.trim().lowercase().ifBlank { if (playing) "playing" else "stopped" }
    }
    val effectiveTitle: String get() = title.ifEmpty { currentTrack?.title ?: "" }
    val effectiveArtist: String get() = artist.ifEmpty { currentTrack?.artist ?: "" }
    val effectivePosition: Long get() = if (positionMs >= 0) positionMs else position
    val effectiveDuration: Long get() = if (durationMs >= 0) durationMs else duration
    val effectiveVolume: Int get() = volume.coerceIn(0, 100)
    val effectiveCoverId: String get() = coverId.ifEmpty { currentTrack?.coverId ?: "" }
    val effectiveDurationWithTrack: Long get() = effectiveDuration.takeIf { it > 0 } ?: currentTrack?.duration ?: 0L
}

@Serializable
data class CurrentTrackDto(
    @SerialName("track_id") val trackId: String = "",
    @SerialName("id") val id: String = "",
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val duration: Long = 0L,
    @SerialName("cover_id") val coverId: String = "",
) {
    val effectiveId: String get() = trackId.ifEmpty { id }
}

@Serializable
data class PlaybackControlRequestDto(
    val command: String,
    val value: String = "",
    @SerialName("track_id") val trackId: String? = null,
    @SerialName("position_ms") val positionMs: Long? = null,
    val volume: Int? = null,
)
