package org.michimusic.data.cache

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        CachedTrack::class,
        CachedAlbum::class,
        CachedArtist::class,
        CachedPlaylist::class,
    ],
    version = 1,
    exportSchema = false,
)
abstract class MichiDatabase : RoomDatabase() {
    abstract fun trackDao(): TrackDao
    abstract fun playlistDao(): PlaylistDao
}
