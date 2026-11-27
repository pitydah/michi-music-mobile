package org.michimusic.data.artist

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index

/** One explicit LOCAL track identity choice. The embedded tag is not authority.
 * Photo/profile writes MUST NOT use this record as an album-wide association. */
@Entity(
    tableName = "artist_explicit_track_identity",
    primaryKeys = ["libraryId", "albumKey", "trackId", "creditKey"],
    indices = [Index(value = ["libraryId", "trackId"]),
               Index(value = ["libraryId", "creditKey", "confirmedMbid"])],
)
data class ArtistExplicitTrackIdentityRecord(
    val libraryId: String,
    val albumKey: String,
    val trackId: String,
    val creditKey: String,
    val trackKeySha256: String,
    val confirmedMbid: String,
    val rootSourceId: String,
    val rootEpoch: Long,
    val catalogRevision: Long,
    val evidence: String,
    val revision: Long,
    val confirmedAtEpochMs: Long,
    /** D46: SHA-256 of the bytes observed at explicit user confirmation. Empty on migration. */
    @ColumnInfo(defaultValue = "''") val sourceContentSha256: String = "",
    @ColumnInfo(defaultValue = "0") val sourceContentBytes: Long = 0L,
)
