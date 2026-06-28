package org.michimusic.data.cache

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        CachedTrack::class,
        CachedAlbum::class,
        CachedArtist::class,
        CachedPlaylist::class,
        ReplayGainEntity::class,
        HistoryEntity::class,
        PlayCountEntity::class,
        QueueEntity::class,
        SettingsEntity::class,
    ],
    version = 3,
    exportSchema = false,
)
abstract class MichiDatabase : RoomDatabase() {
    abstract fun trackDao(): TrackDao
    abstract fun playlistDao(): PlaylistDao
    abstract fun replayGainDao(): ReplayGainDao
    abstract fun appDao(): AppDao

    companion object {
        fun buildForSync(context: android.content.Context): MichiDatabase {
            return androidx.room.Room.databaseBuilder(
                context,
                MichiDatabase::class.java,
                "michi-sync.db",
            ).build()
        }
    }
}
