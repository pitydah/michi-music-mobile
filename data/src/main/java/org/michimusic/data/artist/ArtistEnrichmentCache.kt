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
}

@Database(entities = [ArtistEnrichmentRecord::class, ArtistTrackEvidenceRecord::class], version = 2, exportSchema = true)
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
    }
}
