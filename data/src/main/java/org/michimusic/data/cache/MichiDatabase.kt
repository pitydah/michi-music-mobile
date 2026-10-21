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
        LibraryEntity::class,
        TrackIdentityEntity::class,
        TrackLocationEntity::class,
        LibraryTrackCrossRef::class,
        PlaylistHeaderEntity::class,
        PlaylistEntryEntity::class,
        SyncProfileEntity::class,
        SyncManifestEntity::class,
        TransferCheckpointEntity::class,
    ],
    version = 5,
    exportSchema = true,
)
abstract class MichiDatabase : RoomDatabase() {
    abstract fun trackDao(): TrackDao
    abstract fun playlistDao(): PlaylistDao
    abstract fun replayGainDao(): ReplayGainDao
    abstract fun appDao(): AppDao
    abstract fun libraryDao(): LibraryDao
    abstract fun trackIdentityDao(): TrackIdentityDao
    abstract fun trackLocationDao(): TrackLocationDao
    abstract fun libraryMembershipDao(): LibraryMembershipDao
    abstract fun normalizedPlaylistDao(): NormalizedPlaylistDao
    abstract fun syncProfileDao(): SyncProfileDao
    abstract fun syncManifestDao(): SyncManifestDao
    abstract fun transferCheckpointDao(): TransferCheckpointDao


    companion object {
        const val DEFAULT_DEVICE_LIBRARY_ID = "lib_device_local"

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

        val MIGRATION_3_4 = Migration(3, 4) { db ->
            // 1. Create libraries table and indices
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS `libraries` (
                    `libraryId` TEXT NOT NULL,
                    `name` TEXT NOT NULL,
                    `type` TEXT NOT NULL,
                    `ownerId` TEXT,
                    `isWritable` INTEGER NOT NULL,
                    `createdAt` INTEGER NOT NULL,
                    `updatedAt` INTEGER NOT NULL,
                    PRIMARY KEY(`libraryId`)
                )
            """.trimIndent())
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_libraries_type` ON `libraries` (`type`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_libraries_ownerId` ON `libraries` (`ownerId`)")

            // 2. Create track_identities table and indices
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS `track_identities` (
                    `trackId` TEXT NOT NULL,
                    `title` TEXT NOT NULL,
                    `artist` TEXT NOT NULL,
                    `album` TEXT NOT NULL,
                    `durationMs` INTEGER NOT NULL,
                    `albumTrackNumber` INTEGER,
                    `contentHash` TEXT,
                    `identityScheme` TEXT NOT NULL,
                    PRIMARY KEY(`trackId`)
                )
            """.trimIndent())
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_track_identities_contentHash` ON `track_identities` (`contentHash`)")

            // 3. Create track_locations table and indices
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS `track_locations` (
                    `locationId` TEXT NOT NULL,
                    `trackId` TEXT NOT NULL,
                    `kind` TEXT NOT NULL,
                    `uri` TEXT,
                    `sourceDeviceId` TEXT,
                    `remoteTrackId` TEXT,
                    `availability` TEXT NOT NULL,
                    PRIMARY KEY(`locationId`),
                    FOREIGN KEY(`trackId`) REFERENCES `track_identities`(`trackId`) ON UPDATE NO ACTION ON DELETE CASCADE
                )
            """.trimIndent())
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_track_locations_trackId` ON `track_locations` (`trackId`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_track_locations_uri` ON `track_locations` (`uri`)")

            // 4. Create library_tracks table and indices
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS `library_tracks` (
                    `libraryId` TEXT NOT NULL,
                    `trackId` TEXT NOT NULL,
                    `addedAt` INTEGER NOT NULL,
                    `origin` TEXT NOT NULL,
                    PRIMARY KEY(`libraryId`, `trackId`),
                    FOREIGN KEY(`libraryId`) REFERENCES `libraries`(`libraryId`) ON UPDATE NO ACTION ON DELETE CASCADE,
                    FOREIGN KEY(`trackId`) REFERENCES `track_identities`(`trackId`) ON UPDATE NO ACTION ON DELETE CASCADE
                )
            """.trimIndent())
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_library_tracks_trackId` ON `library_tracks` (`trackId`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_library_tracks_libraryId` ON `library_tracks` (`libraryId`)")

            // 5. Create playlist_headers table and indices
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS `playlist_headers` (
                    `playlistId` TEXT NOT NULL,
                    `name` TEXT NOT NULL,
                    `libraryId` TEXT,
                    `scope` TEXT NOT NULL,
                    `createdAt` INTEGER NOT NULL,
                    `updatedAt` INTEGER NOT NULL,
                    PRIMARY KEY(`playlistId`)
                )
            """.trimIndent())
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_playlist_headers_libraryId` ON `playlist_headers` (`libraryId`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_playlist_headers_scope` ON `playlist_headers` (`scope`)")

            // 6. Create playlist_entries table and indices
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS `playlist_entries` (
                    `entryId` TEXT NOT NULL,
                    `playlistId` TEXT NOT NULL,
                    `trackId` TEXT NOT NULL,
                    `position` INTEGER NOT NULL,
                    `addedAt` INTEGER NOT NULL,
                    PRIMARY KEY(`entryId`),
                    FOREIGN KEY(`playlistId`) REFERENCES `playlist_headers`(`playlistId`) ON UPDATE NO ACTION ON DELETE CASCADE
                )
            """.trimIndent())
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_playlist_entries_playlistId_position` ON `playlist_entries` (`playlistId`, `position`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_playlist_entries_trackId` ON `playlist_entries` (`trackId`)")

            // 7. Create sync_profiles table and indices
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS `sync_profiles` (
                    `id` TEXT NOT NULL,
                    `name` TEXT NOT NULL,
                    `sourceLibraryId` TEXT NOT NULL,
                    `destinationLibraryId` TEXT NOT NULL,
                    `mode` TEXT NOT NULL,
                    `policy` TEXT NOT NULL,
                    `provenance` TEXT,
                    `createdAt` INTEGER NOT NULL,
                    PRIMARY KEY(`id`)
                )
            """.trimIndent())
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_sync_profiles_sourceLibraryId_destinationLibraryId` ON `sync_profiles` (`sourceLibraryId`, `destinationLibraryId`)")

            // 8. Create sync_manifests table and indices
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS `sync_manifests` (
                    `jobId` TEXT NOT NULL,
                    `profileId` TEXT NOT NULL,
                    `sourceMetadata` TEXT NOT NULL,
                    `expectedHashes` TEXT NOT NULL,
                    `version` INTEGER NOT NULL,
                    `createdAt` INTEGER NOT NULL,
                    PRIMARY KEY(`jobId`)
                )
            """.trimIndent())
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_sync_manifests_profileId` ON `sync_manifests` (`profileId`)")

            // 9. Insert default device-local library
            val now = System.currentTimeMillis()
            db.execSQL("""
                INSERT OR IGNORE INTO `libraries` (`libraryId`, `name`, `type`, `ownerId`, `isWritable`, `createdAt`, `updatedAt`)
                VALUES ('$DEFAULT_DEVICE_LIBRARY_ID', 'Este teléfono', 'DEVICE_LOCAL', NULL, 0, $now, $now)
            """.trimIndent())

            // 10. Migrate existing playlists from cached_playlists CSV to playlist_headers and playlist_entries
            try {
                val cursor = db.query("SELECT id, name, trackIds FROM cached_playlists")
                val playlistsToMigrate = mutableListOf<Triple<String, String, String>>()
                while (cursor.moveToNext()) {
                    val pId = cursor.getString(0)
                    val pName = cursor.getString(1)
                    val pTrackIds = cursor.getString(2) ?: ""
                    playlistsToMigrate.add(Triple(pId, pName, pTrackIds))
                }
                cursor.close()

                for ((pId, pName, pTrackIds) in playlistsToMigrate) {
                    val escapedName = pName.replace("'", "''")
                    val escapedId = pId.replace("'", "''")
                    db.execSQL("""
                        INSERT OR IGNORE INTO `playlist_headers` (`playlistId`, `name`, `libraryId`, `scope`, `createdAt`, `updatedAt`)
                        VALUES ('$escapedId', '$escapedName', NULL, 'GLOBAL', $now, $now)
                    """.trimIndent())

                    val trackIdList = pTrackIds.split(",")
                        .map { it.trim() }
                        .filter { it.isNotEmpty() }

                    trackIdList.forEachIndexed { index, trackId ->
                        val entryId = "${escapedId}_migrated_$index"
                        val escapedTrackId = trackId.replace("'", "''")
                        db.execSQL("""
                            INSERT OR IGNORE INTO `playlist_entries` (`entryId`, `playlistId`, `trackId`, `position`, `addedAt`)
                            VALUES ('$entryId', '$escapedId', '$escapedTrackId', $index, $now)
                        """.trimIndent())
                    }
                }
            } catch (e: Exception) {
                android.util.Log.w("MichiDatabase", "Error migrando cached_playlists a playlist_entries", e)
            }
        }

        val MIGRATION_4_5 = Migration(4, 5) { db ->
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS `transfer_checkpoints` (
                    `transferKey` TEXT NOT NULL,
                    `transferId` TEXT NOT NULL,
                    `remoteDeviceId` TEXT NOT NULL,
                    `sourceLibraryId` TEXT NOT NULL,
                    `destinationLibraryId` TEXT NOT NULL,
                    `trackId` TEXT NOT NULL,
                    `remoteTrackId` TEXT NOT NULL,
                    `tempFilePath` TEXT NOT NULL,
                    `finalFilePath` TEXT NOT NULL,
                    `expectedSize` INTEGER NOT NULL,
                    `receivedBytes` INTEGER NOT NULL,
                    `verifiedBytes` INTEGER NOT NULL,
                    `expectedSha256` TEXT NOT NULL,
                    `etag` TEXT NOT NULL,
                    `transferState` TEXT NOT NULL,
                    `createdAt` INTEGER NOT NULL,
                    `updatedAt` INTEGER NOT NULL,
                    PRIMARY KEY(`transferKey`)
                )
            """.trimIndent())
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_transfer_checkpoints_remoteDeviceId` ON `transfer_checkpoints` (`remoteDeviceId`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_transfer_checkpoints_remoteDeviceId_destinationLibraryId` ON `transfer_checkpoints` (`remoteDeviceId`, `destinationLibraryId`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_transfer_checkpoints_trackId` ON `transfer_checkpoints` (`trackId`)")
        }
    }
}
