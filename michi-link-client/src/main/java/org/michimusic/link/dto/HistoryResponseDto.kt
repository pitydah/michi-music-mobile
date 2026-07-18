package org.michimusic.link.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class HistoryEntryDto(
    @SerialName("track_id") val trackId: String = "",
    @SerialName("played_at") val playedAt: String = "",
    @SerialName("duration_ms") val durationMs: Long = 0L,
    val track: TrackResponseDto? = null,
)

@Serializable
data class HistoryResponse(
    val items: List<HistoryEntryDto> = emptyList(),
    val page: Int = 1,
    val total: Int = 0,
)

@Serializable
data class BookmarkDto(
    @SerialName("track_id") val trackId: String = "",
    @SerialName("position_ms") val positionMs: Long = 0L,
    val comment: String = "",
    @SerialName("created_at") val createdAt: String = "",
)

@Serializable
data class BookmarkUpsertRequest(
    @SerialName("position_ms") val positionMs: Long,
    val comment: String = "",
)
