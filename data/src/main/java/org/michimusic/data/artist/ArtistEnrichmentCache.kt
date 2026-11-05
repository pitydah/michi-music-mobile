package org.michimusic.data.artist

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Index
import androidx.room.Transaction
import androidx.room.migration.Migration
import androidx.room.RoomDatabase

/**
 * A deliberately separate Room database: upgrading Artist Enrichment does not change
 * MichiDatabase (v7) or migrate/delete any track, playlist, history or playback table.
 * Version 1 has no legacy enrichment rows to migrate. Future schema revisions MUST supply
 * explicit Room migrations; never fallbackToDestructiveMigration().
 */
@Entity(tableName = "artist_enrichment", primaryKeys = ["libraryId", "creditKey", "albumGroupKey"])
data class ArtistEnrichmentRecord(
    val libraryId: String,
    val creditKey: String,
    val albumGroupKey: String,
    val confirmedMbid: String,
    val profileJson: String,
    val savedAtMs: Long,
    val staleAfterMs: Long,
    val source: String,
    val biographySourceUrl: String?,
    val portraitSourceUrl: String?,
    val portraitLicense: String?,
    val portraitLicenseUrl: String?,
    val portraitAttribution: String?,
)

/** Raw observations, never automatic artist identity assignment or playback metadata rewrite. */
@Entity(
    tableName = "artist_track_evidence",
    primaryKeys = ["libraryId", "trackId", "field", "ordinal"],
    indices = [Index(value = ["libraryId", "trackId"])],
)
data class ArtistTrackEvidenceRecord(
    val libraryId: String,
    val trackId: String,
    val field: String, // ARTIST_MBID, ALBUM_ARTIST_MBID, ARTISTS, ALBUM_ARTIST
    val ordinal: Int,
    val value: String,
    val observedAtMs: Long,
    val source: String = "USER_REQUESTED_EMBEDDED_TAG_SCAN",
)

/** One immutable downloaded artwork file, addressable by its SHA-256-derived asset id. */
@Entity(tableName = "artist_artwork_asset", primaryKeys = ["assetId"])
data class ArtistArtworkAssetRecord(
    val assetId: String,
    val confirmedMbid: String,
    val verifiedQid: String?,
    val source: String,
    val sourcePageUrl: String,
    val imageUrl: String,
    val localRelativePath: String,
    val sha256: String,
    val mimeType: String,
    val width: Int,
    val height: Int,
    val licenseId: String,
    val licenseUrl: String,
    val attribution: String,
    val evidence: String,
    val downloadedAtMs: Long,
)

/** Per-slot choice. The same asset may back both slots, but focus stays independent. */
@Entity(
    tableName = "artist_artwork_selection",
    primaryKeys = ["libraryId", "creditKey", "albumGroupKey", "slot"],
    indices = [Index(value = ["assetId"])],
)
data class ArtistArtworkSelectionRecord(
    val libraryId: String,
    val creditKey: String,
    val albumGroupKey: String,
    val slot: String,
    val assetId: String,
    val confirmedMbid: String,
    val focusX: Float,
    val focusY: Float,
    val manuallyChosen: Boolean,
    val revision: Long,
    val updatedAtMs: Long,
)

@Dao
interface ArtistEnrichmentDao {
    @Query("SELECT * FROM artist_enrichment WHERE libraryId = :libraryId AND creditKey = :creditKey AND albumGroupKey = :albumGroupKey LIMIT 1")
    suspend fun get(libraryId: String, creditKey: String, albumGroupKey: String): ArtistEnrichmentRecord?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(record: ArtistEnrichmentRecord)

    @Query("DELETE FROM artist_enrichment WHERE libraryId = :libraryId AND creditKey = :creditKey AND albumGroupKey = :albumGroupKey")
    suspend fun delete(libraryId: String, creditKey: String, albumGroupKey: String): Int

    @Query("SELECT * FROM artist_track_evidence WHERE libraryId = :libraryId AND trackId = :trackId ORDER BY field, ordinal")
    suspend fun getTrackEvidence(libraryId: String, trackId: String): List<ArtistTrackEvidenceRecord>

    @Query("DELETE FROM artist_track_evidence WHERE libraryId = :libraryId AND trackId = :trackId")
    suspend fun clearTrackEvidence(libraryId: String, trackId: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTrackEvidence(rows: List<ArtistTrackEvidenceRecord>)

    @Transaction
    suspend fun replaceTrackEvidence(libraryId: String, trackId: String, rows: List<ArtistTrackEvidenceRecord>) {
        clearTrackEvidence(libraryId, trackId)
        insertTrackEvidence(rows)
    }

    @Query("SELECT COUNT(*) FROM artist_enrichment WHERE libraryId = :libraryId")
    suspend fun countForLibrary(libraryId: String): Int

    @Query("SELECT * FROM artist_artwork_asset WHERE assetId = :assetId LIMIT 1")
    suspend fun getArtworkAsset(assetId: String): ArtistArtworkAssetRecord?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putArtworkAsset(asset: ArtistArtworkAssetRecord)

    @Query("SELECT * FROM artist_artwork_selection WHERE libraryId = :libraryId AND creditKey = :creditKey AND albumGroupKey = :albumGroupKey")
    suspend fun getArtworkSelections(libraryId: String, creditKey: String, albumGroupKey: String): List<ArtistArtworkSelectionRecord>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putArtworkSelection(selection: ArtistArtworkSelectionRecord)

    @Query("DELETE FROM artist_artwork_selection WHERE libraryId = :libraryId AND creditKey = :creditKey AND albumGroupKey = :albumGroupKey")
    suspend fun clearArtworkSelections(libraryId: String, creditKey: String, albumGroupKey: String): Int

    @Query("SELECT COUNT(*) FROM artist_artwork_selection WHERE libraryId = :libraryId")
    suspend fun countArtworkSelectionsForLibrary(libraryId: String): Int

    @Query("SELECT COUNT(*) FROM artist_artwork_selection")
    suspend fun countArtworkSelections(): Int

    @Query("SELECT COUNT(*) FROM artist_artwork_asset")
    suspend fun countArtworkAssets(): Int

    @Transaction
    suspend fun persistArtwork(asset: ArtistArtworkAssetRecord, selection: ArtistArtworkSelectionRecord) {
        putArtworkAsset(asset)
        putArtworkSelection(selection)
    }
}

@Database(
    entities = [
        ArtistEnrichmentRecord::class,
        ArtistTrackEvidenceRecord::class,
        ArtistArtworkAssetRecord::class,
        ArtistArtworkSelectionRecord::class,
    ],
    version = 3,
    exportSchema = true,
)
abstract class ArtistEnrichmentCacheDatabase : RoomDatabase() {
    abstract fun artistEnrichmentDao(): ArtistEnrichmentDao

    companion object {
        /** Migrates old R16.4 optional enrichment data in place. No destructive fallback. */
        val MIGRATION_1_2 = Migration(1, 2) { db ->
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS `artist_track_evidence` (
                    `libraryId` TEXT NOT NULL, `trackId` TEXT NOT NULL,
                    `field` TEXT NOT NULL, `ordinal` INTEGER NOT NULL,
                    `value` TEXT NOT NULL, `observedAtMs` INTEGER NOT NULL,
                    `source` TEXT NOT NULL,
                    PRIMARY KEY(`libraryId`, `trackId`, `field`, `ordinal`)
                )
            """.trimIndent())
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_artist_track_evidence_libraryId_trackId` ON `artist_track_evidence` (`libraryId`, `trackId`)")
        }

        /** R11: independent thumb/hero assets and selections. Existing enrichment rows survive. */
        val MIGRATION_2_3 = Migration(2, 3) { db ->
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS `artist_artwork_asset` (
                    `assetId` TEXT NOT NULL,
                    `confirmedMbid` TEXT NOT NULL,
                    `verifiedQid` TEXT,
                    `source` TEXT NOT NULL,
                    `sourcePageUrl` TEXT NOT NULL,
                    `imageUrl` TEXT NOT NULL,
                    `localRelativePath` TEXT NOT NULL,
                    `sha256` TEXT NOT NULL,
                    `mimeType` TEXT NOT NULL,
                    `width` INTEGER NOT NULL,
                    `height` INTEGER NOT NULL,
                    `licenseId` TEXT NOT NULL,
                    `licenseUrl` TEXT NOT NULL,
                    `attribution` TEXT NOT NULL,
                    `evidence` TEXT NOT NULL,
                    `downloadedAtMs` INTEGER NOT NULL,
                    PRIMARY KEY(`assetId`)
                )
            """.trimIndent())
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS `artist_artwork_selection` (
                    `libraryId` TEXT NOT NULL,
                    `creditKey` TEXT NOT NULL,
                    `albumGroupKey` TEXT NOT NULL,
                    `slot` TEXT NOT NULL,
                    `assetId` TEXT NOT NULL,
                    `confirmedMbid` TEXT NOT NULL,
                    `focusX` REAL NOT NULL,
                    `focusY` REAL NOT NULL,
                    `manuallyChosen` INTEGER NOT NULL,
                    `revision` INTEGER NOT NULL,
                    `updatedAtMs` INTEGER NOT NULL,
                    PRIMARY KEY(`libraryId`, `creditKey`, `albumGroupKey`, `slot`)
                )
            """.trimIndent())
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_artist_artwork_selection_assetId` ON `artist_artwork_selection` (`assetId`)")
        }
    }
}
