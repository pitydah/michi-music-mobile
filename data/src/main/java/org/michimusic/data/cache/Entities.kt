package org.michimusic.data.cache

import androidx.room.Entity
import androidx.room.PrimaryKey

// K16-01: the cache is shared by every paired library, so a track ID alone is
// not an identity. Two peers can legitimately own a track with the same ID and
// different metadata; the composite key keeps both rows. An empty sourceId is
// the local/legacy owner, which is what the 7 -> 8 migration assigns.
@Entity(tableName = "cached_tracks", primaryKeys = ["sourceId", "id"])
data class CachedTrack(
    val id: String,
    val title: String,
    val artist: String = "",
    val album: String = "",
    val duration: Long = 0,
    val size: Long = 0,
    val format: String = "",
    val albumArtist: String = "",
    val bitrate: Int = 0,
    val sampleRate: Int = 0,
    val channels: Int = 0,
    val coverId: String = "",
    val trackNumber: Int = 0,
    val year: Int = 0,
    val filepath: String = "",
    val downloaded: Boolean = false,
    val sourceId: String = "",
    /** Size recorded when this app verified the managed copy. 0 = unverified. */
    val verifiedSize: Long = 0,
    /** SHA-256 computed or verified by this app for [filepath]. Empty = unverified. */
    val verifiedSha256: String = "",
    /** Wall-clock time of the verification; invalidates copies modified afterwards. */
    val verifiedAt: Long = 0,
    /** Lowercase algorithm of [verifiedSha256]. */
    val verifiedAlgorithm: String = "",
    /** Original genre metadata. Never inferred when the source omits it. */
    val genre: String = "",
)

@Entity(tableName = "cached_albums")
data class CachedAlbum(
    @PrimaryKey val id: String,
    val title: String,
    val artist: String = "",
    val year: Int = 0,
    val trackCount: Int = 0,
    val coverId: String = "",
)

@Entity(tableName = "cached_artists")
data class CachedArtist(
    @PrimaryKey val id: String,
    val name: String,
    val albumCount: Int = 0,
    val trackCount: Int = 0,
)

// K16-02: playlist IDs are only unique inside the library that produced them.
// The composite key prevents one peer's manifest from overwriting another's.
@Entity(tableName = "cached_playlists", primaryKeys = ["sourceId", "id"])
data class CachedPlaylist(
    val id: String,
    val name: String,
    val trackIds: String = "",
    val trackCount: Int = 0,
    val sourceId: String = "",
)

@Entity(tableName = "replaygain_cache")
data class ReplayGainEntity(
    @PrimaryKey val trackId: String,
    val trackGain: Float = Float.NaN,
    val albumGain: Float = Float.NaN,
)

@Entity(tableName = "play_history")
data class HistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val trackId: String,
    val playedAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "play_counts")
data class PlayCountEntity(
    @PrimaryKey val trackId: String,
    val playCount: Int = 0,
    val lastPlayed: Long = 0L,
)

@Entity(tableName = "saved_queue")
data class QueueEntity(
    @PrimaryKey val id: Int = 0,
    val trackIds: String = "",
    val startIndex: Int = 0,
    val positionMs: Long = 0L,
    val repeatMode: Int = 0,
    val shuffleMode: Boolean = false,
    val entryIds: String = "",
    val locationIds: String = "",
)

@Entity(tableName = "app_settings")
data class SettingsEntity(
    @PrimaryKey val key: String,
    val value: String = "",
)
