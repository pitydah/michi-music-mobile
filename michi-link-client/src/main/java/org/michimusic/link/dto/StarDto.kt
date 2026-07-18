package org.michimusic.link.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class StarResponse(
    val id: String = "",
    val starred: Boolean = false,
    @SerialName("starred_at") val starredAt: String? = null,
)

@Serializable
data class StarredListResponse(
    val starred: List<TrackResponseDto> = emptyList(),
    val total: Int = 0,
)

@Serializable
data class RateRequest(
    val rating: Int,
)

@Serializable
data class RateResponse(
    val id: String = "",
    val rating: Int = 0,
)
