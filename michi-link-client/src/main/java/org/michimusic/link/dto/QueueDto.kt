package org.michimusic.link.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * MIM-08: R1.4 active queue. The Micro Server returns
 * `{queue_id, queue_version, queue_degraded, items:[{id,track_id,position}], ...}`.
 * `id` is the queue ITEM identity; `track_id` is the library track. Mobile must
 * never confuse them: duplicate tracks in a queue are distinct items.
 */
@Serializable
data class QueueDto(
    // Legacy `/queue` payloads carried a `tracks` array. Kept for compatibility
    // with already-certified endpoints; R1.4 items are preferred and richer.
    val tracks: List<QueueTrackDto> = emptyList(),
    @SerialName("current_index") val currentIndex: Int = -1,
    @SerialName("total_duration") val totalDuration: Long = 0L,
    @SerialName("queue_id") val queueId: String = "",
    @SerialName("queue_version") val queueVersion: Long = 0L,
    @SerialName("queue_degraded") val queueDegraded: Boolean = false,
    @SerialName("queue_degraded_reason") val queueDegradedReason: String? = null,
    @SerialName("current_track_id") val currentTrackId: String = "",
    @SerialName("position_ms") val positionMs: Long = -1L,
    val playing: Boolean = false,
    val volume: Int = -1,
    /** Parallel to [tracks]; empty when the producer did not expose item ids. */
    @SerialName("item_ids") val itemIds: List<String> = emptyList(),
    @SerialName("item_positions") val itemPositions: List<Int> = emptyList(),
) {
    fun itemIdAt(index: Int): String? = itemIds.getOrNull(index)?.takeIf { it.isNotBlank() }

    fun hasStableItemIds(): Boolean =
        itemIds.size == tracks.size && itemIds.all { it.isNotBlank() } &&
            itemIds.distinct().size == itemIds.size

    val effectiveCurrentTrackId: String
        get() = currentTrackId.ifBlank { tracks.getOrNull(currentIndex)?.trackId.orEmpty() }
}

/** One queued occurrence as returned by the R1.4 coordinator. */
@Serializable
data class QueueItemDto(
    @SerialName("id") val id: String = "",
    @SerialName("track_id") val trackId: String = "",
    val position: Int = 0,
    // A producer MAY enrich items with track metadata. Optional, never invented.
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val duration: Long = 0L,
    @SerialName("cover_id") val coverId: String = "",
) {
    fun toQueueTrack(): QueueTrackDto = QueueTrackDto(
        trackId = trackId.ifBlank { id },
        title = title,
        artist = artist,
        album = album,
        duration = duration,
        coverId = coverId,
    )
}

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
    @SerialName("queue_id") val queueId: String? = null,
    @SerialName("position_ms") val positionMs: Long? = null,
)

@Serializable
data class QueueRepeatRequestDto(
    val mode: String, // "off", "one", "all"
)

@Serializable
data class QueueRepeatResponseDto(
    @SerialName("repeat_mode") val repeatMode: String = "",
    val mode: String = "",
    val repeat: String = "",
) {
    /** R1.4 returns `mode`/`repeat`; older fixtures returned `repeat_mode`. */
    val effectiveMode: String get() = repeatMode.ifBlank { mode.ifBlank { repeat } }
}

@Serializable
data class QueueShuffleRequestDto(
    val enabled: Boolean,
)

@Serializable
data class QueueShuffleResponseDto(
    @SerialName("shuffle_enabled") val shuffleEnabled: Boolean = false,
    val enabled: Boolean = false,
    val shuffle: Boolean = false,
) {
    val effectiveShuffle: Boolean get() = shuffleEnabled || enabled || shuffle
}
