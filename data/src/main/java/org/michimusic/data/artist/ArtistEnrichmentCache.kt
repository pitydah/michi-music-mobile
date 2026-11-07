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
import kotlinx.coroutines.flow.Flow

/**
 * A deliberately separate Room database: upgrading Artist Enrichment does not change
 * MichiDatabase (v7) or migrate/delete any track, playlist, history or playback table.
 * Version 1 has no legacy enrichment rows to migrate. Future schema revisions MUST supply
 * explicit Room migrations; never fallbackToDestructiveMigration().
 */
@Entity(
    tableName = "artist_enrichment",
    primaryKeys = ["libraryId", "creditKey", "albumGroupKey"],
    indices = [Index(value = ["libraryId", "creditKey", "membershipFingerprint"])],
)
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
    /**
     * JSON array of the album IDs visible when this association was confirmed. It is
     * evidence for recovering a legitimate album-group change, never a name-based
     * identity shortcut. Null on legacy rows; those remain exact-match only.
     */
    val albumIdsJson: String? = null,
    /**
     * Last visible membership fingerprint that resolved to this durable association.
     * The primary key stays [albumGroupKey] so artwork keeps one canonical association
     * even when albums are added or removed. This column is only a mutable index into
     * that association; it is never an identity by name.
     */
    val membershipFingerprint: String? = null,
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

/**
 * Per-artist provenance for an immutable content-addressed asset. The asset row only
 * describes bytes; this row keeps every attribution, licence and identity binding scoped
 * to one library/artist/MBID/slot so two artists can never overwrite each other.
 */
@Entity(
    tableName = "artist_artwork_link",
    primaryKeys = ["libraryId", "creditKey", "albumGroupKey", "slot", "confirmedMbid"],
    indices = [Index(value = ["assetId"])],
)
data class ArtistArtworkLinkRecord(
    val libraryId: String,
    val creditKey: String,
    val albumGroupKey: String,
    val slot: String,
    val confirmedMbid: String,
    val assetId: String,
    val verifiedQid: String?,
    val source: String,
    val sourcePageUrl: String,
    val imageUrl: String,
    val licenseId: String,
    val licenseUrl: String,
    val attribution: String,
    val evidence: String,
    val linkedAtMs: Long,
)

/**
 * Monotonic, persisted observation counter per library. It is incremented inside the
 * same transaction as every selection mutation, so deletions and same-millisecond
 * writes cannot hide behind MAX(updatedAtMs).
 */
@Entity(tableName = "artist_artwork_revision", primaryKeys = ["libraryId"])
data class ArtistArtworkRevisionRecord(
    val libraryId: String,
    val revision: Long,
    val updatedAtMs: Long,
)

@Dao
interface ArtistEnrichmentDao {
    @Query("SELECT * FROM artist_enrichment WHERE libraryId = :libraryId AND creditKey = :creditKey AND albumGroupKey = :albumGroupKey LIMIT 1")
    suspend fun get(libraryId: String, creditKey: String, albumGroupKey: String): ArtistEnrichmentRecord?

    @Query("SELECT * FROM artist_enrichment WHERE libraryId = :libraryId AND creditKey = :creditKey AND membershipFingerprint = :membershipFingerprint")
    suspend fun getAllByMembership(
        libraryId: String,
        creditKey: String,
        membershipFingerprint: String,
    ): List<ArtistEnrichmentRecord>

    /** Recovery candidates for one local credit. Ambiguous results must fail closed. */
    @Query("SELECT * FROM artist_enrichment WHERE libraryId = :libraryId AND creditKey = :creditKey")
    suspend fun getAllForCredit(libraryId: String, creditKey: String): List<ArtistEnrichmentRecord>

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

    @Query("SELECT * FROM artist_artwork_asset")
    suspend fun getAllArtworkAssets(): List<ArtistArtworkAssetRecord>

    @Query("DELETE FROM artist_artwork_asset WHERE assetId NOT IN (SELECT DISTINCT assetId FROM artist_artwork_selection)")
    suspend fun deleteUnreferencedArtworkAssets(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putArtworkAsset(asset: ArtistArtworkAssetRecord)

    @Query("SELECT revision FROM artist_artwork_revision WHERE libraryId = :libraryId LIMIT 1")
    fun observeLibraryArtworkRevision(libraryId: String): Flow<Long?>

    @Query("SELECT * FROM artist_artwork_selection WHERE libraryId = :libraryId AND creditKey = :creditKey AND albumGroupKey = :albumGroupKey AND confirmedMbid = :confirmedMbid")
    suspend fun getArtworkSelections(
        libraryId: String,
        creditKey: String,
        albumGroupKey: String,
        confirmedMbid: String,
    ): List<ArtistArtworkSelectionRecord>

    @Query("SELECT * FROM artist_artwork_link WHERE libraryId = :libraryId AND creditKey = :creditKey AND albumGroupKey = :albumGroupKey AND slot = :slot AND confirmedMbid = :confirmedMbid LIMIT 1")
    suspend fun getArtworkLink(
        libraryId: String,
        creditKey: String,
        albumGroupKey: String,
        slot: String,
        confirmedMbid: String,
    ): ArtistArtworkLinkRecord?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putArtworkLink(link: ArtistArtworkLinkRecord)

    @Query("DELETE FROM artist_artwork_link WHERE libraryId = :libraryId AND creditKey = :creditKey AND albumGroupKey = :albumGroupKey AND slot = :slot AND confirmedMbid != :confirmedMbid")
    suspend fun deleteOtherArtworkLinks(
        libraryId: String,
        creditKey: String,
        albumGroupKey: String,
        slot: String,
        confirmedMbid: String,
    ): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putArtworkSelection(selection: ArtistArtworkSelectionRecord)

    @Query("DELETE FROM artist_artwork_selection WHERE libraryId = :libraryId AND creditKey = :creditKey AND albumGroupKey = :albumGroupKey AND confirmedMbid = :confirmedMbid")
    suspend fun clearArtworkSelections(
        libraryId: String,
        creditKey: String,
        albumGroupKey: String,
        confirmedMbid: String,
    ): Int

    @Query("DELETE FROM artist_artwork_link WHERE libraryId = :libraryId AND creditKey = :creditKey AND albumGroupKey = :albumGroupKey AND confirmedMbid = :confirmedMbid")
    suspend fun clearArtworkLinks(
        libraryId: String,
        creditKey: String,
        albumGroupKey: String,
        confirmedMbid: String,
    ): Int

    @Query("INSERT OR IGNORE INTO artist_artwork_revision (libraryId, revision, updatedAtMs) VALUES (:libraryId, 0, :nowMs)")
    suspend fun ensureArtworkRevision(libraryId: String, nowMs: Long)

    @Query("UPDATE artist_artwork_revision SET revision = revision + 1, updatedAtMs = :nowMs WHERE libraryId = :libraryId")
    suspend fun incrementArtworkRevision(libraryId: String, nowMs: Long): Int

    @Query("SELECT revision FROM artist_artwork_revision WHERE libraryId = :libraryId LIMIT 1")
    suspend fun artworkRevision(libraryId: String): Long?

    /** Must run inside the same transaction as the selection mutation it describes. */
    @Transaction
    suspend fun bumpArtworkRevision(libraryId: String, nowMs: Long) {
        ensureArtworkRevision(libraryId, nowMs)
        incrementArtworkRevision(libraryId, nowMs)
    }

    /** Returns the transaction-assigned revision so selection rows never depend on wall-clock time. */
    @Transaction
    suspend fun bumpArtworkRevisionAndReturn(libraryId: String, nowMs: Long): Long {
        ensureArtworkRevision(libraryId, nowMs)
        incrementArtworkRevision(libraryId, nowMs)
        return artworkRevision(libraryId) ?: 1L
    }

    @Query("SELECT COUNT(*) FROM artist_artwork_selection WHERE libraryId = :libraryId")
    suspend fun countArtworkSelectionsForLibrary(libraryId: String): Int

    @Query("SELECT COUNT(*) FROM artist_artwork_selection")
    suspend fun countArtworkSelections(): Int

    @Query("SELECT COUNT(*) FROM artist_artwork_asset")
    suspend fun countArtworkAssets(): Int

    @Transaction
    suspend fun persistArtwork(
        asset: ArtistArtworkAssetRecord,
        link: ArtistArtworkLinkRecord,
        selection: ArtistArtworkSelectionRecord,
    ): Long {
        putArtworkAsset(asset)
        deleteOtherArtworkLinks(
            libraryId = link.libraryId,
            creditKey = link.creditKey,
            albumGroupKey = link.albumGroupKey,
            slot = link.slot,
            confirmedMbid = link.confirmedMbid,
        )
        putArtworkLink(link)
        val revision = bumpArtworkRevisionAndReturn(selection.libraryId, selection.updatedAtMs)
        putArtworkSelection(selection.copy(revision = revision))
        return revision
    }

    @Transaction
    suspend fun clearArtworkForMbid(
        libraryId: String,
        creditKey: String,
        albumGroupKey: String,
        confirmedMbid: String,
        nowMs: Long,
    ) {
        clearArtworkSelections(libraryId, creditKey, albumGroupKey, confirmedMbid)
        clearArtworkLinks(libraryId, creditKey, albumGroupKey, confirmedMbid)
        bumpArtworkRevision(libraryId, nowMs)
    }

    @Query("UPDATE artist_artwork_selection SET focusX = :focusX, focusY = :focusY, updatedAtMs = :nowMs WHERE libraryId = :libraryId AND creditKey = :creditKey AND albumGroupKey = :albumGroupKey AND slot = :slot AND confirmedMbid = :confirmedMbid")
    suspend fun updateArtworkFocusRow(
        libraryId: String,
        creditKey: String,
        albumGroupKey: String,
        slot: String,
        confirmedMbid: String,
        focusX: Float,
        focusY: Float,
        nowMs: Long,
    ): Int

    @Query("UPDATE artist_artwork_selection SET revision = :revision WHERE libraryId = :libraryId AND creditKey = :creditKey AND albumGroupKey = :albumGroupKey AND slot = :slot AND confirmedMbid = :confirmedMbid")
    suspend fun setArtworkSelectionRevision(
        libraryId: String,
        creditKey: String,
        albumGroupKey: String,
        slot: String,
        confirmedMbid: String,
        revision: Long,
    ): Int

    @Transaction
    suspend fun updateArtworkFocus(
        libraryId: String,
        creditKey: String,
        albumGroupKey: String,
        slot: String,
        confirmedMbid: String,
        focusX: Float,
        focusY: Float,
        nowMs: Long,
    ): Int {
        val changed = updateArtworkFocusRow(
            libraryId, creditKey, albumGroupKey, slot, confirmedMbid, focusX, focusY, nowMs,
        )
        if (changed > 0) {
            val revision = bumpArtworkRevisionAndReturn(libraryId, nowMs)
            setArtworkSelectionRevision(
                libraryId, creditKey, albumGroupKey, slot, confirmedMbid, revision,
            )
        }
        return changed
    }
}

@Database(
    entities = [
        ArtistEnrichmentRecord::class,
        ArtistTrackEvidenceRecord::class,
        ArtistArtworkAssetRecord::class,
        ArtistArtworkSelectionRecord::class,
        ArtistArtworkLinkRecord::class,
        ArtistArtworkRevisionRecord::class,
    ],
    version = 7,
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

        /** R11.1: provenance is scoped per artist/MBID/slot; asset stays immutable content. */
        val MIGRATION_3_4 = Migration(3, 4) { db ->
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS `artist_artwork_link` (
                    `libraryId` TEXT NOT NULL,
                    `creditKey` TEXT NOT NULL,
                    `albumGroupKey` TEXT NOT NULL,
                    `slot` TEXT NOT NULL,
                    `confirmedMbid` TEXT NOT NULL,
                    `assetId` TEXT NOT NULL,
                    `verifiedQid` TEXT,
                    `source` TEXT NOT NULL,
                    `sourcePageUrl` TEXT NOT NULL,
                    `imageUrl` TEXT NOT NULL,
                    `licenseId` TEXT NOT NULL,
                    `licenseUrl` TEXT NOT NULL,
                    `attribution` TEXT NOT NULL,
                    `evidence` TEXT NOT NULL,
                    `linkedAtMs` INTEGER NOT NULL,
                    PRIMARY KEY(`libraryId`, `creditKey`, `albumGroupKey`, `slot`, `confirmedMbid`)
                )
            """.trimIndent())
            db.execSQL("""
                INSERT OR REPLACE INTO `artist_artwork_link` (
                    `libraryId`, `creditKey`, `albumGroupKey`, `slot`, `confirmedMbid`,
                    `assetId`, `verifiedQid`, `source`, `sourcePageUrl`, `imageUrl`,
                    `licenseId`, `licenseUrl`, `attribution`, `evidence`, `linkedAtMs`
                )
                SELECT s.`libraryId`, s.`creditKey`, s.`albumGroupKey`, s.`slot`, s.`confirmedMbid`,
                       a.`assetId`, a.`verifiedQid`, a.`source`, a.`sourcePageUrl`, a.`imageUrl`,
                       a.`licenseId`, a.`licenseUrl`, a.`attribution`, a.`evidence`, s.`updatedAtMs`
                FROM `artist_artwork_selection` s
                INNER JOIN `artist_artwork_asset` a ON a.`assetId` = s.`assetId`
            """.trimIndent())
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_artist_artwork_link_assetId` ON `artist_artwork_link` (`assetId`)")
        }

        /**
         * R11.5.2: keep the album IDs that supported an association. Legacy rows stay
         * exact-match only; the column is additive and never rewrites or deletes data.
         */
        val MIGRATION_5_6 = Migration(5, 6) { db ->
            db.execSQL("ALTER TABLE `artist_enrichment` ADD COLUMN `albumIdsJson` TEXT")
        }

        /**
         * R11.5.2-R: separate the durable association key from the current membership
         * fingerprint. The canonical albumGroupKey is preserved; only a mutable lookup
         * column is added. Historical artwork revisions based on wall-clock timestamps
         * are normalized to their relative order so a huge legacy timestamp cannot
         * outrank every future transaction-assigned revision forever.
         */
        val MIGRATION_6_7 = Migration(6, 7) { db ->
            db.execSQL("ALTER TABLE `artist_enrichment` ADD COLUMN `membershipFingerprint` TEXT")
            db.execSQL(
                "UPDATE `artist_enrichment` SET `membershipFingerprint` = `albumGroupKey` " +
                    "WHERE `membershipFingerprint` IS NULL",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS " +
                    "`index_artist_enrichment_libraryId_creditKey_membershipFingerprint` " +
                    "ON `artist_enrichment` (`libraryId`, `creditKey`, `membershipFingerprint`)",
            )
            db.execSQL(
                "UPDATE `artist_artwork_selection` SET `revision` = (" +
                    "SELECT COUNT(*) FROM `artist_artwork_selection` s2 " +
                    "WHERE s2.`libraryId` = `artist_artwork_selection`.`libraryId` " +
                    "AND (s2.`updatedAtMs` < `artist_artwork_selection`.`updatedAtMs` " +
                    "OR (s2.`updatedAtMs` = `artist_artwork_selection`.`updatedAtMs` " +
                    "AND s2.`rowid` <= `artist_artwork_selection`.`rowid`)))",
            )
            db.execSQL("DELETE FROM `artist_artwork_revision`")
            db.execSQL(
                "INSERT INTO `artist_artwork_revision` (`libraryId`, `revision`, `updatedAtMs`) " +
                    "SELECT `libraryId`, COUNT(*), COALESCE(MAX(`updatedAtMs`), 0) " +
                    "FROM `artist_artwork_selection` GROUP BY `libraryId`",
            )
        }

        /** R11.5: monotonic per-library artwork revision, independent of wall-clock time. */
        val MIGRATION_4_5 = Migration(4, 5) { db ->
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS `artist_artwork_revision` (
                    `libraryId` TEXT NOT NULL,
                    `revision` INTEGER NOT NULL,
                    `updatedAtMs` INTEGER NOT NULL,
                    PRIMARY KEY(`libraryId`)
                )
            """.trimIndent())
            db.execSQL("""
                INSERT OR IGNORE INTO `artist_artwork_revision` (`libraryId`, `revision`, `updatedAtMs`)
                SELECT `libraryId`, COUNT(*), COALESCE(MAX(`updatedAtMs`), 0)
                FROM `artist_artwork_selection`
                GROUP BY `libraryId`
            """.trimIndent())
        }
    }
}
