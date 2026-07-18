package org.michimusic.link.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class TrackBulkRequest(
    @SerialName("track_ids") val trackIds: List<String>,
)

@Serializable
data class TrackBulkResponse(
    val tracks: List<TrackResponseDto> = emptyList(),
    val total: Int = 0,
)
