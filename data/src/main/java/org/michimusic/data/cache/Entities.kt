package org.michimusic.data.cache

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "cached_tracks")
data class CachedTrack(
    @PrimaryKey val id: String,
    val title: String,
    val artist: String = "",
    val album: String = "",
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
    val downloaded: Boolean = false,
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

@Entity(tableName = "cached_playlists")
data class CachedPlaylist(
    @PrimaryKey val id: String,
    val name: String,
    val trackIds: String = "",
    val trackCount: Int = 0,
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
)

@Entity(tableName = "app_settings")
data class SettingsEntity(
    @PrimaryKey val key: String,
    val value: String = "",
)
