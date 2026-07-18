package org.michimusic.link.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class AlbumDto(
    val id: String = "",
    val title: String = "",
    val artist: String = "",
    val year: Int? = null,
    val genre: String? = null,
    @SerialName("track_count") val trackCount: Int = 0,
    @SerialName("cover_id") val coverId: String? = null,
    @SerialName("duration_ms") val durationMs: Long = 0L,
    val starred: Boolean = false,
    val rating: Int = 0,
)

@Serializable
data class AlbumDetailDto(
    val album: AlbumDto = AlbumDto(),
    val tracks: List<TrackResponseDto> = emptyList(),
)
