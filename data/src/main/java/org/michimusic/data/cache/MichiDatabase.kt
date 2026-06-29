package org.michimusic.data.cache

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

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
        val MIGRATION_1_2 = Migration(1, 2) { db ->
            db.execSQL("ALTER TABLE cached_tracks ADD COLUMN format TEXT NOT NULL DEFAULT ''")
            db.execSQL("CREATE TABLE IF NOT EXISTS `replaygain_cache` (`trackId` TEXT NOT NULL, `trackGain` REAL NOT NULL DEFAULT 'NaN', `albumGain` REAL NOT NULL DEFAULT 'NaN', PRIMARY KEY(`trackId`))")
            db.execSQL("CREATE TABLE IF NOT EXISTS `cached_playlists` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `trackIds` TEXT NOT NULL DEFAULT '', `trackCount` INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(`id`))")
            db.execSQL("CREATE TABLE IF NOT EXISTS `play_history` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `trackId` TEXT NOT NULL, `playedAt` INTEGER NOT NULL)")
            db.execSQL("CREATE TABLE IF NOT EXISTS `play_counts` (`trackId` TEXT NOT NULL, `playCount` INTEGER NOT NULL DEFAULT 0, `lastPlayed` INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(`trackId`))")
            db.execSQL("CREATE TABLE IF NOT EXISTS `saved_queue` (`id` INTEGER NOT NULL, `trackIds` TEXT NOT NULL DEFAULT '', `startIndex` INTEGER NOT NULL DEFAULT 0, `positionMs` INTEGER NOT NULL DEFAULT 0, `repeatMode` INTEGER NOT NULL DEFAULT 0, `shuffleMode` INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(`id`))")
            db.execSQL("CREATE TABLE IF NOT EXISTS `app_settings` (`key` TEXT NOT NULL, `value` TEXT NOT NULL DEFAULT '', PRIMARY KEY(`key`))")
        }

        val MIGRATION_2_3 = Migration(2, 3) { db ->
            db.execSQL("CREATE TABLE IF NOT EXISTS `cached_albums` (`id` TEXT NOT NULL, `title` TEXT NOT NULL, `artist` TEXT NOT NULL DEFAULT '', `year` INTEGER NOT NULL DEFAULT 0, `trackCount` INTEGER NOT NULL DEFAULT 0, `coverId` TEXT NOT NULL DEFAULT '', PRIMARY KEY(`id`))")
            db.execSQL("CREATE TABLE IF NOT EXISTS `cached_artists` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `albumCount` INTEGER NOT NULL DEFAULT 0, `trackCount` INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(`id`))")
        }
    }
}
