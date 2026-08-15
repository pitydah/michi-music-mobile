package org.michimusic.core.models

import kotlinx.serialization.Serializable

@Serializable
data class Track(
    val id: String,
    val title: String,
    val artist: String = "",
    val album: String = "",
    val albumId: String = "",
    val duration: Long = 0,
    val size: Long = 0,
    val format: String = "",
    val bitrate: Int = 0,
    val sampleRate: Int = 0,
    val channels: Int = 0,
    val coverId: String = "",
    val trackNumber: Int = 0,
    val year: Int = 0,
    val filepath: String = "",
    val dateAdded: Long = 0L,
    val source: TrackSource = TrackSource.LOCAL,
    val replayGainTrack: Float = Float.NaN,
    val replayGainAlbum: Float = Float.NaN,
    val contentHash: String = "",
) {
    /**
     * Stable content identity signature. Uses contentHash if available,
     * or normalized canonical metadata signature: "title|artist|album|duration_secs".
     */
    val stableContentIdentity: String
        get() = if (contentHash.isNotEmpty()) {
            contentHash
        } else {
            "${title.trim().lowercase()}|${artist.trim().lowercase()}|${album.trim().lowercase()}|${duration / 1000}"
        }
}

enum class TrackSource {
    LOCAL,
    SYNCED,
    STREAMING,
}

@Serializable
data class Album(
    val id: String,
    val title: String,
    val artist: String = "",
    val year: Int = 0,
    val trackCount: Int = 0,
    val coverId: String = "",
)

@Serializable
data class Artist(
    val id: String,
    val name: String,
    val albumCount: Int = 0,
    val trackCount: Int = 0,
)

@Serializable
data class Playlist(
    val id: String,
    val name: String,
    val trackIds: List<String> = emptyList(),
    val trackCount: Int = 0,
)
