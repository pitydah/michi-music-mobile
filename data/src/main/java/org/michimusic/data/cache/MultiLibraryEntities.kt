package org.michimusic.data.cache

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "libraries",
    indices = [
        Index("type"),
        Index("ownerId"),
    ],
)
data class LibraryEntity(
    @PrimaryKey
    val libraryId: String,
    val name: String,
    val type: String, // DEVICE_LOCAL, SYNCED, REMOTE_STREAM, USER_CUSTOM
    val ownerId: String? = null,
    val isWritable: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "track_identities",
    indices = [
        Index("contentHash"),
    ],
)
data class TrackIdentityEntity(
    @PrimaryKey
    val trackId: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val albumTrackNumber: Int? = null,
    val contentHash: String? = null,
    val identityScheme: String = "CANONICAL_V1",
)

@Entity(
    tableName = "track_locations",
    indices = [
        Index("trackId"),
        Index("uri"),
    ],
    foreignKeys = [
        ForeignKey(
            entity = TrackIdentityEntity::class,
            parentColumns = ["trackId"],
            childColumns = ["trackId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class TrackLocationEntity(
    @PrimaryKey
    val locationId: String,
    val trackId: String,
    val kind: String, // MEDIASTORE, SAF, SYNCED_MANAGED, REMOTE_URL
    val uri: String?,
    val sourceDeviceId: String? = null,
    val remoteTrackId: String? = null,
    val availability: String = "AVAILABLE", // AVAILABLE, UNAVAILABLE, REQUIRES_PERMISSION, OFFLINE
)

@Entity(
    tableName = "library_tracks",
    primaryKeys = ["libraryId", "trackId"],
    indices = [
        Index("trackId"),
        Index("libraryId"),
    ],
    foreignKeys = [
        ForeignKey(
            entity = LibraryEntity::class,
            parentColumns = ["libraryId"],
            childColumns = ["libraryId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = TrackIdentityEntity::class,
            parentColumns = ["trackId"],
            childColumns = ["trackId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class LibraryTrackCrossRef(
    val libraryId: String,
    val trackId: String,
    val addedAt: Long = System.currentTimeMillis(),
    val origin: String = "LOCAL",
)

@Entity(
    tableName = "playlist_headers",
    indices = [
        Index("libraryId"),
        Index("scope"),
    ],
)
data class PlaylistHeaderEntity(
    @PrimaryKey
    val playlistId: String,
    val name: String,
    val libraryId: String? = null, // null = Global
    val scope: String = "GLOBAL",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "playlist_entries",
    indices = [
        Index(value = ["playlistId", "position"]),
        Index("trackId"),
    ],
    foreignKeys = [
        ForeignKey(
            entity = PlaylistHeaderEntity::class,
            parentColumns = ["playlistId"],
            childColumns = ["playlistId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class PlaylistEntryEntity(
    @PrimaryKey
    val entryId: String,
    val playlistId: String,
    val trackId: String,
    val position: Int,
    val addedAt: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "sync_profiles",
    indices = [
        Index(value = ["sourceLibraryId", "destinationLibraryId"]),
    ],
)
data class SyncProfileEntity(
    @PrimaryKey
    val id: String,
    val name: String,
    val sourceLibraryId: String,
    val destinationLibraryId: String,
    val mode: String = "ONE_WAY",
    val policy: String = "MIRROR",
    val provenance: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "sync_manifests",
    indices = [
        Index("profileId"),
    ],
)
data class SyncManifestEntity(
    @PrimaryKey
    val jobId: String,
    val profileId: String,
    val sourceMetadata: String = "{}",
    val expectedHashes: String = "{}",
    val version: Int = 1,
    val createdAt: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "transfer_checkpoints",
    indices = [
        Index("remoteDeviceId"),
        Index(value = ["remoteDeviceId", "destinationLibraryId"]),
        Index("trackId"),
    ],
)
data class TransferCheckpointEntity(
    @PrimaryKey
    val transferKey: String, // "$remoteDeviceId:$destinationLibraryId:$trackId"
    val transferId: String,
    val remoteDeviceId: String,
    val sourceLibraryId: String,
    val destinationLibraryId: String,
    val trackId: String,
    val remoteTrackId: String,
    val tempFilePath: String,
    val finalFilePath: String,
    val expectedSize: Long,
    val receivedBytes: Long = 0L,
    val verifiedBytes: Long = 0L,
    val expectedSha256: String = "",
    val etag: String = "",
    val transferState: String = "PENDING",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "approved_sync_plans",
    indices = [
        Index("endpointId"),
        Index("executionState"),
    ],
)
data class ApprovedSyncPlanEntity(
    @PrimaryKey
    val planId: String,
    val endpointId: String,
    val endpointName: String,
    val scope: String,
    val selectionJson: String,
    val deliveryChoiceJson: String,
    val itemsJson: String,
    val manifestVersion: Int,
    val manifestChecksum: String,
    val totalSelectedTracks: Int,
    val tracksToDownloadCount: Int,
    val totalDownloadBytes: Long,
    val status: String,
    val executionState: String = "QUEUED",
    val createdAt: Long = System.currentTimeMillis(),
    val executedAt: Long? = null,
    val completedAt: Long? = null,
    val errorMessage: String? = null,
)
