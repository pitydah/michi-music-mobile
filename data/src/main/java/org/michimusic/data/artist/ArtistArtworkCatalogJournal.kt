package org.michimusic.data.artist

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert

/** Baseline for one concrete library. Source switch resets it, not artist identity. */
@Entity(tableName = "artist_artwork_catalog_baseline", primaryKeys = ["libraryId"])
data class ArtistArtworkCatalogBaselineRecord(
    val libraryId: String,
    val sourceId: String,
    val snapshotDigest: String,
    val updatedAtMs: Long,
)

/** Hashed track identifiers only. No titles, file paths, bytes or credits. */
@Entity(tableName = "artist_artwork_catalog_track", primaryKeys = ["libraryId", "trackHash"])
data class ArtistArtworkCatalogTrackRecord(
    val libraryId: String,
    val trackHash: String,
)

/** Pair observed during an already-authorized FULL catalog read, not proof of identity.
 * Persist only changed-root/digest observations; never examine audio for this table.
 */
@Entity(
    tableName = "artist_artwork_source_catalog_credit",
    primaryKeys = ["libraryId", "sourceId", "albumKey", "creditKey"],
    indices = [Index(value = ["libraryId", "albumKey", "creditKey"])],
)
data class ArtistArtworkSourceCatalogCreditRecord(
    val libraryId: String,
    val sourceId: String,
    val albumKey: String,
    val creditKey: String,
    val observedAtMs: Long,
)

/** This is a *receipt* of an explicit semantic binding within a known active source.
 * It never writes or substitutes the binding itself. Legacy bindings lack this proof.
 */
@Entity(
    tableName = "artist_artwork_source_binding_proof",
    primaryKeys = ["libraryId", "sourceId", "albumKey", "creditKey", "associationId"],
    indices = [Index(value = ["libraryId", "creditKey", "associationId", "sourceId"])],
)
data class ArtistArtworkSourceBindingProofRecord(
    val libraryId: String,
    val sourceId: String,
    val albumKey: String,
    val creditKey: String,
    val associationId: String,
    val confirmedAtMs: Long,
)

/** Durable pending work; every album-credit binding must be revalidated before HTTP. */
@Entity(
    tableName = "artist_artwork_auto_outbox",
    primaryKeys = ["libraryId", "creditKey", "associationId", "albumKey", "sourceId"],
    indices = [
        Index(value = ["libraryId", "creditKey", "associationId", "state"]),
        Index(value = ["state", "updatedAtMs"]),
        Index(value = ["libraryId", "sourceId", "state"]),
    ],
)
data class ArtistArtworkAutoOutboxRecord(
    val libraryId: String,
    val creditKey: String,
    val associationId: String,
    val albumKey: String,
    val sourceId: String,
    val state: String = "PENDING",
    val updatedAtMs: Long,
)

/** One queued job per identity-scoped association, regardless of album fan-out. */
data class ArtistArtworkDispatchRow(
    val libraryId: String,
    val creditKey: String,
    val associationId: String,
    val readyAtMs: Long,
)

@Dao
interface ArtistArtworkCatalogJournalDao {
    @Query("SELECT * FROM artist_artwork_catalog_baseline WHERE libraryId = :libraryId LIMIT 1")
    suspend fun baseline(libraryId: String): ArtistArtworkCatalogBaselineRecord?

    @Query("SELECT trackHash FROM artist_artwork_catalog_track WHERE libraryId = :libraryId")
    suspend fun hashes(libraryId: String): List<String>

    @Upsert
    suspend fun putBaseline(row: ArtistArtworkCatalogBaselineRecord)

    @Query("DELETE FROM artist_artwork_catalog_track WHERE libraryId = :libraryId")
    suspend fun clearHashes(libraryId: String)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertHashes(rows: List<ArtistArtworkCatalogTrackRecord>)

    /** Removes only stale row fingerprints. IN batches stay under SQLite host-variable limits. */
    @Query("DELETE FROM artist_artwork_catalog_track WHERE libraryId = :libraryId AND trackHash IN (:hashes)")
    suspend fun deleteHashes(libraryId: String, hashes: List<String>): Int

    /** A full, source-consistent catalog observation may be replaced atomically. */
    @Query("DELETE FROM artist_artwork_source_catalog_credit WHERE libraryId = :libraryId AND sourceId = :sourceId")
    suspend fun clearSourceCatalogCredits(libraryId: String, sourceId: String): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertSourceCatalogCredits(rows: List<ArtistArtworkSourceCatalogCreditRecord>)

    /** Capture provenance ONLY after a newly committed, explicit album-credit binding.
     * The observed pair must predate the binding and be uniquely observed in this source.
     * Merely opening a library, seeing a name or refreshing a provider cannot create proof.
     * Historic bindings before the first observation remain REVIEW_ONLY for auto artwork.
     */
    // A subsequent explicit re-confirmation supersedes an older receipt for the
    // SAME exact source+album+credit+association. Without REPLACE the old receipt's
    // confirmedAtMs survives, so the binding version check rejects it forever.
    @Query("""INSERT OR REPLACE INTO artist_artwork_source_binding_proof
        (libraryId, sourceId, albumKey, creditKey, associationId, confirmedAtMs)
        SELECT b.libraryId, :sourceId, b.albumKey, b.creditKey, b.associationId, b.confirmedAtMs
        FROM artist_album_credit_binding b
        INNER JOIN artist_artwork_source_catalog_credit c
            ON c.libraryId = b.libraryId AND c.sourceId = :sourceId
            AND c.albumKey = b.albumKey AND c.creditKey = b.creditKey
        WHERE b.libraryId = :libraryId AND b.creditKey = :creditKey
          AND b.associationId = :associationId
          AND b.confirmedAtMs > :baselineAtMs AND b.confirmedAtMs <= :nowMs
          AND c.observedAtMs <= b.confirmedAtMs
          AND NOT EXISTS (SELECT 1 FROM artist_artwork_source_catalog_credit other
              WHERE other.libraryId = b.libraryId AND other.albumKey = b.albumKey
                AND other.creditKey = b.creditKey AND other.sourceId != :sourceId
                AND other.observedAtMs <= b.confirmedAtMs)""")
    suspend fun captureBindingSourceProofs(
        libraryId: String, creditKey: String, associationId: String,
        sourceId: String, baselineAtMs: Long, nowMs: Long,
    ): Unit

    /** Explicit binding provenance survives A -> B -> A; an outdated binding does not.
     * Source existence in a catalog alone does not satisfy this authority fence.
     */
    @Query("""SELECT COUNT(*) FROM artist_artwork_source_binding_proof p
        INNER JOIN artist_album_credit_binding b ON b.libraryId = p.libraryId
          AND b.albumKey = p.albumKey AND b.creditKey = p.creditKey
          AND b.associationId = p.associationId AND b.confirmedAtMs = p.confirmedAtMs
        WHERE p.libraryId = :libraryId AND p.sourceId = :sourceId
          AND p.albumKey = :albumKey AND p.creditKey = :creditKey
          AND p.associationId = :associationId""")
    suspend fun countVerifiedSourceBinding(
        libraryId: String, sourceId: String, albumKey: String,
        creditKey: String, associationId: String,
    ): Int

    /** A missing proof is a review-required state, NOT a license to use an old binding. */
    @Query("""UPDATE artist_artwork_auto_outbox SET state = 'NEEDS_SOURCE_PROOF',
        updatedAtMs = :nowMs
        WHERE libraryId = :libraryId AND sourceId = :sourceId
          AND albumKey = :albumKey AND creditKey = :creditKey
          AND associationId = :associationId AND state = 'PENDING'""")
    suspend fun blockUnprovenSourceEvent(
        libraryId: String, sourceId: String, albumKey: String,
        creditKey: String, associationId: String, nowMs: Long,
    ): Int

    /** After explicit binding proof, recover only matching review-held rows. */
    @Query("""UPDATE artist_artwork_auto_outbox SET state = 'PENDING', updatedAtMs = :nowMs
        WHERE libraryId = :libraryId AND sourceId = :sourceId
          AND creditKey = :creditKey AND associationId = :associationId
          AND state = 'NEEDS_SOURCE_PROOF' AND EXISTS (
            SELECT 1 FROM artist_artwork_source_binding_proof p
            INNER JOIN artist_album_credit_binding b ON b.libraryId = p.libraryId
              AND b.albumKey = p.albumKey AND b.creditKey = p.creditKey
              AND b.associationId = p.associationId AND b.confirmedAtMs = p.confirmedAtMs
            WHERE p.libraryId = artist_artwork_auto_outbox.libraryId
              AND p.sourceId = artist_artwork_auto_outbox.sourceId
              AND p.albumKey = artist_artwork_auto_outbox.albumKey
              AND p.creditKey = artist_artwork_auto_outbox.creditKey
              AND p.associationId = artist_artwork_auto_outbox.associationId)""")
    suspend fun reviveProvenSourceEvents(
        libraryId: String, sourceId: String, creditKey: String,
        associationId: String, nowMs: Long,
    ): Int

    @Upsert
    suspend fun putOutbox(row: ArtistArtworkAutoOutboxRecord)

    /** Post-confirmation event cannot revive completed or cooling-down associations. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertOutboxIfAbsent(row: ArtistArtworkAutoOutboxRecord): Long

    /** Newly bound or previously identity-blocked exact albums are eligible.
     * READY and cooled-down provider failures must NOT be revived by refresh.
     */
    @Query("""SELECT b.albumKey FROM artist_album_credit_binding b
        WHERE b.libraryId = :libraryId AND b.creditKey = :creditKey
          AND b.associationId = :associationId
          AND NOT EXISTS (
              SELECT 1 FROM artist_artwork_auto_outbox o
              WHERE o.libraryId = b.libraryId AND o.creditKey = b.creditKey
                AND o.associationId = b.associationId AND o.albumKey = b.albumKey
                AND o.sourceId = :sourceId
                AND o.state NOT IN ('NEEDS_IDENTITY', 'STALE_SOURCE', 'STALE_CONTEXT')
          )
        ORDER BY b.confirmedAtMs DESC, b.albumKey DESC LIMIT 1""")
    suspend fun firstBoundAlbumKey(libraryId: String, creditKey: String, associationId: String, sourceId: String): String?

    /** Verifiable source proof, current binding, and pending outbox in one SQL read. */
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
    suspend fun countProvenPendingForAssociation(
        libraryId: String, sourceId: String, creditKey: String,
        associationId: String, nowMs: Long,
    ): Int

    /** Only currently source-proven album bindings may seed an automatic event.
     * A source with the same albumKey/credit text cannot borrow another source's proof.
     */
    @Query("""SELECT b.albumKey FROM artist_album_credit_binding b
        INNER JOIN artist_artwork_source_binding_proof p
          ON p.libraryId = b.libraryId AND p.albumKey = b.albumKey
          AND p.creditKey = b.creditKey AND p.associationId = b.associationId
          AND p.confirmedAtMs = b.confirmedAtMs AND p.sourceId = :sourceId
        WHERE b.libraryId = :libraryId AND b.creditKey = :creditKey
          AND b.associationId = :associationId
          AND NOT EXISTS (
              SELECT 1 FROM artist_artwork_auto_outbox o
              WHERE o.libraryId = b.libraryId AND o.creditKey = b.creditKey
                AND o.associationId = b.associationId AND o.albumKey = b.albumKey
                AND o.sourceId = :sourceId
                AND o.state NOT IN ('NEEDS_IDENTITY', 'NEEDS_SOURCE_PROOF',
                    'STALE_SOURCE', 'STALE_CONTEXT')
          )
        ORDER BY b.confirmedAtMs DESC, b.albumKey DESC LIMIT 1""")
    suspend fun firstProvenBoundAlbumKey(
        libraryId: String, creditKey: String, associationId: String, sourceId: String,
    ): String?

    /** Exact Room bindings for a small set of album keys; no name-to-identity guess. */
    @Query("""SELECT * FROM artist_album_credit_binding
        WHERE libraryId = :libraryId AND albumKey IN (:albumKeys)""")
    suspend fun confirmedCreditsForAlbums(
        libraryId: String, albumKeys: List<String>,
    ): List<ArtistAlbumCreditBindingRecord>

    /** Only a fresh SAME-root exact album-credit delta can restore STALE_CONTEXT.
     * Never move a row to another source. A new source receives its OWN outbox row.
     */
    @Query("""UPDATE artist_artwork_auto_outbox SET state = 'PENDING', updatedAtMs = :nowMs
        WHERE libraryId = :libraryId AND creditKey = :creditKey
          AND associationId = :associationId AND albumKey = :albumKey
          AND sourceId = :sourceId AND state = 'STALE_CONTEXT'
          AND EXISTS (SELECT 1 FROM artist_album_credit_binding b
              INNER JOIN artist_artwork_source_binding_proof p
                ON p.libraryId = b.libraryId AND p.albumKey = b.albumKey
                AND p.creditKey = b.creditKey AND p.associationId = b.associationId
                AND p.confirmedAtMs = b.confirmedAtMs AND p.sourceId = :sourceId
              WHERE b.libraryId = :libraryId AND b.albumKey = :albumKey
                AND b.creditKey = :creditKey AND b.associationId = :associationId)""")
    suspend fun reviveExactContextOnCatalogDelta(
        libraryId: String, creditKey: String, associationId: String,
        albumKey: String, sourceId: String, nowMs: Long,
    ): Int

    /** Only previously authorized rows for a RETURNED source can be recovered.
     * This query is invoked on a fresh, complete root observation and returns
     * only rows still backed by the exact confirmed album-credit relationship.
     * The observer also validates the album AND credit in its current snapshot.
     */
    @Query("""SELECT o.* FROM artist_artwork_auto_outbox o
        INNER JOIN artist_album_credit_binding b ON b.libraryId = o.libraryId
            AND b.albumKey = o.albumKey AND b.creditKey = o.creditKey
            AND b.associationId = o.associationId
        INNER JOIN artist_artwork_source_binding_proof p ON p.libraryId = b.libraryId
            AND p.sourceId = o.sourceId AND p.albumKey = b.albumKey
            AND p.creditKey = b.creditKey AND p.associationId = b.associationId
            AND p.confirmedAtMs = b.confirmedAtMs
        WHERE o.libraryId = :libraryId AND o.sourceId = :sourceId
          AND o.state = 'STALE_SOURCE' AND o.albumKey IN (:albumKeys)""")
    suspend fun returnedSourceBoundRowsForAlbums(
        libraryId: String, sourceId: String, albumKeys: List<String>,
    ): List<ArtistArtworkAutoOutboxRecord>

    /** Re-arm this source's old pending evidence only after the exact current
     * observed album-credit has been reconciled; do not reopen READY/cooldown.
     */
    @Query("""UPDATE artist_artwork_auto_outbox SET state = 'PENDING', updatedAtMs = :nowMs
        WHERE libraryId = :libraryId AND creditKey = :creditKey
          AND associationId = :associationId AND albumKey = :albumKey
          AND sourceId = :sourceId AND state = 'STALE_SOURCE'
          AND EXISTS (SELECT 1 FROM artist_album_credit_binding b
              INNER JOIN artist_artwork_source_binding_proof p
                ON p.libraryId = b.libraryId AND p.albumKey = b.albumKey
                AND p.creditKey = b.creditKey AND p.associationId = b.associationId
                AND p.confirmedAtMs = b.confirmedAtMs AND p.sourceId = :sourceId
              WHERE b.libraryId = :libraryId AND b.albumKey = :albumKey
                AND b.creditKey = :creditKey AND b.associationId = :associationId)""")
    suspend fun resumeReturnedSourceBoundRow(
        libraryId: String, creditKey: String, associationId: String,
        albumKey: String, sourceId: String, nowMs: Long,
    ): Int

    /** User-triggered missing-art repair AFTER local slot/byte validation.
     * Only completed READY/CACHED rows can be rearmed. Never bypass PENDING cooldown,
     * NEEDS_IDENTITY, manually protected slots, or unconfirmed album bindings.
     * The calling backfill checks exact binding + consent in the same transaction.
     */
    @Query("""UPDATE artist_artwork_auto_outbox SET state = 'PENDING', updatedAtMs = :nowMs
        WHERE libraryId = :libraryId AND creditKey = :creditKey
          AND associationId = :associationId AND albumKey = :albumKey
          AND sourceId = :sourceId AND state IN ('READY', 'CACHED')
          AND EXISTS (SELECT 1 FROM artist_album_credit_binding b
              INNER JOIN artist_artwork_source_binding_proof p
                ON p.libraryId = b.libraryId AND p.albumKey = b.albumKey
                AND p.creditKey = b.creditKey AND p.associationId = b.associationId
                AND p.confirmedAtMs = b.confirmedAtMs AND p.sourceId = :sourceId
              WHERE b.libraryId = :libraryId AND b.albumKey = :albumKey
                AND b.creditKey = :creditKey AND b.associationId = :associationId)""")
    suspend fun rearmCompletedForExplicitMissingArtwork(
        libraryId: String, creditKey: String, associationId: String,
        albumKey: String, sourceId: String, nowMs: Long,
    ): Int

    /** A newly confirmed identity may revive ONLY work blocked for missing identity/context.
     * READY and deferred provider errors must never be reopened by a normal event.
     */
    @Query("""UPDATE artist_artwork_auto_outbox SET state = 'PENDING', updatedAtMs = :nowMs
        WHERE libraryId = :libraryId AND creditKey = :creditKey
          AND associationId = :associationId AND albumKey = :albumKey
          AND sourceId = :sourceId AND state IN ('NEEDS_IDENTITY', 'STALE_CONTEXT')
          AND EXISTS (SELECT 1 FROM artist_album_credit_binding b
              INNER JOIN artist_artwork_source_binding_proof p
                ON p.libraryId = b.libraryId AND p.albumKey = b.albumKey
                AND p.creditKey = b.creditKey AND p.associationId = b.associationId
                AND p.confirmedAtMs = b.confirmedAtMs AND p.sourceId = :sourceId
              WHERE b.libraryId = :libraryId AND b.albumKey = :albumKey
                AND b.creditKey = :creditKey AND b.associationId = :associationId)""")
    suspend fun reviveVerifiedEvent(
        libraryId: String, creditKey: String, associationId: String,
        albumKey: String, sourceId: String, nowMs: Long,
    ): Int

    /** Only due work may be consumed; deferred rows stay pending until their deadline. */
    @Query("""SELECT * FROM artist_artwork_auto_outbox WHERE libraryId = :libraryId
        AND creditKey = :creditKey AND associationId = :associationId AND state = 'PENDING'
        AND updatedAtMs <= :nowMs ORDER BY updatedAtMs ASC LIMIT 1024""")
    suspend fun pending(
        libraryId: String, creditKey: String, associationId: String, nowMs: Long,
    ): List<ArtistArtworkAutoOutboxRecord>

    /** Read only the due evidence owned by the worker's original root.
     * Old-root fan-out (even >1024 albums) cannot starve the active root.
     */
    @Query("""SELECT o.* FROM artist_artwork_auto_outbox o
        INNER JOIN artist_album_credit_binding b
          ON b.libraryId = o.libraryId AND b.creditKey = o.creditKey
         AND b.albumKey = o.albumKey AND b.associationId = o.associationId
        WHERE o.libraryId = :libraryId AND o.creditKey = :creditKey
          AND o.associationId = :associationId AND o.sourceId = :sourceId
          AND o.state = 'PENDING' AND o.updatedAtMs <= :nowMs
        ORDER BY o.updatedAtMs ASC LIMIT 1024""")
    suspend fun pendingForRoot(
        libraryId: String, creditKey: String, associationId: String,
        sourceId: String, nowMs: Long,
    ): List<ArtistArtworkAutoOutboxRecord>

    /** Reject unproven events BEFORE the 1,024-row worker limit. */
    @Query("""SELECT o.* FROM artist_artwork_auto_outbox o
        INNER JOIN artist_album_credit_binding b ON b.libraryId = o.libraryId
          AND b.albumKey = o.albumKey AND b.creditKey = o.creditKey
          AND b.associationId = o.associationId
        INNER JOIN artist_artwork_source_binding_proof p
          ON p.libraryId = o.libraryId AND p.sourceId = o.sourceId
          AND p.albumKey = o.albumKey AND p.creditKey = o.creditKey
          AND p.associationId = o.associationId AND p.confirmedAtMs = b.confirmedAtMs
        WHERE o.libraryId = :libraryId AND o.creditKey = :creditKey
          AND o.associationId = :associationId AND o.sourceId = :sourceId
          AND o.state = 'PENDING' AND o.updatedAtMs <= :nowMs
        ORDER BY o.updatedAtMs ASC LIMIT 1024""")
    suspend fun provenPendingForRoot(
        libraryId: String, creditKey: String, associationId: String,
        sourceId: String, nowMs: Long,
    ): List<ArtistArtworkAutoOutboxRecord>

    /** Source proof missing or superseded: hold for explicit re-confirmation.
     * This never overwrites a READY or a provider retry cooldown.
     */
    @Query("""UPDATE artist_artwork_auto_outbox SET state = 'NEEDS_SOURCE_PROOF',
        updatedAtMs = :nowMs
        WHERE libraryId = :libraryId AND sourceId = :sourceId AND state = 'PENDING'
          AND NOT EXISTS (SELECT 1 FROM artist_artwork_source_binding_proof p
            INNER JOIN artist_album_credit_binding b ON b.libraryId = p.libraryId
              AND b.albumKey = p.albumKey AND b.creditKey = p.creditKey
              AND b.associationId = p.associationId AND b.confirmedAtMs = p.confirmedAtMs
            WHERE p.libraryId = artist_artwork_auto_outbox.libraryId
              AND p.sourceId = artist_artwork_auto_outbox.sourceId
              AND p.albumKey = artist_artwork_auto_outbox.albumKey
              AND p.creditKey = artist_artwork_auto_outbox.creditKey
              AND p.associationId = artist_artwork_auto_outbox.associationId)""")
    suspend fun holdUnprovenEventsForRoot(libraryId: String, sourceId: String, nowMs: Long): Int

    /** Cleanup is source-scoped and ignores deferred deadlines: unbound albums have
     * no authority to download, even if they are the oldest 100k queue entries.
     * The correlated NOT EXISTS is deliberately exact; never match by artist name.
     */
    @Query("""UPDATE artist_artwork_auto_outbox SET state = 'STALE_CONTEXT', updatedAtMs = :nowMs
        WHERE libraryId = :libraryId AND creditKey = :creditKey
          AND associationId = :associationId AND sourceId = :sourceId
          AND state = 'PENDING' AND NOT EXISTS (
            SELECT 1 FROM artist_album_credit_binding b
            WHERE b.libraryId = artist_artwork_auto_outbox.libraryId
              AND b.albumKey = artist_artwork_auto_outbox.albumKey
              AND b.creditKey = artist_artwork_auto_outbox.creditKey
              AND b.associationId = artist_artwork_auto_outbox.associationId
          )""")
    suspend fun finishOrphanedPendingForRoot(
        libraryId: String, creditKey: String, associationId: String,
        sourceId: String, nowMs: Long,
    ): Int

    /** A removed association must not be redispatched on every unrelated library event. */
    @Query("""UPDATE artist_artwork_auto_outbox SET state = 'STALE_CONTEXT', updatedAtMs = :nowMs
        WHERE libraryId = :libraryId AND creditKey = :creditKey
          AND associationId = :associationId AND sourceId = :sourceId AND state = 'PENDING'""")
    suspend fun finishMissingAssociation(
        libraryId: String, creditKey: String, associationId: String, sourceId: String, nowMs: Long,
    ): Int

    /** Stable keyset browse over CONFIRMED bindings; no media/track scan or OFFSET walk. */
    @Query("""SELECT * FROM artist_album_credit_binding WHERE libraryId = :libraryId
        AND (albumKey > :afterAlbum OR (albumKey = :afterAlbum AND creditKey > :afterCredit))
        ORDER BY albumKey, creditKey LIMIT :limit""")
    suspend fun bindingsAfter(libraryId: String, afterAlbum: String, afterCredit: String, limit: Int):
        List<ArtistAlbumCreditBindingRecord>

    /** Bounded keyset for SOURCE-PROVEN rows: untrusted legacy bindings are not
     * counted as inspected candidates in a user-triggered recovery campaign.
     */
    @Query("""SELECT b.* FROM artist_album_credit_binding b
        INNER JOIN artist_artwork_source_binding_proof p
          ON p.libraryId = b.libraryId AND p.sourceId = :sourceId
          AND p.albumKey = b.albumKey AND p.creditKey = b.creditKey
          AND p.associationId = b.associationId AND p.confirmedAtMs = b.confirmedAtMs
        WHERE b.libraryId = :libraryId
          AND (b.albumKey > :afterAlbum OR (b.albumKey = :afterAlbum
            AND b.creditKey > :afterCredit))
        ORDER BY b.albumKey, b.creditKey LIMIT :limit""")
    suspend fun provenBindingsAfter(
        libraryId: String, sourceId: String, afterAlbum: String,
        afterCredit: String, limit: Int,
    ): List<ArtistAlbumCreditBindingRecord>

    /** One row per exact artist association, never 128 albums of the same artist. */
    @Query("""SELECT libraryId, creditKey, associationId, MIN(updatedAtMs) AS readyAtMs
        FROM artist_artwork_auto_outbox WHERE state = 'PENDING'
        GROUP BY libraryId, creditKey, associationId
        HAVING MIN(updatedAtMs) <= :nowMs ORDER BY readyAtMs ASC LIMIT :limit""")
    suspend fun pendingDispatch(limit: Int, nowMs: Long): List<ArtistArtworkDispatchRow>

    /** Dispatch only evidence belonging to the *currently active* root and library.
     * A source switch must never cause spurious WorkManager jobs for old roots.
     */
    @Query("""SELECT libraryId, creditKey, associationId, MIN(updatedAtMs) AS readyAtMs
        FROM artist_artwork_auto_outbox WHERE state = 'PENDING'
          AND libraryId = :libraryId AND sourceId = :sourceId
          AND updatedAtMs <= :nowMs
        GROUP BY libraryId, creditKey, associationId
        ORDER BY readyAtMs ASC LIMIT :limit""")
    suspend fun pendingDispatchForRoot(
        libraryId: String, sourceId: String, limit: Int, nowMs: Long,
    ): List<ArtistArtworkDispatchRow>

    /** Only exact currently bound album-credit events can enter WorkManager. This
     * closes head-of-line starvation when 128+ orphan associations precede a valid
     * artist in the durable outbox. Names are never used as identity authority.
     */
    @Query("""SELECT o.libraryId AS libraryId, o.creditKey AS creditKey,
        o.associationId AS associationId, MIN(o.updatedAtMs) AS readyAtMs
        FROM artist_artwork_auto_outbox o
        INNER JOIN artist_album_credit_binding b ON b.libraryId = o.libraryId
            AND b.albumKey = o.albumKey AND b.creditKey = o.creditKey
            AND b.associationId = o.associationId
        WHERE o.state = 'PENDING' AND o.libraryId = :libraryId
          AND o.sourceId = :sourceId AND o.updatedAtMs <= :nowMs
        GROUP BY o.libraryId, o.creditKey, o.associationId
        ORDER BY readyAtMs ASC LIMIT :limit""")
    suspend fun boundPendingDispatchForRoot(
        libraryId: String, sourceId: String, limit: Int, nowMs: Long,
    ): List<ArtistArtworkDispatchRow>

    /** Orphaned future rows must not create deferred wakeups for absent albums. */
    @Query("""SELECT MIN(o.updatedAtMs) FROM artist_artwork_auto_outbox o
        INNER JOIN artist_album_credit_binding b ON b.libraryId = o.libraryId
            AND b.albumKey = o.albumKey AND b.creditKey = o.creditKey
            AND b.associationId = o.associationId
        WHERE o.libraryId = :libraryId AND o.sourceId = :sourceId
          AND o.state = 'PENDING' AND o.updatedAtMs > :nowMs""")
    suspend fun nextBoundDeferredDueAtForRoot(
        libraryId: String, sourceId: String, nowMs: Long,
    ): Long?

    /** Manual retry also cannot spend its capped 16 slots on unbound albums. */
    @Query("""SELECT o.libraryId AS libraryId, o.creditKey AS creditKey,
        o.associationId AS associationId, MIN(o.updatedAtMs) AS readyAtMs
        FROM artist_artwork_auto_outbox o
        INNER JOIN artist_album_credit_binding b ON b.libraryId = o.libraryId
            AND b.albumKey = o.albumKey AND b.creditKey = o.creditKey
            AND b.associationId = o.associationId
        WHERE o.state = 'PENDING' AND o.libraryId = :libraryId
          AND o.sourceId = :sourceId
        GROUP BY o.libraryId, o.creditKey, o.associationId
        ORDER BY readyAtMs ASC LIMIT :limit""")
    suspend fun boundManualRetryCandidates(
        libraryId: String, sourceId: String, limit: Int,
    ): List<ArtistArtworkDispatchRow>

    /** Proven, currently bound dispatch window; unproven rows never monopolize slots. */
    @Query("""SELECT o.libraryId AS libraryId, o.creditKey AS creditKey,
        o.associationId AS associationId, MIN(o.updatedAtMs) AS readyAtMs
        FROM artist_artwork_auto_outbox o
        INNER JOIN artist_album_credit_binding b ON b.libraryId = o.libraryId
          AND b.albumKey = o.albumKey AND b.creditKey = o.creditKey
          AND b.associationId = o.associationId
        INNER JOIN artist_artwork_source_binding_proof p
          ON p.libraryId = o.libraryId AND p.sourceId = o.sourceId
          AND p.albumKey = o.albumKey AND p.creditKey = o.creditKey
          AND p.associationId = o.associationId AND p.confirmedAtMs = b.confirmedAtMs
        WHERE o.state = 'PENDING' AND o.libraryId = :libraryId
          AND o.sourceId = :sourceId AND o.updatedAtMs <= :nowMs
        GROUP BY o.libraryId, o.creditKey, o.associationId
        ORDER BY readyAtMs ASC LIMIT :limit""")
    suspend fun provenPendingDispatchForRoot(
        libraryId: String, sourceId: String, limit: Int, nowMs: Long,
    ): List<ArtistArtworkDispatchRow>

    /** Keyset scan beyond already ENQUEUED/RUNNING jobs. The tie breaker is
     * (creditKey, associationId), not OFFSET: every run is bounded to eight pages.
     * Proof and the current binding are joined BEFORE grouping and limiting.
     * `afterReadyAtMs=-1` begins the first page (timestamps must be nonnegative).
     */
    @Query("""SELECT o.libraryId AS libraryId, o.creditKey AS creditKey,
        o.associationId AS associationId, MIN(o.updatedAtMs) AS readyAtMs
        FROM artist_artwork_auto_outbox o
        INNER JOIN artist_album_credit_binding b ON b.libraryId = o.libraryId
          AND b.albumKey = o.albumKey AND b.creditKey = o.creditKey
          AND b.associationId = o.associationId
        INNER JOIN artist_artwork_source_binding_proof p
          ON p.libraryId = o.libraryId AND p.sourceId = o.sourceId
          AND p.albumKey = o.albumKey AND p.creditKey = o.creditKey
          AND p.associationId = o.associationId AND p.confirmedAtMs = b.confirmedAtMs
        WHERE o.state = 'PENDING' AND o.libraryId = :libraryId
          AND o.sourceId = :sourceId AND o.updatedAtMs <= :nowMs
        GROUP BY o.libraryId, o.creditKey, o.associationId
        HAVING MIN(o.updatedAtMs) > :afterReadyAtMs
          OR (MIN(o.updatedAtMs) = :afterReadyAtMs AND
            (o.creditKey > :afterCreditKey OR
              (o.creditKey = :afterCreditKey AND o.associationId > :afterAssociationId)))
        ORDER BY readyAtMs ASC, o.creditKey ASC, o.associationId ASC LIMIT :limit""")
    suspend fun provenPendingDispatchPageForRoot(
        libraryId: String, sourceId: String, limit: Int, nowMs: Long,
        afterReadyAtMs: Long, afterCreditKey: String, afterAssociationId: String,
    ): List<ArtistArtworkDispatchRow>

    /** Explicit user retry: stable bounded pagination past already queued jobs.
     * This is the same root+proof authority as normal dispatch, but may see future
     * cooldown rows; it MUST NOT turn another root's events into eligible jobs.
     */
    @Query("""SELECT o.libraryId AS libraryId, o.creditKey AS creditKey,
        o.associationId AS associationId, MIN(o.updatedAtMs) AS readyAtMs
        FROM artist_artwork_auto_outbox o
        INNER JOIN artist_album_credit_binding b ON b.libraryId = o.libraryId
          AND b.albumKey = o.albumKey AND b.creditKey = o.creditKey
          AND b.associationId = o.associationId
        INNER JOIN artist_artwork_source_binding_proof p
          ON p.libraryId = o.libraryId AND p.sourceId = o.sourceId
          AND p.albumKey = o.albumKey AND p.creditKey = o.creditKey
          AND p.associationId = o.associationId AND p.confirmedAtMs = b.confirmedAtMs
        WHERE o.state = 'PENDING' AND o.libraryId = :libraryId AND o.sourceId = :sourceId
        GROUP BY o.libraryId, o.creditKey, o.associationId
        HAVING MIN(o.updatedAtMs) > :afterReadyAtMs
          OR (MIN(o.updatedAtMs) = :afterReadyAtMs AND
            (o.creditKey > :afterCreditKey OR
              (o.creditKey = :afterCreditKey AND o.associationId > :afterAssociationId)))
        ORDER BY readyAtMs ASC, o.creditKey ASC, o.associationId ASC LIMIT :limit""")
    suspend fun provenManualRetryPageForRoot(
        libraryId: String, sourceId: String, limit: Int,
        afterReadyAtMs: Long, afterCreditKey: String, afterAssociationId: String,
    ): List<ArtistArtworkDispatchRow>

    /** Future due time excludes unproven and orphaned work. */
    @Query("""SELECT MIN(o.updatedAtMs) FROM artist_artwork_auto_outbox o
        INNER JOIN artist_album_credit_binding b ON b.libraryId = o.libraryId
          AND b.albumKey = o.albumKey AND b.creditKey = o.creditKey
          AND b.associationId = o.associationId
        INNER JOIN artist_artwork_source_binding_proof p
          ON p.libraryId = o.libraryId AND p.sourceId = o.sourceId
          AND p.albumKey = o.albumKey AND p.creditKey = o.creditKey
          AND p.associationId = o.associationId AND p.confirmedAtMs = b.confirmedAtMs
        WHERE o.libraryId = :libraryId AND o.sourceId = :sourceId
          AND o.state = 'PENDING' AND o.updatedAtMs > :nowMs""")
    suspend fun provenNextDeferredDueAtForRoot(
        libraryId: String, sourceId: String, nowMs: Long,
    ): Long?

    /** Explicit retry cannot bypass source provenance. */
    @Query("""SELECT o.libraryId AS libraryId, o.creditKey AS creditKey,
        o.associationId AS associationId, MIN(o.updatedAtMs) AS readyAtMs
        FROM artist_artwork_auto_outbox o
        INNER JOIN artist_album_credit_binding b ON b.libraryId = o.libraryId
          AND b.albumKey = o.albumKey AND b.creditKey = o.creditKey
          AND b.associationId = o.associationId
        INNER JOIN artist_artwork_source_binding_proof p
          ON p.libraryId = o.libraryId AND p.sourceId = o.sourceId
          AND p.albumKey = o.albumKey AND p.creditKey = o.creditKey
          AND p.associationId = o.associationId AND p.confirmedAtMs = b.confirmedAtMs
        WHERE o.state = 'PENDING' AND o.libraryId = :libraryId
          AND o.sourceId = :sourceId
        GROUP BY o.libraryId, o.creditKey, o.associationId
        ORDER BY readyAtMs ASC LIMIT :limit""")
    suspend fun provenManualRetryCandidates(
        libraryId: String, sourceId: String, limit: Int,
    ): List<ArtistArtworkDispatchRow>

    /** First future deadline for the active concrete source: one bounded local wake. */
    @Query("""SELECT MIN(updatedAtMs) FROM artist_artwork_auto_outbox
        WHERE libraryId = :libraryId AND sourceId = :sourceId
          AND state = 'PENDING' AND updatedAtMs > :nowMs""")
    suspend fun nextDeferredDueAtForRoot(
        libraryId: String, sourceId: String, nowMs: Long,
    ): Long?

    /** User-initiated retry only: respects the current root and concrete library. */
    @Query("""SELECT libraryId, creditKey, associationId, MIN(updatedAtMs) AS readyAtMs
        FROM artist_artwork_auto_outbox WHERE state = 'PENDING'
          AND libraryId = :libraryId AND sourceId = :sourceId
        GROUP BY libraryId, creditKey, associationId
        ORDER BY readyAtMs ASC LIMIT :limit""")
    suspend fun manualRetryCandidates(
        libraryId: String, sourceId: String, limit: Int,
    ): List<ArtistArtworkDispatchRow>

    @Query("""UPDATE artist_artwork_auto_outbox SET updatedAtMs = :nowMs
        WHERE state = 'PENDING' AND libraryId = :libraryId AND sourceId = :sourceId
          AND creditKey = :creditKey AND associationId = :associationId""")
    suspend fun activatePendingForAssociation(
        libraryId: String, sourceId: String, creditKey: String, associationId: String, nowMs: Long,
    ): Int

    /** Move an ineligible association behind ready work; prevents cooldown head-of-line starvation. */
    @Query("UPDATE artist_artwork_auto_outbox SET updatedAtMs = :notBeforeMs WHERE libraryId = :libraryId AND creditKey = :creditKey AND associationId = :associationId AND sourceId = :sourceId AND state = 'PENDING' AND updatedAtMs < :notBeforeMs")
    suspend fun deferPendingForAssociation(
        libraryId: String, creditKey: String, associationId: String, sourceId: String, notBeforeMs: Long,
    ): Int

    /** Complete all presently bound evidence, including >1024 albums of one artist.
     * Unbound and other-source evidence stays untouched for conservative cleanup.
     */
    @Query("""UPDATE artist_artwork_auto_outbox SET state = :state, updatedAtMs = :nowMs
        WHERE libraryId = :libraryId AND creditKey = :creditKey
          AND associationId = :associationId AND sourceId = :expectedSource
          AND state = 'PENDING' AND EXISTS (
            SELECT 1 FROM artist_album_credit_binding b
            INNER JOIN artist_artwork_source_binding_proof p
              ON p.libraryId = b.libraryId AND p.albumKey = b.albumKey
              AND p.creditKey = b.creditKey AND p.associationId = b.associationId
              AND p.sourceId = artist_artwork_auto_outbox.sourceId
              AND p.confirmedAtMs = b.confirmedAtMs
            WHERE b.libraryId = artist_artwork_auto_outbox.libraryId
              AND b.albumKey = artist_artwork_auto_outbox.albumKey
              AND b.creditKey = artist_artwork_auto_outbox.creditKey
              AND b.associationId = artist_artwork_auto_outbox.associationId
          )""")
    suspend fun finishBoundAssociation(
        libraryId: String, creditKey: String, associationId: String,
        expectedSource: String, state: String, nowMs: Long,
    ): Int

    @Query("UPDATE artist_artwork_auto_outbox SET state = :state, updatedAtMs = :nowMs WHERE libraryId = :libraryId AND creditKey = :creditKey AND associationId = :associationId AND albumKey = :albumKey AND sourceId = :sourceId AND state = 'PENDING'")
    suspend fun finish(libraryId: String, creditKey: String, associationId: String, albumKey: String, sourceId: String, state: String, nowMs: Long): Int

    @Query("UPDATE artist_artwork_auto_outbox SET state = 'STALE_SOURCE', updatedAtMs = :nowMs WHERE libraryId = :libraryId AND sourceId != :sourceId AND state = 'PENDING'")
    suspend fun invalidateOtherSources(libraryId: String, sourceId: String, nowMs: Long): Int

    @Query("DELETE FROM artist_artwork_auto_outbox WHERE libraryId = :libraryId AND state != 'PENDING' AND updatedAtMs < :beforeMs")
    suspend fun pruneCompleted(libraryId: String, beforeMs: Long): Int

    @Query("DELETE FROM artist_artwork_auto_outbox WHERE libraryId = :libraryId AND state = 'PENDING' AND updatedAtMs < :beforeMs")
    suspend fun pruneStalePending(libraryId: String, beforeMs: Long): Int
}
