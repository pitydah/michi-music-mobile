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

/** Exact, durable per-asset deletion intent. A row is never authority to delete
 * a currently referenced file; the worker must revalidate all current owners.
 */
@Entity(
    tableName = "artist_artwork_cleanup",
    primaryKeys = ["cleanupId", "assetId"],
    indices = [Index(value = ["state", "createdAtMs"])],
)
data class ArtistArtworkCleanupRecord(
    val cleanupId: String,
    val libraryId: String,
    val creditKey: String,
    val associationId: String,
    val assetId: String,
    val localRelativePath: String,
    val sha256: String,
    val createdAtMs: Long,
    val state: String,
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

    /** S1-B1: exact association ownership; never infer the deletion scope from a credit. */
    @Query("SELECT * FROM artist_album_credit_binding WHERE libraryId = :libraryId AND creditKey = :creditKey AND associationId = :associationId")
    suspend fun bindingsForAssociation(
        libraryId: String, creditKey: String, associationId: String,
    ): List<ArtistAlbumCreditBindingRecord>

    @Query("DELETE FROM durable_artist_association WHERE libraryId = :libraryId AND creditKey = :creditKey AND associationId = :associationId")
    suspend fun deleteDurableAssociation(
        libraryId: String, creditKey: String, associationId: String,
    ): Int

    /** Retire queued work inside the same transaction as the binding and photos. */
    @Query("DELETE FROM artist_artwork_auto_outbox WHERE libraryId = :libraryId AND creditKey = :creditKey AND associationId = :associationId")
    suspend fun deleteAutoArtworkForAssociation(
        libraryId: String, creditKey: String, associationId: String,
    ): Int

    @Query("DELETE FROM artist_artwork_source_binding_proof WHERE libraryId = :libraryId AND creditKey = :creditKey AND associationId = :associationId")
    suspend fun deleteSourceBindingProofsForAssociation(
        libraryId: String, creditKey: String, associationId: String,
    ): Int

    /** D36: exact, explicitly bound album credits with durable association identity.
     * Two unrelated people sharing a display name never share an album binding.
     * Inner joins fail closed if the association/identity was deleted.
     */
    @Query("""SELECT binding.libraryId AS libraryId, binding.albumKey AS albumKey,
        binding.creditKey AS creditKey, binding.associationId AS associationId,
        binding.evidenceType AS evidenceType, binding.confirmedAtMs AS confirmedAtMs,
        identity.confirmedMbid AS confirmedMbid
        FROM artist_album_credit_binding AS binding
        INNER JOIN durable_artist_association AS durable
          ON durable.libraryId = binding.libraryId
          AND durable.creditKey = binding.creditKey
          AND durable.associationId = binding.associationId
        INNER JOIN artist_enrichment AS identity
          ON identity.libraryId = durable.libraryId
          AND identity.creditKey = durable.creditKey
          AND identity.albumGroupKey = durable.identityAssociationId
        WHERE binding.libraryId = :libraryId
          AND binding.albumKey IN (:albumKeys) AND binding.creditKey IN (:creditKeys)
        ORDER BY binding.albumKey, binding.creditKey, binding.associationId LIMIT :limit""")
    suspend fun getConfirmedAlbumIdentityRows(
        libraryId: String, albumKeys: List<String>, creditKeys: List<String>, limit: Int = Int.MAX_VALUE,
    ): List<ArtistConfirmedAlbumIdentityRow>

    /** Diagnostic only: embedded/automatic MBID observations never grant an identity. */
    @Query("""SELECT * FROM artist_track_evidence
        WHERE libraryId = :libraryId AND trackId IN (:trackIds)
          AND field IN ('ARTIST_MBID', 'AUTO_ARTIST_MBID')
        ORDER BY trackId, field, ordinal LIMIT :limit""")
    suspend fun getUntrustedArtistTagMbidsForTracks(
        libraryId: String, trackIds: List<String>, limit: Int = Int.MAX_VALUE,
    ): List<ArtistTrackEvidenceRecord>

    /** Exact, manually confirmed track-only identity; no implicit album assignment. */
    @Query("SELECT * FROM artist_explicit_track_identity WHERE libraryId = :libraryId AND trackId IN (:trackIds) LIMIT :limit")
    suspend fun explicitTrackIdentities(libraryId: String, trackIds: List<String>, limit: Int = Int.MAX_VALUE): List<ArtistExplicitTrackIdentityRecord>

    @Query("SELECT * FROM artist_explicit_track_identity WHERE libraryId = :libraryId AND albumKey = :albumId AND trackId = :trackId AND creditKey = :creditKey LIMIT 1")
    suspend fun explicitTrackIdentity(libraryId: String, albumId: String, trackId: String,
        creditKey: String): ArtistExplicitTrackIdentityRecord?

    /** A first decision is insert-only: unexpected duplicate requests abort transaction. */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertExplicitTrackIdentity(row: ArtistExplicitTrackIdentityRecord)

    /** D46: re-attest an 18->19 legacy row with matching user-chosen MBID.
     * Never update a verified row, an altered root, or another identity.
     */
    @Query("""UPDATE artist_explicit_track_identity SET
        sourceContentSha256 = :sha256, sourceContentBytes = :byteCount,
        revision = revision + 1, confirmedAtEpochMs = :nowMs
        WHERE libraryId = :libraryId AND albumKey = :albumKey AND trackId = :trackId
          AND creditKey = :creditKey AND trackKeySha256 = :trackKeySha256
          AND confirmedMbid = :confirmedMbid AND rootSourceId = :rootSourceId
          AND rootEpoch = :rootEpoch AND evidence = 'USER_EXPLICIT_TRACK_CONFIRMATION'
          AND sourceContentSha256 = '' AND sourceContentBytes = 0
          AND revision BETWEEN 1 AND 9223372036854775806""")
    suspend fun reverifyLegacyExplicitTrackIdentityProof(
        libraryId: String, albumKey: String, trackId: String, creditKey: String,
        trackKeySha256: String, confirmedMbid: String, rootSourceId: String,
        rootEpoch: Long, sha256: String, byteCount: Long, nowMs: Long,
    ): Int

    /** Guard for automatic photos: a durable identity with no album ownership is insufficient. */
    @Query("SELECT COUNT(*) FROM artist_album_credit_binding WHERE libraryId = :libraryId AND creditKey = :creditKey AND associationId = :associationId")
    suspend fun countConfirmedBindingsForAssociation(
        libraryId: String,
        creditKey: String,
        associationId: String,
    ): Int

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

    /** Delete only AUTO role evidence. Explicit user-supplied rows are immutable here. */
    @Query("""DELETE FROM artist_track_evidence WHERE libraryId = :libraryId
        AND trackId IN (:trackIds)
        AND field IN ('AUTO_ARTIST_MBID', 'AUTO_ALBUM_ARTIST_MBID', 'AUTO_RECORDING_MBID', 'AUTO_RELEASE_MBID')""")
    suspend fun clearObservedArtistMbidsForTracks(libraryId: String, trackIds: List<String>): Int

    /** Read a whole bounded page in one query to avoid N queries or redundant writes. */
    @Query("""SELECT * FROM artist_track_evidence WHERE libraryId = :libraryId
        AND trackId IN (:trackIds) AND field IN ('AUTO_ARTIST_MBID', 'AUTO_ALBUM_ARTIST_MBID', 'AUTO_RECORDING_MBID', 'AUTO_RELEASE_MBID')""")
    suspend fun observedArtistMbidsForTracks(
        libraryId: String, trackIds: List<String>,
    ): List<ArtistTrackEvidenceRecord>

    /**
     * Atomic, idempotent replacement of AUTO evidence only. When metadata is unchanged,
     * preserve rows and timestamps instead of DELETE+INSERT on every library refresh.
     * Ignore observation time when comparing semantics; compare source to avoid cross-root reuse.
     */
    @Transaction
    suspend fun replaceObservedArtistMbidsForBatch(
        libraryId: String, trackIds: List<String>, rows: List<ArtistTrackEvidenceRecord>,
    ): Boolean {
        require(libraryId.isNotBlank() && trackIds.isNotEmpty() && trackIds.size <= 64 &&
            trackIds.distinct().size == trackIds.size)
        val authorized = trackIds.toSet()
        require(rows.all { row ->
            row.libraryId == libraryId && row.trackId in authorized &&
                row.field in setOf("AUTO_ARTIST_MBID", "AUTO_ALBUM_ARTIST_MBID",
                    "AUTO_RECORDING_MBID", "AUTO_RELEASE_MBID") &&
                row.ordinal in 0..7 && row.source.startsWith("EXISTING_TAG_READ:")
        })
        fun key(row: ArtistTrackEvidenceRecord) = listOf(
            row.trackId, row.field, row.ordinal.toString(), row.value, row.source,
        )
        val proposed = rows.map(::key).toSet()
        require(proposed.size == rows.size) { "Repeated MBID evidence key in one batch" }
        if (observedArtistMbidsForTracks(libraryId, trackIds).map(::key).toSet() == proposed) {
            return false
        }
        clearObservedArtistMbidsForTracks(libraryId, trackIds)
        if (rows.isNotEmpty()) insertTrackEvidence(rows)
        return true
    }

    /** The receipt is not an identity, an association, or permission for automatic artwork. */
    @Upsert
    suspend fun putCreditReviewReceipt(receipt: ArtistCreditReviewReceiptRecord)

    @Query("""SELECT * FROM artist_credit_review_receipt
        WHERE libraryId = :libraryId AND sourceId = :sourceId AND creditKey = :creditKey
          AND albumKey = :albumKey AND trackId = :trackId LIMIT 1""")
    suspend fun getCreditReviewReceipt(
        libraryId: String, sourceId: String, creditKey: String,
        albumKey: String, trackId: String,
    ): ArtistCreditReviewReceiptRecord?

    @Query("""DELETE FROM artist_credit_review_receipt
        WHERE libraryId = :libraryId AND verifiedAtMs < :cutoff""")
    suspend fun pruneExpiredCreditReviewReceipts(libraryId: String, cutoff: Long): Int

    @Query("SELECT COUNT(*) FROM artist_enrichment WHERE libraryId = :libraryId")
    suspend fun countForLibrary(libraryId: String): Int

    @Query("SELECT * FROM artist_artwork_asset WHERE assetId = :assetId LIMIT 1")
    suspend fun getArtworkAsset(assetId: String): ArtistArtworkAssetRecord?

    /** D10/D14: only a *real*, currently-unreferenced asset may be cleaned up. */
    @Query("SELECT DISTINCT assetId FROM artist_artwork_link WHERE libraryId = :libraryId AND creditKey = :creditKey AND albumGroupKey = :associationId AND confirmedMbid = :expectedMbid")
    suspend fun artworkLinkAssetsForAssociation(
        libraryId: String, creditKey: String, associationId: String, expectedMbid: String,
    ): List<String>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertArtworkCleanup(record: ArtistArtworkCleanupRecord): Long

    @Query("SELECT * FROM artist_artwork_cleanup WHERE state = 'PENDING' ORDER BY createdAtMs ASC, cleanupId ASC, assetId ASC LIMIT :limit")
    suspend fun pendingArtworkCleanup(limit: Int): List<ArtistArtworkCleanupRecord>

    @Query("""SELECT * FROM artist_artwork_cleanup WHERE state = 'PENDING' AND
        (createdAtMs > :afterMs OR (createdAtMs = :afterMs AND
        (cleanupId > :afterCleanup OR (cleanupId = :afterCleanup AND assetId > :afterAsset))))
        ORDER BY createdAtMs ASC, cleanupId ASC, assetId ASC LIMIT :limit""")
    suspend fun pendingArtworkCleanupAfter(afterMs: Long, afterCleanup: String, afterAsset: String,
        limit: Int): List<ArtistArtworkCleanupRecord>

    @Query("UPDATE artist_artwork_cleanup SET state = :state WHERE cleanupId = :cleanupId AND assetId = :assetId AND state = 'PENDING'")
    suspend fun finishArtworkCleanup(cleanupId: String, assetId: String, state: String): Int

    @Query("SELECT COUNT(*) FROM artist_artwork_selection WHERE assetId = :assetId")
    suspend fun countSelectionReferencesToAsset(assetId: String): Int

    @Query("SELECT COUNT(*) FROM artist_artwork_link WHERE assetId = :assetId")
    suspend fun countLinkReferencesToAsset(assetId: String): Int

    @Query("SELECT COUNT(*) FROM artist_artwork_asset WHERE localRelativePath = :path AND assetId != :assetId")
    suspend fun countOtherArtworkAssetsForPath(assetId: String, path: String): Int

    @Query("DELETE FROM artist_artwork_asset WHERE assetId = :assetId AND NOT EXISTS (SELECT 1 FROM artist_artwork_selection WHERE assetId = :assetId) AND NOT EXISTS (SELECT 1 FROM artist_artwork_link WHERE assetId = :assetId)")
    suspend fun deleteArtworkAssetIfUnreferenced(assetId: String): Int

    @Query("SELECT * FROM artist_artwork_asset")
    suspend fun getAllArtworkAssets(): List<ArtistArtworkAssetRecord>

    @Query("DELETE FROM artist_artwork_asset WHERE assetId NOT IN (SELECT DISTINCT assetId FROM artist_artwork_selection) AND assetId NOT IN (SELECT DISTINCT assetId FROM artist_artwork_link)")
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

    /** A legacy slot with a different MBID must not be silently left behind. */
    @Query("SELECT COUNT(*) FROM artist_artwork_selection WHERE libraryId = :libraryId AND creditKey = :creditKey AND albumGroupKey = :associationId AND confirmedMbid != :expectedMbid")
    suspend fun countForeignArtworkSelections(
        libraryId: String, creditKey: String, associationId: String, expectedMbid: String,
    ): Int

    @Query("SELECT COUNT(*) FROM artist_artwork_link WHERE libraryId = :libraryId AND creditKey = :creditKey AND albumGroupKey = :associationId AND confirmedMbid != :expectedMbid")
    suspend fun countForeignArtworkLinks(
        libraryId: String, creditKey: String, associationId: String, expectedMbid: String,
    ): Int

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

    /** Durable, exact evidence that survives provider latency. Never match by credit alone. */
    @Query("""SELECT COUNT(*) FROM artist_artwork_auto_outbox o
        INNER JOIN artist_album_credit_binding b ON b.libraryId = o.libraryId
            AND b.albumKey = o.albumKey AND b.creditKey = o.creditKey
            AND b.associationId = o.associationId
        WHERE o.libraryId = :libraryId AND o.creditKey = :creditKey
          AND o.associationId = :associationId AND o.sourceId = :expectedSource
          AND o.state = 'PENDING' AND o.updatedAtMs <= :nowMs""")
    suspend fun countAuthorizedAutoArtworkEvents(
        libraryId: String, creditKey: String, associationId: String,
        expectedSource: String, nowMs: Long,
    ): Int

    /** Protect manual slots even if the old selection carries a different MBID. */
    @Query("""SELECT COUNT(*) FROM artist_artwork_selection
        WHERE libraryId = :libraryId AND creditKey = :creditKey
          AND albumGroupKey = :associationId AND slot = :slot AND manuallyChosen = 1""")
    suspend fun countManualArtworkForSlot(
        libraryId: String, creditKey: String, associationId: String, slot: String,
    ): Int

    /** Under the final artwork write transaction, source-specific proof is mandatory. */
    @Query("""SELECT COUNT(*) FROM artist_artwork_auto_outbox o
        INNER JOIN artist_artwork_source_binding_proof p
          ON p.libraryId = o.libraryId AND p.sourceId = o.sourceId
          AND p.albumKey = o.albumKey AND p.creditKey = o.creditKey
          AND p.associationId = o.associationId
        INNER JOIN artist_album_credit_binding b
          ON b.libraryId = p.libraryId AND b.albumKey = p.albumKey
          AND b.creditKey = p.creditKey AND b.associationId = p.associationId
          AND b.confirmedAtMs = p.confirmedAtMs
        WHERE o.libraryId = :libraryId AND o.sourceId = :sourceId
          AND o.creditKey = :creditKey AND o.associationId = :associationId
          AND o.state = 'PENDING' AND o.updatedAtMs <= :nowMs""")
    suspend fun countProvenAutomatedArtworkEvents(
        libraryId: String, sourceId: String, creditKey: String,
        associationId: String, nowMs: Long,
    ): Int

    /**
     * Last-write-wins is NOT acceptable here: a manual choice, identity replacement
     * or binding removal during HTTP invalidates the automatic write. All checks and
     * the asset/link/selection commit share the same Room transaction.
     * A manually initiated save continues to use persistArtwork(), unchanged.
     */
    @Transaction
    suspend fun persistArtworkAutomated(
        asset: ArtistArtworkAssetRecord,
        link: ArtistArtworkLinkRecord,
        selection: ArtistArtworkSelectionRecord,
        expectedSource: String,
    ): Long? {
        if (expectedSource.isBlank() || selection.manuallyChosen ||
            asset.confirmedMbid != selection.confirmedMbid ||
            link.confirmedMbid != selection.confirmedMbid ||
            link.assetId != selection.assetId || asset.assetId != selection.assetId ||
            link.verifiedQid != asset.verifiedQid
        ) return null
        val association = getDurableAssociation(
            selection.libraryId, selection.creditKey, selection.albumGroupKey,
        ) ?: return null
        val identity = get(
            selection.libraryId, selection.creditKey, association.identityAssociationId,
        ) ?: return null
        if (identity.confirmedMbid != selection.confirmedMbid ||
            identity.source == "musicbrainz-manual-selection-pending") return null
        val identityQid = runCatching {
            org.json.JSONObject(identity.profileJson).optString("wikidataQid")
        }.getOrNull()
        if (identityQid.isNullOrBlank() || identityQid != asset.verifiedQid) return null
        if (countAuthorizedAutoArtworkEvents(
                selection.libraryId, selection.creditKey, association.associationId,
                expectedSource, System.currentTimeMillis(),
            ) < 1) return null
        // A root-specific receipt must match the CURRENT binding confirmation.
        // Catalog membership alone never authorizes an old artist binding.
        val authorizedProofs = countProvenAutomatedArtworkEvents(
            selection.libraryId, expectedSource, selection.creditKey,
            association.associationId, System.currentTimeMillis(),
        )
        if (authorizedProofs < 1) return null
        // This covers an interactive choice made WHILE a candidate was downloading.
        val existing = getArtworkSelections(
            selection.libraryId, selection.creditKey,
            selection.albumGroupKey, selection.confirmedMbid,
        ).firstOrNull { it.slot == selection.slot }
        if (existing?.manuallyChosen == true || countManualArtworkForSlot(
                selection.libraryId, selection.creditKey, selection.albumGroupKey, selection.slot,
            ) > 0) return null
        putArtworkAsset(asset)
        deleteOtherArtworkLinks(
            link.libraryId, link.creditKey, link.albumGroupKey, link.slot, link.confirmedMbid,
        )
        putArtworkLink(link)
        val revision = bumpArtworkRevisionAndReturn(selection.libraryId, selection.updatedAtMs)
        putArtworkSelection(selection.copy(revision = revision))
        return revision
    }

    /** Read ALL MBIDs for the exact slot; a different owner must never be overwritten. */
    @Query("""SELECT * FROM artist_artwork_selection WHERE libraryId = :libraryId
        AND creditKey = :creditKey AND albumGroupKey = :associationId AND slot = :slot LIMIT 1""")
    suspend fun artworkSelectionForExactSlot(
        libraryId: String, creditKey: String, associationId: String, slot: String,
    ): ArtistArtworkSelectionRecord?

    /** D15: final manual authorisation guard, checked alongside the Room write atomically. */
    @Transaction
    suspend fun persistArtworkManuallyAuthorized(
        asset: ArtistArtworkAssetRecord,
        link: ArtistArtworkLinkRecord,
        selection: ArtistArtworkSelectionRecord,
        expectedAssetId: String?,
        expectedRevision: Long?,
        replaceManualAuthorized: Boolean,
    ): Long? {
        if (!selection.manuallyChosen ||
            (expectedAssetId == null) != (expectedRevision == null) ||
            selection.assetId != asset.assetId || selection.assetId != link.assetId ||
            asset.confirmedMbid != selection.confirmedMbid ||
            link.confirmedMbid != selection.confirmedMbid ||
            selection.libraryId != link.libraryId || selection.creditKey != link.creditKey ||
            selection.albumGroupKey != link.albumGroupKey || selection.slot != link.slot ||
            selection.focusX !in 0f..1f || selection.focusY !in 0f..1f
        ) return null
        val association = getDurableAssociation(
            selection.libraryId, selection.creditKey, selection.albumGroupKey,
        ) ?: return null
        val identity = get(
            selection.libraryId, selection.creditKey, association.identityAssociationId,
        ) ?: return null
        if (identity.confirmedMbid != selection.confirmedMbid ||
            identity.source == "musicbrainz-manual-selection-pending" ||
            countConfirmedBindingsForAssociation(
                selection.libraryId, selection.creditKey, association.associationId,
            ) < 1 ||
            countForeignArtworkSelections(
                selection.libraryId, selection.creditKey, association.associationId, selection.confirmedMbid,
            ) > 0 ||
            countForeignArtworkLinks(
                selection.libraryId, selection.creditKey, association.associationId, selection.confirmedMbid,
            ) > 0
        ) return null
        val existing = artworkSelectionForExactSlot(
            selection.libraryId, selection.creditKey, selection.albumGroupKey, selection.slot,
        )
        if (existing?.assetId != expectedAssetId || existing?.revision != expectedRevision ||
            (existing != null && existing.confirmedMbid != selection.confirmedMbid) ||
            (existing?.manuallyChosen == true && !replaceManualAuthorized)
        ) return null
        // No expected version can authorize replacing a link left by an old owner.
        val oldLink = getArtworkLink(
            link.libraryId, link.creditKey, link.albumGroupKey, link.slot, link.confirmedMbid,
        )
        if (oldLink != null && oldLink.assetId != existing?.assetId) return null
        putArtworkAsset(asset)
        deleteOtherArtworkLinks(
            link.libraryId, link.creditKey, link.albumGroupKey, link.slot, link.confirmedMbid,
        )
        putArtworkLink(link)
        val revision = bumpArtworkRevisionAndReturn(selection.libraryId, selection.updatedAtMs)
        putArtworkSelection(selection.copy(revision = revision))
        return revision
    }

    @Transaction
    suspend fun persistArtwork(
        asset: ArtistArtworkAssetRecord,
        link: ArtistArtworkLinkRecord,
        selection: ArtistArtworkSelectionRecord,
    ): Long {
        // A picker opened before deletion must not resurrect its old slots.
        val association = getDurableAssociation(
            selection.libraryId, selection.creditKey, selection.albumGroupKey,
        ) ?: error("La asociación artística ya no existe.")
        val identity = get(
            selection.libraryId, selection.creditKey, association.identityAssociationId,
        ) ?: error("La identidad artística ya no existe.")
        require(identity.confirmedMbid == selection.confirmedMbid &&
            link.confirmedMbid == selection.confirmedMbid &&
            asset.confirmedMbid == selection.confirmedMbid &&
            countConfirmedBindingsForAssociation(
                selection.libraryId, selection.creditKey, association.associationId,
            ) > 0
        ) { "La asociación o la identidad cambió; vuelve a abrir el selector." }
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

    /** D14 optimistic concurrency: old previews cannot change a newly chosen image. */
    @Query("""UPDATE artist_artwork_selection
        SET focusX = :focusX, focusY = :focusY, updatedAtMs = :nowMs
        WHERE libraryId = :libraryId AND creditKey = :creditKey
          AND albumGroupKey = :albumGroupKey AND slot = :slot
          AND confirmedMbid = :confirmedMbid AND assetId = :expectedAssetId
          AND revision = :expectedRevision""")
    suspend fun updateArtworkFocusGuardedRow(
        libraryId: String, creditKey: String, albumGroupKey: String, slot: String,
        confirmedMbid: String, expectedAssetId: String, expectedRevision: Long,
        focusX: Float, focusY: Float, nowMs: Long,
    ): Int

    /** Keep the compare-and-swap and published artwork revision in one transaction. */
    @Transaction
    suspend fun updateArtworkFocusGuarded(
        libraryId: String, creditKey: String, albumGroupKey: String, slot: String,
        confirmedMbid: String, expectedAssetId: String, expectedRevision: Long,
        focusX: Float, focusY: Float, nowMs: Long,
    ): Int {
        val changed = updateArtworkFocusGuardedRow(
            libraryId, creditKey, albumGroupKey, slot, confirmedMbid,
            expectedAssetId, expectedRevision, focusX, focusY, nowMs,
        )
        if (changed > 0) {
            val revision = bumpArtworkRevisionAndReturn(libraryId, nowMs)
            check(setArtworkSelectionRevision(
                libraryId, creditKey, albumGroupKey, slot, confirmedMbid, revision,
            ) == 1)
        }
        return changed
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
        ArtistArtworkCatalogBaselineRecord::class,
        ArtistArtworkCatalogTrackRecord::class,
        ArtistArtworkAutoOutboxRecord::class,
        ArtistArtworkSourceCatalogCreditRecord::class,
        ArtistArtworkSourceBindingProofRecord::class,
        ArtistCreditReviewReceiptRecord::class,
        ArtistArtworkCleanupRecord::class,
        ArtistBulkSessionRecord::class,
        ArtistBulkReceiptRecord::class,
        ArtistBulkPermissionRecord::class,
        ArtistBulkTerminalReceiptRecord::class,
        ArtistBulkProviderQuotaRecord::class,
        ArtistBulkProviderLeaseRecord::class,
        ArtistBulkProviderObservationRecord::class,
        ArtistExplicitTrackIdentityRecord::class,
    ],
    version = 19,
    exportSchema = true,
)
abstract class ArtistEnrichmentCacheDatabase : RoomDatabase() {
    abstract fun artistEnrichmentDao(): ArtistEnrichmentDao
    abstract fun artistArtworkCatalogJournalDao(): ArtistArtworkCatalogJournalDao
    abstract fun artistBulkSessionDao(): ArtistBulkSessionDao
    abstract fun artistBulkTerminalDao(): ArtistBulkTerminalDao
    abstract fun artistBulkProviderLeaseDao(): ArtistBulkProviderLeaseDao
    abstract fun artistBulkProviderObservationDao(): ArtistBulkProviderObservationDao

    companion object {
        /** D43: explicitly reviewed local track identities; no implicit album/metadata write. */
        val MIGRATION_17_18 = Migration(17, 18) { db ->
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS `artist_explicit_track_identity` (
                    `libraryId` TEXT NOT NULL,
                    `albumKey` TEXT NOT NULL,
                    `trackId` TEXT NOT NULL,
                    `creditKey` TEXT NOT NULL,
                    `trackKeySha256` TEXT NOT NULL,
                    `confirmedMbid` TEXT NOT NULL,
                    `rootSourceId` TEXT NOT NULL,
                    `rootEpoch` INTEGER NOT NULL,
                    `catalogRevision` INTEGER NOT NULL,
                    `evidence` TEXT NOT NULL,
                    `revision` INTEGER NOT NULL,
                    `confirmedAtEpochMs` INTEGER NOT NULL,
                    PRIMARY KEY(`libraryId`, `albumKey`, `trackId`, `creditKey`)
                )
            """.trimIndent())
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_artist_explicit_track_identity_libraryId_trackId` ON `artist_explicit_track_identity` (`libraryId`, `trackId`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_artist_explicit_track_identity_libraryId_creditKey_confirmedMbid` ON `artist_explicit_track_identity` (`libraryId`, `creditKey`, `confirmedMbid`)")
        }

        /** D46: retain old choices but mark pre-hash rows unverified. */
        val MIGRATION_18_19 = Migration(18, 19) { db ->
            db.execSQL("ALTER TABLE `artist_explicit_track_identity` ADD COLUMN `sourceContentSha256` TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE `artist_explicit_track_identity` ADD COLUMN `sourceContentBytes` INTEGER NOT NULL DEFAULT 0")
        }

        /** D28: append-only untrusted observation notes, no HTTP execution authority. */
        val MIGRATION_16_17 = Migration(16, 17) { db ->
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS `artist_bulk_provider_observation` (
                    `leaseId` TEXT NOT NULL,
                    `sessionId` TEXT NOT NULL,
                    `fingerprint` TEXT NOT NULL,
                    `permitId` TEXT NOT NULL,
                    `provider` TEXT NOT NULL,
                    `mbid` TEXT NOT NULL,
                    `observedAtEpochMs` INTEGER NOT NULL,
                    `recordedAtEpochMs` INTEGER NOT NULL,
                    `httpStatusUnverified` INTEGER NOT NULL,
                    `responseBytes` INTEGER NOT NULL,
                    `responseSha256Unverified` TEXT,
                    `contentType` TEXT,
                    `classification` TEXT NOT NULL,
                    `retryAtEpochMs` INTEGER,
                    `origin` TEXT NOT NULL,
                    PRIMARY KEY(`leaseId`),
                    FOREIGN KEY(`leaseId`) REFERENCES `artist_bulk_provider_lease`(`leaseId`)
                        ON UPDATE NO ACTION ON DELETE CASCADE
                )
            """.trimIndent())
            db.execSQL("""
                CREATE INDEX IF NOT EXISTS `index_artist_bulk_provider_observation_sessionId_fingerprint`
                ON `artist_bulk_provider_observation` (`sessionId`, `fingerprint`)
            """.trimIndent())
        }

        /** D27 S4-G5: provider quota/admission journal, NO network and NO profile mutation. */
        val MIGRATION_15_16 = Migration(15, 16) { db ->
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS `artist_bulk_provider_quota` (
                    `provider` TEXT NOT NULL,
                    `nextEligibleAtEpochMs` INTEGER NOT NULL,
                    `updatedAtEpochMs` INTEGER NOT NULL,
                    `revision` INTEGER NOT NULL,
                    PRIMARY KEY(`provider`)
                )
            """.trimIndent())
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS `artist_bulk_provider_lease` (
                    `leaseId` TEXT NOT NULL,
                    `sessionId` TEXT NOT NULL,
                    `fingerprint` TEXT NOT NULL,
                    `permitId` TEXT NOT NULL,
                    `provider` TEXT NOT NULL,
                    `mbid` TEXT NOT NULL,
                    `permissionRevision` INTEGER NOT NULL,
                    `libraryId` TEXT NOT NULL,
                    `sourceId` TEXT NOT NULL,
                    `rootEpoch` INTEGER NOT NULL,
                    `catalogRevision` INTEGER NOT NULL,
                    `libraryRevision` INTEGER NOT NULL,
                    `issuedAtEpochMs` INTEGER NOT NULL,
                    `expiresAtEpochMs` INTEGER NOT NULL,
                    `updatedAtEpochMs` INTEGER NOT NULL,
                    `state` TEXT NOT NULL,
                    `revision` INTEGER NOT NULL,
                    PRIMARY KEY(`leaseId`),
                    FOREIGN KEY(`sessionId`, `fingerprint`) REFERENCES `artist_bulk_permission`(`sessionId`, `fingerprint`)
                        ON UPDATE NO ACTION ON DELETE CASCADE
                )
            """.trimIndent())
            db.execSQL("""
                CREATE UNIQUE INDEX IF NOT EXISTS `index_artist_bulk_provider_lease_permitId`
                ON `artist_bulk_provider_lease` (`permitId`)
            """.trimIndent())
            db.execSQL("""
                CREATE INDEX IF NOT EXISTS `index_artist_bulk_provider_lease_sessionId_fingerprint`
                ON `artist_bulk_provider_lease` (`sessionId`, `fingerprint`)
            """.trimIndent())
            db.execSQL("""
                CREATE INDEX IF NOT EXISTS `index_artist_bulk_provider_lease_state_expiresAtEpochMs`
                ON `artist_bulk_provider_lease` (`state`, `expiresAtEpochMs`)
            """.trimIndent())
        }

        /** D25: append-only terminal outcomes. No artist profile, audio or artwork rows change. */
        val MIGRATION_14_15 = Migration(14, 15) { db ->
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS `artist_bulk_terminal_receipt` (
                    `sessionId` TEXT NOT NULL,
                    `fingerprint` TEXT NOT NULL,
                    `permitId` TEXT NOT NULL,
                    `outcome` TEXT NOT NULL,
                    `evidenceKind` TEXT NOT NULL,
                    `reservationRevision` INTEGER NOT NULL,
                    `libraryId` TEXT NOT NULL,
                    `sourceId` TEXT NOT NULL,
                    `rootEpoch` INTEGER NOT NULL,
                    `catalogRevision` INTEGER NOT NULL,
                    `libraryRevision` INTEGER NOT NULL,
                    `completedAtEpochMs` INTEGER NOT NULL,
                    `receiptDigest` TEXT NOT NULL,
                    PRIMARY KEY(`sessionId`, `fingerprint`),
                    FOREIGN KEY(`sessionId`, `fingerprint`) REFERENCES `artist_bulk_permission`(`sessionId`, `fingerprint`)
                        ON UPDATE NO ACTION ON DELETE CASCADE
                )
            """.trimIndent())
            db.execSQL("""
                CREATE INDEX IF NOT EXISTS `index_artist_bulk_terminal_receipt_sessionId`
                ON `artist_bulk_terminal_receipt` (`sessionId`)
            """.trimIndent())
            db.execSQL("""
                CREATE UNIQUE INDEX IF NOT EXISTS `index_artist_bulk_terminal_receipt_permitId`
                ON `artist_bulk_terminal_receipt` (`permitId`)
            """.trimIndent())
        }

        /** D23: consent receipts are distinct from prepared drafts and NOT executable. */
        val MIGRATION_13_14 = Migration(13, 14) { db ->
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS `artist_bulk_permission` (
                    `sessionId` TEXT NOT NULL,
                    `fingerprint` TEXT NOT NULL,
                    `permitId` TEXT NOT NULL,
                    `libraryId` TEXT NOT NULL,
                    `sourceId` TEXT NOT NULL,
                    `rootEpoch` INTEGER NOT NULL,
                    `catalogRevision` INTEGER NOT NULL,
                    `libraryRevision` INTEGER NOT NULL,
                    `artistCredit` TEXT NOT NULL,
                    `mbid` TEXT NOT NULL,
                    `associationId` TEXT NOT NULL,
                    `allowedOperation` TEXT NOT NULL,
                    `status` TEXT NOT NULL,
                    `issuedAtEpochMs` INTEGER NOT NULL,
                    `expiresAtEpochMs` INTEGER NOT NULL,
                    `updatedAtEpochMs` INTEGER NOT NULL,
                    `revision` INTEGER NOT NULL,
                    PRIMARY KEY(`sessionId`, `fingerprint`),
                    FOREIGN KEY(`sessionId`, `fingerprint`) REFERENCES `artist_bulk_receipt`(`sessionId`, `fingerprint`)
                        ON UPDATE NO ACTION ON DELETE CASCADE
                )
            """.trimIndent())
            db.execSQL("""
                CREATE INDEX IF NOT EXISTS `index_artist_bulk_permission_sessionId`
                ON `artist_bulk_permission` (`sessionId`)
            """.trimIndent())
            db.execSQL("""
                CREATE UNIQUE INDEX IF NOT EXISTS `index_artist_bulk_permission_permitId`
                ON `artist_bulk_permission` (`permitId`)
            """.trimIndent())
        }

        /** D22 S4-D: additive, independent enrichment session journal. No audio-table migration. */
        val MIGRATION_12_13 = Migration(12, 13) { db ->
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS `artist_bulk_session` (
                    `sessionId` TEXT NOT NULL,
                    `libraryId` TEXT NOT NULL,
                    `sourceId` TEXT NOT NULL,
                    `rootEpoch` INTEGER NOT NULL,
                    `catalogRevision` INTEGER NOT NULL,
                    `libraryRevision` INTEGER NOT NULL,
                    `createdAtEpochMs` INTEGER NOT NULL,
                    `updatedAtEpochMs` INTEGER NOT NULL,
                    `status` TEXT NOT NULL,
                    `revision` INTEGER NOT NULL,
                    `receiptCount` INTEGER NOT NULL,
                    PRIMARY KEY(`sessionId`)
                )
            """.trimIndent())
            db.execSQL("""
                CREATE INDEX IF NOT EXISTS `index_artist_bulk_session_libraryId_createdAtEpochMs`
                ON `artist_bulk_session` (`libraryId`, `createdAtEpochMs`)
            """.trimIndent())
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS `artist_bulk_receipt` (
                    `sessionId` TEXT NOT NULL,
                    `fingerprint` TEXT NOT NULL,
                    `libraryId` TEXT NOT NULL,
                    `artistCredit` TEXT NOT NULL,
                    `albumIdsJson` TEXT NOT NULL,
                    `mbid` TEXT NOT NULL,
                    `associationId` TEXT NOT NULL,
                    `status` TEXT NOT NULL,
                    `revision` INTEGER NOT NULL,
                    `updatedAtEpochMs` INTEGER NOT NULL,
                    PRIMARY KEY(`sessionId`, `fingerprint`),
                    FOREIGN KEY(`sessionId`) REFERENCES `artist_bulk_session`(`sessionId`)
                        ON UPDATE NO ACTION ON DELETE CASCADE
                )
            """.trimIndent())
            db.execSQL("""
                CREATE INDEX IF NOT EXISTS `index_artist_bulk_receipt_sessionId`
                ON `artist_bulk_receipt` (`sessionId`)
            """.trimIndent())
        }

        /** S1-B2: additive journal only; no audio, selection, profile or outbox rows are rewritten. */
        val MIGRATION_11_12 = Migration(11, 12) { db ->
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS `artist_artwork_cleanup` (
                    `cleanupId` TEXT NOT NULL,
                    `libraryId` TEXT NOT NULL,
                    `creditKey` TEXT NOT NULL,
                    `associationId` TEXT NOT NULL,
                    `assetId` TEXT NOT NULL,
                    `localRelativePath` TEXT NOT NULL,
                    `sha256` TEXT NOT NULL,
                    `createdAtMs` INTEGER NOT NULL,
                    `state` TEXT NOT NULL,
                    PRIMARY KEY(`cleanupId`, `assetId`)
                )
            """.trimIndent())
            db.execSQL("""
                CREATE INDEX IF NOT EXISTS `index_artist_artwork_cleanup_state_createdAtMs`
                ON `artist_artwork_cleanup` (`state`, `createdAtMs`)
            """.trimIndent())
        }

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
                    }
                }
            }

            // Group every historical claim before assigning ownership. A confirmed claim
            // cannot win merely because a competing association was classified as
            // INSUFFICIENT, and two local associations remain distinct even with one MBID.
            db.execSQL(
                "INSERT INTO artist_album_credit_binding " +
                    "(libraryId,albumKey,creditKey,associationId,evidenceType,confirmedAtMs) " +
                    "SELECT libraryId,albumKey,creditKey,MIN(associationId)," +
                    "'LEGACY_EXPLICIT_CONTEXT',MAX(confirmedAtMs) " +
                    "FROM artist_membership_evidence " +
                    "WHERE evidenceType='LEGACY_EXPLICIT_CONTEXT' AND source='MIGRATION_V8' " +
                    "GROUP BY libraryId,albumKey,creditKey " +
                    "HAVING COUNT(DISTINCT associationId)=1 " +
                    "AND SUM(CASE WHEN status='CONFIRMED' THEN 1 ELSE 0 END)>0",
            )
            db.execSQL(
                "UPDATE artist_membership_evidence SET status='INSUFFICIENT',confirmedAtMs=NULL " +
                    "WHERE evidenceType='LEGACY_EXPLICIT_CONTEXT' AND source='MIGRATION_V8' " +
                    "AND EXISTS (SELECT 1 FROM artist_membership_evidence other " +
                    "WHERE other.libraryId=artist_membership_evidence.libraryId " +
                    "AND other.albumKey=artist_membership_evidence.albumKey " +
                    "AND other.creditKey=artist_membership_evidence.creditKey " +
                    "AND other.evidenceType='LEGACY_EXPLICIT_CONTEXT' " +
                    "AND other.source='MIGRATION_V8' " +
                    "AND other.associationId!=artist_membership_evidence.associationId)",
            )
        }

        /**
         * Repairs installations already migrated by v9. Only cursor-order legacy
         * bindings are removed; later explicit or structured-evidence bindings remain
         * authoritative. Historical competing claims are retained as insufficient
         * evidence rather than deleted or converted into ownership.
         */
        val MIGRATION_9_10 = Migration(9, 10) { db ->
            db.execSQL(
                "DELETE FROM artist_album_credit_binding " +
                    "WHERE evidenceType='LEGACY_EXPLICIT_CONTEXT' " +
                    "AND EXISTS (SELECT 1 FROM artist_membership_evidence first " +
                    "JOIN artist_membership_evidence other " +
                    "ON other.libraryId=first.libraryId AND other.albumKey=first.albumKey " +
                    "AND other.creditKey=first.creditKey " +
                    "AND other.associationId!=first.associationId " +
                    "WHERE first.libraryId=artist_album_credit_binding.libraryId " +
                    "AND first.albumKey=artist_album_credit_binding.albumKey " +
                    "AND first.creditKey=artist_album_credit_binding.creditKey " +
                    "AND first.evidenceType='LEGACY_EXPLICIT_CONTEXT' " +
                    "AND first.source='MIGRATION_V8' " +
                    "AND other.evidenceType='LEGACY_EXPLICIT_CONTEXT' " +
                    "AND other.source='MIGRATION_V8')",
            )
            db.execSQL(
                "UPDATE artist_membership_evidence SET status='INSUFFICIENT',confirmedAtMs=NULL " +
                    "WHERE evidenceType='LEGACY_EXPLICIT_CONTEXT' AND source='MIGRATION_V8' " +
                    "AND EXISTS (SELECT 1 FROM artist_membership_evidence other " +
                    "WHERE other.libraryId=artist_membership_evidence.libraryId " +
                    "AND other.albumKey=artist_membership_evidence.albumKey " +
                    "AND other.creditKey=artist_membership_evidence.creditKey " +
                    "AND other.evidenceType='LEGACY_EXPLICIT_CONTEXT' " +
                    "AND other.source='MIGRATION_V8' " +
                    "AND other.associationId!=artist_membership_evidence.associationId)",
            )
        }

        /** Isolated catalog and outbox; no changes to artist identities or playback. */
        val MIGRATION_10_11 = Migration(10, 11) { db ->
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS `artist_artwork_catalog_baseline` (
                    `libraryId` TEXT NOT NULL, `sourceId` TEXT NOT NULL,
                    `snapshotDigest` TEXT NOT NULL, `updatedAtMs` INTEGER NOT NULL,
                    PRIMARY KEY(`libraryId`)
                )
            """.trimIndent())
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS `artist_artwork_catalog_track` (
                    `libraryId` TEXT NOT NULL, `trackHash` TEXT NOT NULL,
                    PRIMARY KEY(`libraryId`, `trackHash`)
                )
            """.trimIndent())
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS `artist_artwork_auto_outbox` (
                    `libraryId` TEXT NOT NULL, `creditKey` TEXT NOT NULL,
                    `associationId` TEXT NOT NULL, `albumKey` TEXT NOT NULL,
                    `sourceId` TEXT NOT NULL, `state` TEXT NOT NULL,
                    `updatedAtMs` INTEGER NOT NULL,
                    PRIMARY KEY(`libraryId`, `creditKey`, `associationId`, `albumKey`, `sourceId`)
                )
            """.trimIndent())
            db.execSQL("""
                CREATE INDEX IF NOT EXISTS `index_artist_artwork_auto_outbox_libraryId_creditKey_associationId_state`
                ON `artist_artwork_auto_outbox` (`libraryId`, `creditKey`, `associationId`, `state`)
            """.trimIndent())
            db.execSQL("""
                CREATE INDEX IF NOT EXISTS `index_artist_artwork_auto_outbox_state_updatedAtMs`
                ON `artist_artwork_auto_outbox` (`state`, `updatedAtMs`)
            """.trimIndent())
            db.execSQL("""
                CREATE INDEX IF NOT EXISTS `index_artist_artwork_auto_outbox_libraryId_sourceId_state`
                ON `artist_artwork_auto_outbox` (`libraryId`, `sourceId`, `state`)
            """.trimIndent())
            // Exact root observations and explicit binding provenance are distinct.
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS `artist_artwork_source_catalog_credit` (
                    `libraryId` TEXT NOT NULL, `sourceId` TEXT NOT NULL,
                    `albumKey` TEXT NOT NULL, `creditKey` TEXT NOT NULL,
                    `observedAtMs` INTEGER NOT NULL,
                    PRIMARY KEY(`libraryId`, `sourceId`, `albumKey`, `creditKey`)
                )
            """.trimIndent())
            db.execSQL("""
                CREATE INDEX IF NOT EXISTS `index_artist_artwork_source_catalog_credit_libraryId_albumKey_creditKey`
                ON `artist_artwork_source_catalog_credit` (`libraryId`, `albumKey`, `creditKey`)
            """.trimIndent())
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS `artist_artwork_source_binding_proof` (
                    `libraryId` TEXT NOT NULL, `sourceId` TEXT NOT NULL,
                    `albumKey` TEXT NOT NULL, `creditKey` TEXT NOT NULL,
                    `associationId` TEXT NOT NULL, `confirmedAtMs` INTEGER NOT NULL,
                    PRIMARY KEY(`libraryId`, `sourceId`, `albumKey`, `creditKey`, `associationId`)
                )
            """.trimIndent())
            db.execSQL("""
                CREATE INDEX IF NOT EXISTS `index_artist_artwork_source_binding_proof_libraryId_creditKey_associationId_sourceId`
                ON `artist_artwork_source_binding_proof` (`libraryId`, `creditKey`, `associationId`, `sourceId`)
            """.trimIndent())
            // New in K26 before initial v11 deployment; no destructive migration.
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS `artist_credit_review_receipt` (
                    `libraryId` TEXT NOT NULL, `sourceId` TEXT NOT NULL,
                    `creditKey` TEXT NOT NULL, `albumKey` TEXT NOT NULL,
                    `trackId` TEXT NOT NULL, `artistMbid` TEXT NOT NULL,
                    `recordingMbid` TEXT NOT NULL, `releaseMbid` TEXT NOT NULL,
                    `role` TEXT NOT NULL, `verifiedAtMs` INTEGER NOT NULL,
                    PRIMARY KEY(`libraryId`, `sourceId`, `creditKey`, `albumKey`, `trackId`)
                )
            """.trimIndent())
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
