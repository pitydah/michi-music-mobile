package org.michimusic.link.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ArtistDto(
    val id: String = "",
    val name: String = "",
    @SerialName("album_count") val albumCount: Int = 0,
    @SerialName("track_count") val trackCount: Int = 0,
    val genre: String? = null,
)

@Serializable
data class ArtistDetailDto(
    val artist: ArtistDto = ArtistDto(),
    val albums: List<AlbumDto> = emptyList(),
    @SerialName("top_tracks") val topTracks: List<TrackResponseDto> = emptyList(),
)
