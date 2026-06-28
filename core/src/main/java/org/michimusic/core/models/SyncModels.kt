package org.michimusic.core.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class DeviceInfo(
    val alias: String,
    val device: String = "android",
    @SerialName("device_model") val deviceModel: String = "",
    val port: Int = 0,
    val version: String = "1.0",
)

@Serializable
data class SessionToken(
    val token: String,
    val createdAt: Long = 0L,
    @SerialName("device_alias") val deviceAlias: String = "",
    @SerialName("device_type") val deviceType: String = "",
    @SerialName("expires_in") val expiresIn: Long = 3600L,
)

@Serializable
data class RegisterRequest(
    val alias: String,
    val device: String = "android",
    @SerialName("device_model") val deviceModel: String = "",
    val port: Int = 0,
    @SerialName("client_device_id") val clientDeviceId: String = "",
)

@Serializable
data class RegisterResponse(
    @SerialName("session_token") val sessionToken: String,
    @SerialName("server_device_id") val serverDeviceId: String,
    @SerialName("client_device_id") val clientDeviceId: String,
    @SerialName("library_size") val librarySize: Int,
    val version: String = "1.0",
)

@Serializable
data class LibraryResponse(
    val tracks: List<TrackDto>,
    val total: Int,
    val artists: Int = 0,
    val albums: Int = 0,
)

@Serializable
data class TrackDto(
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
data class SyncStateEntry(
    @SerialName("track_id") val trackId: String,
    @SerialName("play_count") val playCount: Int = 0,
    @SerialName("last_played") val lastPlayed: Long = 0L,
    val favorite: Boolean = false,
)

@Serializable
data class AnnounceMessage(
    val type: String = "announce",
    val alias: String = "",
    val device: String = "desktop",
    val port: Int = 53318,
    val version: String = "1.0",
    @SerialName("device_model") val deviceModel: String = "",
    @SerialName("device_id") val deviceId: String = "",
)

@Serializable
data class DiscoveredPeer(
    val alias: String,
    val ip: String,
    val port: Int = 53318,
    @SerialName("device_type") val deviceType: String = "desktop",
    @SerialName("device_id") val deviceId: String = "",
    val version: String = "1.0",
)

enum class SyncConnectionState {
    DISCONNECTED,
    DISCOVERING,
    CONNECTING,
    CONNECTED,
    ERROR,
}

@Serializable
data class ManifestTrack(
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
    val checksum: String = "",
    @SerialName("download_path") val downloadPath: String = "",
)

@Serializable
data class SyncManifest(
    @SerialName("manifest_id") val manifestId: String = "",
    @SerialName("device_id") val deviceId: String = "",
    @SerialName("created_at") val createdAt: String = "",
    @SerialName("total_tracks") val totalTracks: Int = 0,
    @SerialName("total_size") val totalSize: Long = 0L,
    val tracks: List<ManifestTrack> = emptyList(),
)

@Serializable
data class FavoritesResponse(
    val tracks: List<String> = emptyList(),
)

@Serializable
data class HistoryEntry(
    @SerialName("track_id") val trackId: String,
    @SerialName("played_at") val playedAt: Long = 0L,
)

@Serializable
data class HistoryResponse(
    val entries: List<HistoryEntry> = emptyList(),
)
