package org.michimusic.link.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

@Serializable
data class SyncManifestDto(
    val schema: String = "michi.sync.manifest",
    val version: Int = 1,
    @SerialName("manifest_id") val manifestId: String = "",
    @SerialName("device_id") val deviceId: String = "",
    @SerialName("created_at") val createdAt: String = "",
    val mode: String = "",
    val profile: SyncProfileDto = SyncProfileDto(),
    val tracks: List<ManifestTrackDto> = emptyList(),
    val playlists: List<ManifestPlaylistDto> = emptyList(),
    val removed: List<JsonElement> = emptyList(),
    @SerialName("total_tracks") val totalTracks: Int = 0,
    @SerialName("total_size") val totalSize: Long = 0L,
    val cursor: String = "",
    val since: String = "",
    val until: String = "",
    val added: List<ManifestTrackDto> = emptyList(),
    val updated: List<ManifestTrackDto> = emptyList(),
    val modified: List<ManifestTrackDto> = emptyList(),
    val deleted: List<String> = emptyList(),
    @SerialName("total_added") val totalAdded: Int = 0,
    @SerialName("total_modified") val totalModified: Int = 0,
    @SerialName("total_removed") val totalRemoved: Int = 0,
    @SerialName("playlists_updated") val playlistsUpdated: List<ManifestPlaylistDto> = emptyList(),
) {
    val effectiveTracks: List<ManifestTrackDto>
        get() = if (tracks.isNotEmpty()) tracks else added + updated + modified
    val effectiveRemoved: List<String>
        get() = removed.mapNotNull { it.removedTrackId() }.ifEmpty { deleted }
}

private fun JsonElement.removedTrackId(): String? = when (this) {
    is JsonPrimitive -> contentOrNull
    is JsonObject -> {
        val id = this["track_id"] ?: this["id"]
        (id as? JsonPrimitive)?.contentOrNull
    }
    else -> null
}

@Serializable
data class ManifestTrackDto(
    @SerialName("track_id") val trackId: String,
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val size: Long = 0,
    val format: String = "",
    val duration: Int = 0,
    val year: Int = 0,
    val genre: String = "",
    @SerialName("cover_id") val coverId: String = "",
    val checksum: String? = null,
    @SerialName("download_path") val downloadPath: String = "",
) {
    fun toTrackDto() = org.michimusic.core.models.TrackDto(
        id = trackId,
        title = title,
        artist = artist,
        album = album,
        duration = duration,
        size = size,
        format = format,
        coverId = coverId,
        year = year,
    )
}

@Serializable
data class SyncProfileDto(
    val audio: String = "original",
    val artwork: String = "embedded",
    val lyrics: Boolean = false,
    val replaygain: Boolean = true,
)

@Serializable
data class SyncStateEntry(
    @SerialName("track_id") val trackId: String,
    @SerialName("play_count") val playCount: Int = 0,
    @SerialName("last_played") val lastPlayed: Long = 0L,
    val favorite: Boolean = false,
)
