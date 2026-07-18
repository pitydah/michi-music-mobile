package org.michimusic.link.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class PlaylistDetailDto(
    val id: String = "",
    val name: String = "",
    val description: String? = null,
    @SerialName("track_count") val trackCount: Int = 0,
    val tracks: List<TrackResponseDto> = emptyList(),
    @SerialName("updated_at") val updatedAt: String? = null,
)

@Serializable
data class PlaylistCreateRequest(
    val name: String,
    val description: String? = null,
)

@Serializable
data class PlaylistUpdateRequest(
    val name: String? = null,
    val description: String? = null,
)

@Serializable
data class PlaylistTracksUpdateRequest(
    @SerialName("track_ids") val trackIds: List<String>,
)
