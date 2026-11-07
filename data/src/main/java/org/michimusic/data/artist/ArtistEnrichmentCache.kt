package org.michimusic.data.artist

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Index
import androidx.room.Transaction
import androidx.room.Upsert
import androidx.room.migration.Migration
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow
import org.json.JSONArray
import java.security.MessageDigest

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
     * Immutable legacy creation context. Runtime lookup never treats this JSON as membership;
     * v8→v9 uses a reproducible hash only to classify migration evidence conservatively.
     * A temporary view or profile refresh must never broaden it.
     */
    val albumIdsJson: String? = null,
    /**
     * Legacy observed membership retained for non-destructive compatibility. It is not
     * an identity or lookup authority; exact-context choices live in
     * [ArtistAssociationResolutionRecord].
     */
    val membershipFingerprint: String? = null,
)

/** Stable library-local aggregate. albumGroupKey is retained as the association id column. */
@Entity(
    tableName = "durable_artist_association",
    primaryKeys = ["libraryId", "creditKey", "associationId"],
    indices = [Index(value = ["libraryId", "creditKey", "identityAssociationId"])],
    foreignKeys = [
        ForeignKey(
            entity = ArtistEnrichmentRecord::class,
            parentColumns = ["libraryId", "creditKey", "albumGroupKey"],
            childColumns = ["libraryId", "creditKey", "identityAssociationId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class DurableArtistAssociationRecord(
    val libraryId: String,
    val creditKey: String,
    val associationId: String,
    val identityAssociationId: String,
    val createdAtMs: Long,
    val updatedAtMs: Long,
)

/** Sole authoritative ownership of one album + credited component. */
@Entity(
    tableName = "artist_album_credit_binding",
    primaryKeys = ["libraryId", "albumKey", "creditKey"],
    indices = [Index(value = ["libraryId", "creditKey", "associationId"])],
    foreignKeys = [
        ForeignKey(
            entity = DurableArtistAssociationRecord::class,
            parentColumns = ["libraryId", "creditKey", "associationId"],
            childColumns = ["libraryId", "creditKey", "associationId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class ArtistAlbumCreditBindingRecord(
    val libraryId: String,
    val albumKey: String,
    val creditKey: String,
    val associationId: String,
    val evidenceType: String,
    val confirmedAtMs: Long,
)

/** Observation supporting a possible binding. Non-confirmed rows never assign identity. */
@Entity(
    tableName = "artist_membership_evidence",
    primaryKeys = ["libraryId", "albumKey", "creditKey", "associationId", "evidenceType", "evidenceKey"],
    indices = [
        Index(value = ["libraryId", "albumKey", "creditKey"]),
        Index(value = ["libraryId", "creditKey", "associationId"]),
    ],
    foreignKeys = [
        ForeignKey(
            entity = DurableArtistAssociationRecord::class,
            parentColumns = ["libraryId", "creditKey", "associationId"],
            childColumns = ["libraryId", "creditKey", "associationId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class ArtistMembershipEvidenceRecord(
    val libraryId: String,
    val albumKey: String,
    val creditKey: String,
    val associationId: String,
    val evidenceType: String,
    val evidenceKey: String,
    val status: String,
    val source: String,
    val observedAtMs: Long,
    val confirmedAtMs: Long?,
)

/** A user-confirmed choice among bindings visible in one exact context fingerprint. */
@Entity(
    tableName = "artist_association_resolution",
    primaryKeys = ["libraryId", "creditKey", "membershipFingerprint"],
    indices = [Index(value = ["libraryId", "creditKey", "chosenAlbumGroupKey"])],
)
data class ArtistAssociationResolutionRecord(
    val libraryId: String,
    val creditKey: String,
    val membershipFingerprint: String,
    val chosenAlbumGroupKey: String,
    val chosenMbid: String,
    val evidence: String,
    val confirmedAtMs: Long,
    /** Sorted album keys for precise invalidation; null only for pre-v9 legacy decisions. */
    val contextAlbumKeysJson: String? = null,
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

    /** Recovery candidates for one local credit. Ambiguous results must fail closed. */
    @Query("SELECT * FROM artist_enrichment WHERE libraryId = :libraryId AND creditKey = :creditKey")
    suspend fun getAllForCredit(libraryId: String, creditKey: String): List<ArtistEnrichmentRecord>

    @Query("SELECT * FROM durable_artist_association WHERE libraryId = :libraryId AND creditKey = :creditKey AND associationId = :associationId LIMIT 1")
    suspend fun getDurableAssociation(
        libraryId: String,
        creditKey: String,
        associationId: String,
    ): DurableArtistAssociationRecord?

    @Query("SELECT * FROM durable_artist_association WHERE libraryId = :libraryId AND creditKey = :creditKey")
    suspend fun getDurableAssociationsForCredit(
        libraryId: String,
        creditKey: String,
    ): List<DurableArtistAssociationRecord>

    @Upsert
    suspend fun putDurableAssociation(record: DurableArtistAssociationRecord)

    @Query("SELECT * FROM artist_album_credit_binding WHERE libraryId = :libraryId AND creditKey = :creditKey AND albumKey IN (:albumKeys)")
    suspend fun getAlbumCreditBindings(
        libraryId: String,
        creditKey: String,
        albumKeys: List<String>,
    ): List<ArtistAlbumCreditBindingRecord>

    @Query("SELECT * FROM artist_album_credit_binding WHERE libraryId = :libraryId AND albumKey = :albumKey AND creditKey = :creditKey LIMIT 1")
    suspend fun getAlbumCreditBinding(
        libraryId: String,
        albumKey: String,
        creditKey: String,
    ): ArtistAlbumCreditBindingRecord?

    @Upsert
    suspend fun putAlbumCreditBinding(record: ArtistAlbumCreditBindingRecord)

    @Upsert
    suspend fun putMembershipEvidence(record: ArtistMembershipEvidenceRecord)

    @Query("SELECT * FROM artist_membership_evidence WHERE libraryId = :libraryId AND albumKey = :albumKey AND creditKey = :creditKey")
    suspend fun getMembershipEvidence(
        libraryId: String,
        albumKey: String,
        creditKey: String,
    ): List<ArtistMembershipEvidenceRecord>

    @Query("UPDATE artist_membership_evidence SET status = 'SUPERSEDED', confirmedAtMs = NULL WHERE libraryId = :libraryId AND albumKey = :albumKey AND creditKey = :creditKey AND associationId = :associationId AND status = 'CONFIRMED'")
    suspend fun supersedeConfirmedMembershipEvidence(
        libraryId: String,
        albumKey: String,
        creditKey: String,
        associationId: String,
    )

    @Query("SELECT * FROM artist_association_resolution WHERE libraryId = :libraryId AND creditKey = :creditKey AND membershipFingerprint = :membershipFingerprint LIMIT 1")
    suspend fun getAssociationResolution(
        libraryId: String,
        creditKey: String,
        membershipFingerprint: String,
    ): ArtistAssociationResolutionRecord?

    @Query("SELECT * FROM artist_association_resolution WHERE libraryId = :libraryId AND creditKey = :creditKey")
    suspend fun getAssociationResolutionsForCredit(
        libraryId: String,
        creditKey: String,
    ): List<ArtistAssociationResolutionRecord>

    @Query("DELETE FROM artist_association_resolution WHERE libraryId = :libraryId AND creditKey = :creditKey AND membershipFingerprint = :membershipFingerprint")
    suspend fun deleteAssociationResolution(
        libraryId: String,
        creditKey: String,
        membershipFingerprint: String,
    )

    @Upsert
    suspend fun putAssociationResolution(resolution: ArtistAssociationResolutionRecord)

    @Query("DELETE FROM artist_association_resolution WHERE libraryId = :libraryId AND creditKey = :creditKey AND chosenAlbumGroupKey = :albumGroupKey")
    suspend fun deleteAssociationResolutionsForGroup(
        libraryId: String,
        creditKey: String,
        albumGroupKey: String,
    ): Int

    /**
     * Association rows must never use SQLite REPLACE: REPLACE is delete+insert and can
     * silently substitute a confirmed MBID while cascading identity-scoped children.
     * Callers enforce the MBID invariant inside a Room transaction before this upsert.
     */
    @Upsert
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
        DurableArtistAssociationRecord::class,
        ArtistAlbumCreditBindingRecord::class,
        ArtistMembershipEvidenceRecord::class,
        ArtistAssociationResolutionRecord::class,
        ArtistTrackEvidenceRecord::class,
        ArtistArtworkAssetRecord::class,
        ArtistArtworkSelectionRecord::class,
        ArtistArtworkLinkRecord::class,
        ArtistArtworkRevisionRecord::class,
    ],
    version = 9,
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

        /** R11.5.2-R3: persist an explicit choice without deleting either historical row. */
        val MIGRATION_7_8 = Migration(7, 8) { db ->
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `artist_association_resolution` (
                    `libraryId` TEXT NOT NULL,
                    `creditKey` TEXT NOT NULL,
                    `membershipFingerprint` TEXT NOT NULL,
                    `chosenAlbumGroupKey` TEXT NOT NULL,
                    `chosenMbid` TEXT NOT NULL,
                    `evidence` TEXT NOT NULL,
                    `confirmedAtMs` INTEGER NOT NULL,
                    PRIMARY KEY(`libraryId`, `creditKey`, `membershipFingerprint`)
                )
                """.trimIndent(),
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS " +
                    "`index_artist_association_resolution_libraryId_creditKey_chosenAlbumGroupKey` " +
                    "ON `artist_association_resolution` (`libraryId`, `creditKey`, `chosenAlbumGroupKey`)",
            )
        }

        /**
         * Final domain split: identity facts stay in artist_enrichment, while stable
         * associations, authoritative album-credit bindings and non-authoritative evidence
         * become distinct persisted concepts. No row, profile or artwork table is rewritten.
         */
        val MIGRATION_8_9 = Migration(8, 9) { db ->
            db.execSQL(
                "ALTER TABLE `artist_association_resolution` ADD COLUMN `contextAlbumKeysJson` TEXT",
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `durable_artist_association` (
                    `libraryId` TEXT NOT NULL, `creditKey` TEXT NOT NULL,
                    `associationId` TEXT NOT NULL, `identityAssociationId` TEXT NOT NULL,
                    `createdAtMs` INTEGER NOT NULL, `updatedAtMs` INTEGER NOT NULL,
                    PRIMARY KEY(`libraryId`, `creditKey`, `associationId`),
                    FOREIGN KEY(`libraryId`, `creditKey`, `identityAssociationId`)
                        REFERENCES `artist_enrichment`(`libraryId`, `creditKey`, `albumGroupKey`)
                        ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """.trimIndent(),
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_durable_artist_association_libraryId_creditKey_identityAssociationId` " +
                    "ON `durable_artist_association` (`libraryId`, `creditKey`, `identityAssociationId`)",
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `artist_album_credit_binding` (
                    `libraryId` TEXT NOT NULL, `albumKey` TEXT NOT NULL,
                    `creditKey` TEXT NOT NULL, `associationId` TEXT NOT NULL,
                    `evidenceType` TEXT NOT NULL, `confirmedAtMs` INTEGER NOT NULL,
                    PRIMARY KEY(`libraryId`, `albumKey`, `creditKey`),
                    FOREIGN KEY(`libraryId`, `creditKey`, `associationId`)
                        REFERENCES `durable_artist_association`(`libraryId`, `creditKey`, `associationId`)
                        ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """.trimIndent(),
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_artist_album_credit_binding_libraryId_creditKey_associationId` " +
                    "ON `artist_album_credit_binding` (`libraryId`, `creditKey`, `associationId`)",
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `artist_membership_evidence` (
                    `libraryId` TEXT NOT NULL, `albumKey` TEXT NOT NULL,
                    `creditKey` TEXT NOT NULL, `associationId` TEXT NOT NULL,
                    `evidenceType` TEXT NOT NULL, `evidenceKey` TEXT NOT NULL,
                    `status` TEXT NOT NULL, `source` TEXT NOT NULL,
                    `observedAtMs` INTEGER NOT NULL, `confirmedAtMs` INTEGER,
                    PRIMARY KEY(`libraryId`, `albumKey`, `creditKey`, `associationId`, `evidenceType`, `evidenceKey`),
                    FOREIGN KEY(`libraryId`, `creditKey`, `associationId`)
                        REFERENCES `durable_artist_association`(`libraryId`, `creditKey`, `associationId`)
                        ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """.trimIndent(),
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_artist_membership_evidence_libraryId_albumKey_creditKey` " +
                    "ON `artist_membership_evidence` (`libraryId`, `albumKey`, `creditKey`)",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_artist_membership_evidence_libraryId_creditKey_associationId` " +
                    "ON `artist_membership_evidence` (`libraryId`, `creditKey`, `associationId`)",
            )

            val rows = db.query(
                "SELECT libraryId, creditKey, albumGroupKey, savedAtMs, source, albumIdsJson " +
                    "FROM artist_enrichment",
            )
            rows.use { cursor ->
                val libraryColumn = cursor.getColumnIndexOrThrow("libraryId")
                val creditColumn = cursor.getColumnIndexOrThrow("creditKey")
                val associationColumn = cursor.getColumnIndexOrThrow("albumGroupKey")
                val savedColumn = cursor.getColumnIndexOrThrow("savedAtMs")
                val sourceColumn = cursor.getColumnIndexOrThrow("source")
                val albumsColumn = cursor.getColumnIndexOrThrow("albumIdsJson")
                while (cursor.moveToNext()) {
                    val libraryId = cursor.getString(libraryColumn)
                    val creditKey = cursor.getString(creditColumn)
                    val associationId = cursor.getString(associationColumn)
                    val savedAt = cursor.getLong(savedColumn)
                    val source = cursor.getString(sourceColumn)
                    db.execSQL(
                        "INSERT OR IGNORE INTO durable_artist_association " +
                            "(libraryId,creditKey,associationId,identityAssociationId,createdAtMs,updatedAtMs) " +
                            "VALUES(?,?,?,?,?,?)",
                        arrayOf(libraryId, creditKey, associationId, associationId, savedAt, savedAt),
                    )
                    if (cursor.isNull(albumsColumn)) continue
                    val albumKeys = runCatching {
                        val array = JSONArray(cursor.getString(albumsColumn))
                        List(array.length()) { index -> array.getString(index) }
                            .filter(String::isNotBlank)
                            .distinct()
                            .sorted()
                    }.getOrDefault(emptyList())
                    val trustworthy = albumKeys.isNotEmpty() &&
                        membershipFingerprint(albumKeys) == associationId &&
                        source.startsWith("musicbrainz-manual-selection")
                    albumKeys.forEach { albumKey ->
                        val status = if (trustworthy) "CONFIRMED" else "INSUFFICIENT"
                        db.execSQL(
                            "INSERT OR IGNORE INTO artist_membership_evidence " +
                                "(libraryId,albumKey,creditKey,associationId,evidenceType,evidenceKey,status,source,observedAtMs,confirmedAtMs) " +
                                "VALUES(?,?,?,?,?,?,?,?,?,?)",
                            arrayOf<Any?>(
                                libraryId, albumKey, creditKey, associationId,
                                "LEGACY_EXPLICIT_CONTEXT", albumKey, status, "MIGRATION_V8", savedAt,
                                if (trustworthy) savedAt else null,
                            ),
                        )
                        if (trustworthy) {
                            db.execSQL(
                                "INSERT OR IGNORE INTO artist_album_credit_binding " +
                                    "(libraryId,albumKey,creditKey,associationId,evidenceType,confirmedAtMs) " +
                                    "VALUES(?,?,?,?,?,?)",
                                arrayOf(
                                    libraryId, albumKey, creditKey, associationId,
                                    "LEGACY_EXPLICIT_CONTEXT", savedAt,
                                ),
                            )
                        }
                    }
                }
            }
        }

        private fun membershipFingerprint(albumKeys: Collection<String>): String {
            val group = albumKeys.toSortedSet().joinToString("\u0000")
            return MessageDigest.getInstance("SHA-256")
                .digest(group.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it.toInt() and 255) }
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
