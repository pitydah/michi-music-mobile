package org.michimusic.core.models

enum class LibraryType {
    DEVICE_LOCAL,
    SYNCED,
    REMOTE_STREAM,
    USER_CUSTOM,
}

data class MusicLibrary(
    val id: String,
    val name: String,
    val type: LibraryType,
    val ownerId: String? = null,
    val isWritable: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)

data class TrackIdentity(
    val id: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val albumTrackNumber: Int? = null,
    val contentHash: String? = null,
    val identityScheme: String = "CANONICAL_V1",
)

enum class TrackLocationType {
    MEDIASTORE,
    SAF,
    SYNCED_MANAGED,
    REMOTE_URL,
}

enum class LocationAvailability {
    AVAILABLE,
    UNAVAILABLE,
    REQUIRES_PERMISSION,
    OFFLINE,
}

data class TrackLocation(
    val id: String,
    val trackId: String,
    val type: TrackLocationType,
    val uri: String?,
    val sourceDeviceId: String? = null,
    val remoteTrackId: String? = null,
    val availability: LocationAvailability = LocationAvailability.AVAILABLE,
)

data class PlayableLocation(
    val locationId: String,
    val uri: String,
    val type: TrackLocationType,
)

data class ResolvedTrack(
    val identity: TrackIdentity,
    val locations: List<TrackLocation>,
    val preferredLocation: PlayableLocation?,
)

sealed interface LibraryScope {
    data object All : LibraryScope
    data class One(val libraryId: String) : LibraryScope
}
