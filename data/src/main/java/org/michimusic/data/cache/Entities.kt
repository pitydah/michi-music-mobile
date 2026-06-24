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
