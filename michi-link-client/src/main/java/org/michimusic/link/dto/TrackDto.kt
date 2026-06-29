package org.michimusic.link.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class TrackResponseDto(
    val id: String,
    val title: String,
    val artist: String = "",
    val album: String = "",
    val duration: Int = 0,
    val size: Long = 0,
    val format: String = "",
    val bitrate: Int = 0,
    @SerialName("sample_rate") val sampleRate: Int = 0,
    val channels: Int = 0,
    @SerialName("cover_id") val coverId: String = "",
    @SerialName("track_number") val trackNumber: Int = 0,
    val year: Int = 0,
)

@Serializable
data class LibraryStatsDto(
    @SerialName("total_tracks") val totalTracks: Int = 0,
    @SerialName("total_artists") val totalArtists: Int = 0,
    @SerialName("total_albums") val totalAlbums: Int = 0,
    @SerialName("total_playlists") val totalPlaylists: Int = 0,
    @SerialName("total_duration") val totalDuration: Long = 0L,
    @SerialName("total_size") val totalSize: Long = 0L,
)

@Serializable
data class LibraryResponseDto(
    val tracks: List<TrackResponseDto> = emptyList(),
    val total: Int = 0,
)

@Serializable
data class SearchResponseDto(
    val results: List<TrackResponseDto> = emptyList(),
    val query: String = "",
)

@Serializable
data class TrackListResponseDto(
    val items: List<TrackResponseDto> = emptyList(),
    val total: Int = 0,
)
