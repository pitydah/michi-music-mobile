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
     * Normalized metadata signature fallback: "title|artist|album|duration_secs".
     * Used only as a fallback heuristic when canonical contentHash is not available.
     */
    val metadataFallbackIdentity: String
        get() = "${title.trim().lowercase()}|${artist.trim().lowercase()}|${album.trim().lowercase()}|${duration / 1000}"

    /**
     * True if the track format indicates a genuine lossless compression or uncompressed PCM.
     * Conforms to Decision D-04: sampleRate >= 48000 does NOT make lossy audio (MP3/AAC/Opus) lossless.
     */
    val isLossless: Boolean
        get() {
            val fmt = format.lowercase()
            return fmt.contains("flac") || fmt.contains("alac") || fmt.contains("wav") || fmt.contains("pcm")
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
