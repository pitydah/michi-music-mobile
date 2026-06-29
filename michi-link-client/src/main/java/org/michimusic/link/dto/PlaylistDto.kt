package org.michimusic.link.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class PlaylistDto(
    @SerialName("playlist_id") val playlistId: String,
    val name: String,
    @SerialName("track_ids") val trackIds: List<String> = emptyList(),
    @SerialName("updated_at") val updatedAt: Long = 0L,
)

@Serializable
data class ManifestPlaylistDto(
    @SerialName("playlist_id") val playlistId: String,
    val name: String,
    @SerialName("track_ids") val trackIds: List<String> = emptyList(),
    @SerialName("updated_at") val updatedAt: Long = 0L,
) {
    fun toDomainPlaylist() = org.michimusic.core.models.ManifestPlaylist(
        playlistId = playlistId,
        name = name,
        trackIds = trackIds,
        updatedAt = updatedAt,
    )
}
