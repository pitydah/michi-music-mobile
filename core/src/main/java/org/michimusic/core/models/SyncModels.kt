package org.michimusic.core.models

import kotlinx.serialization.Serializable

@Serializable
data class DeviceInfo(
    val alias: String,
    val device: String = "android",
    val deviceModel: String = "",
    val port: Int = 0,
    val version: String = "1.0",
)

@Serializable
data class SessionToken(
    val token: String,
    val createdAt: Long = 0L,
    val deviceAlias: String = "",
    val deviceType: String = "",
    val expiresIn: Long = 3600L,
)

@Serializable
data class RegisterRequest(
    val alias: String,
    val device: String = "android",
    val deviceModel: String = "",
    val port: Int = 0,
    val clientDeviceId: String = "",
)

@Serializable
data class RegisterResponse(
    val sessionToken: String,
    val serverDeviceId: String,
    val clientDeviceId: String,
    val librarySize: Int,
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
    val size: Int = 0,
    val format: String = "",
    val bitrate: Int = 0,
    val sampleRate: Int = 0,
    val channels: Int = 0,
    val coverId: String = "",
    val trackNumber: Int = 0,
    val year: Int = 0,
)

@Serializable
data class SyncStateEntry(
    val trackId: String,
    val playCount: Int = 0,
    val lastPlayed: Long = 0L,
    val favorite: Boolean = false,
)

@Serializable
data class SyncManifest(
    val deviceId: String,
    val tracks: List<String> = emptyList(),
    val totalSize: Long = 0L,
    val lastSync: Long = 0L,
)
