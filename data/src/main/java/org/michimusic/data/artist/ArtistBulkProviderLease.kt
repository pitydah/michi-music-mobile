package org.michimusic.data.artist

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import java.util.UUID

/** D27: a cross-process rate-budget record. It is NOT a record of HTTP traffic. */
@Entity(tableName = "artist_bulk_provider_quota")
data class ArtistBulkProviderQuotaRecord(
    @androidx.room.PrimaryKey val provider: String,
    val nextEligibleAtEpochMs: Long,
    val updatedAtEpochMs: Long,
    val revision: Long,
)

/** Staged admission cannot be converted into a dispatch credential. */
@Entity(
    tableName = "artist_bulk_provider_lease",
    indices = [
        Index(value = ["permitId"], unique = true),
        Index(value = ["sessionId", "fingerprint"]),
        Index(value = ["state", "expiresAtEpochMs"]),
    ],
    foreignKeys = [ForeignKey(
        entity = ArtistBulkPermissionRecord::class,
        parentColumns = ["sessionId", "fingerprint"],
        childColumns = ["sessionId", "fingerprint"],
        onDelete = ForeignKey.CASCADE,
    )],
)
data class ArtistBulkProviderLeaseRecord(
    @androidx.room.PrimaryKey val leaseId: String,
    val sessionId: String,
    val fingerprint: String,
    val permitId: String,
    val provider: String,
    val mbid: String,
    val permissionRevision: Long,
    val libraryId: String,
    val sourceId: String,
    val rootEpoch: Long,
    val catalogRevision: Long,
    val libraryRevision: Long,
    val issuedAtEpochMs: Long,
    val expiresAtEpochMs: Long,
    val updatedAtEpochMs: Long,
    val state: String,
    val revision: Long,
) {
    companion object {
        const val STAGED_NO_IO = "STAGED_NO_IO"
        const val ABANDONED_NO_IO = "ABANDONED_NO_IO"
        const val EXPIRED_NO_IO = "EXPIRED_NO_IO"
    }

    val mayDispatch: Boolean get() = false
}

/** Pure rules: the source permission and D26 preview cannot grant network access. */
object ArtistBulkProviderLeasePolicy {
    const val LEASE_TTL_MS = 30_000L
    const val MAX_PREPARED_RECEIPTS = 64

    fun mayStage(
        preview: ArtistBulkProfileLookupPreview,
        permission: ArtistBulkPermissionRecord,
        session: ArtistBulkSessionRecord,
        sourceReceipt: ArtistBulkReceiptRecord,
        scope: ArtistBulkTerminalScope,
        conditions: ArtistBulkProviderConditions,
        now: Long,
        preparedCount: Int,
        activeReservations: Int,
    ): Boolean {
        if (preview.mayDispatch || preview.provider != ArtistBulkProfileProvider.MUSICBRAINZ_ARTIST_BY_MBID ||
            !scope.isValid() || preview.expectedScope != scope ||
            permission.sessionId != preview.sessionId ||
            permission.fingerprint != preview.fingerprint ||
            permission.permitId != preview.permitId ||
            permission.revision != preview.permissionRevision ||
            session.sessionId != permission.sessionId ||
            session.libraryId != permission.libraryId || session.sourceId != permission.sourceId ||
            session.rootEpoch != permission.rootEpoch ||
            session.catalogRevision != permission.catalogRevision ||
            session.libraryRevision != permission.libraryRevision ||
            session.status != ArtistBulkSessionRecord.PREPARED_NOT_SUBMITTED ||
            session.receiptCount !in 1..MAX_PREPARED_RECEIPTS ||
            preparedCount != session.receiptCount || activeReservations != 1 ||
            sourceReceipt.sessionId != permission.sessionId ||
            sourceReceipt.fingerprint != permission.fingerprint ||
            sourceReceipt.libraryId != permission.libraryId ||
            sourceReceipt.status != ArtistBulkSessionRecord.PREPARED_NOT_SUBMITTED ||
            sourceReceipt.artistCredit != permission.artistCredit ||
            sourceReceipt.mbid.lowercase(java.util.Locale.ROOT) != preview.mbid ||
            sourceReceipt.associationId != permission.associationId
        ) return false
        return ArtistBulkProviderPreflight.inspect(permission, scope, conditions, now) == preview
    }

    /** A wall-clock jump backward or a deadline overflow blocks new admission. */
    fun deadlines(now: Long, permissionExpiry: Long, quota: ArtistBulkProviderQuotaRecord): Pair<Long, Long>? {
        if (now <= 0 || now < quota.updatedAtEpochMs || quota.revision < 0 ||
            quota.nextEligibleAtEpochMs > now || quota.nextEligibleAtEpochMs < 0 ||
            permissionExpiry <= now || now > Long.MAX_VALUE - LEASE_TTL_MS ||
            now > Long.MAX_VALUE - ArtistBulkProviderRetryPolicy.MUSICBRAINZ_MIN_INTERVAL_MS
        ) return null
        val expires = minOf(permissionExpiry, now + LEASE_TTL_MS)
        if (expires <= now) return null
        return expires to (now + ArtistBulkProviderRetryPolicy.MUSICBRAINZ_MIN_INTERVAL_MS)
    }

    fun matches(lease: ArtistBulkProviderLeaseRecord, preview: ArtistBulkProfileLookupPreview): Boolean =
        !lease.mayDispatch && lease.state == ArtistBulkProviderLeaseRecord.STAGED_NO_IO &&
        lease.sessionId == preview.sessionId && lease.fingerprint == preview.fingerprint &&
        lease.permitId == preview.permitId && lease.provider == preview.provider.name &&
        lease.mbid == preview.mbid && lease.permissionRevision == preview.permissionRevision &&
        lease.libraryId == preview.expectedScope.libraryId &&
        lease.sourceId == preview.expectedScope.sourceId &&
        lease.rootEpoch == preview.expectedScope.rootEpoch &&
        lease.catalogRevision == preview.expectedScope.catalogRevision &&
        lease.libraryRevision == preview.expectedScope.libraryRevision
}

/**
 * D27 local DB journal. The only side effects are SQLite writes. The method name
 * intentionally excludes dispatch: there is no HTTP/Worker dependency or I/O token.
 * Its atomicity across processes relies on Room @Transaction and SQLite serialization;
 * verify on Android before using it as the foundation for a future executor.
 */
@Dao
interface ArtistBulkProviderLeaseDao {
    @Query("SELECT * FROM artist_bulk_provider_quota WHERE provider = :provider LIMIT 1")
    suspend fun quota(provider: String): ArtistBulkProviderQuotaRecord?

    @Query("SELECT * FROM artist_bulk_provider_lease WHERE permitId = :permitId LIMIT 1")
    suspend fun leaseForPermit(permitId: String): ArtistBulkProviderLeaseRecord?

    @Query("SELECT * FROM artist_bulk_permission WHERE sessionId = :sessionId AND fingerprint = :fingerprint LIMIT 1")
    suspend fun permission(sessionId: String, fingerprint: String): ArtistBulkPermissionRecord?

    @Query("SELECT * FROM artist_bulk_session WHERE sessionId = :sessionId LIMIT 1")
    suspend fun session(sessionId: String): ArtistBulkSessionRecord?

    @Query("SELECT * FROM artist_bulk_receipt WHERE sessionId = :sessionId AND fingerprint = :fingerprint LIMIT 1")
    suspend fun sourceReceipt(sessionId: String, fingerprint: String): ArtistBulkReceiptRecord?

    @Query("SELECT COUNT(*) FROM artist_bulk_receipt WHERE sessionId = :sessionId AND status = 'PREPARED_NOT_SUBMITTED'")
    suspend fun preparedCount(sessionId: String): Int

    @Query("SELECT COUNT(*) FROM artist_bulk_permission WHERE status = 'RESERVED_NO_IO'")
    suspend fun activeReservations(): Int

    @Query("SELECT COUNT(*) FROM artist_bulk_provider_lease WHERE state = 'STAGED_NO_IO' AND expiresAtEpochMs > :now")
    suspend fun liveStagedLeases(now: Long): Int

    @Query("SELECT COUNT(*) FROM artist_bulk_terminal_receipt WHERE sessionId = :sessionId AND fingerprint = :fingerprint")
    suspend fun terminalCount(sessionId: String, fingerprint: String): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun seedQuota(record: ArtistBulkProviderQuotaRecord): Long

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertLease(record: ArtistBulkProviderLeaseRecord)

    @Query("""UPDATE artist_bulk_provider_quota
        SET nextEligibleAtEpochMs = :next, updatedAtEpochMs = :now, revision = revision + 1
        WHERE provider = :provider AND revision = :revision AND updatedAtEpochMs <= :now
          AND nextEligibleAtEpochMs <= :now""")
    suspend fun advanceQuotaCas(provider: String, revision: Long, now: Long, next: Long): Int

    @Query("""UPDATE artist_bulk_provider_lease SET state = 'EXPIRED_NO_IO',
        revision = revision + 1, updatedAtEpochMs = :now
        WHERE state = 'STAGED_NO_IO' AND expiresAtEpochMs <= :now AND updatedAtEpochMs <= :now""")
    suspend fun expireLeases(now: Long): Int

    @Query("""UPDATE artist_bulk_provider_lease SET state = 'ABANDONED_NO_IO',
        revision = revision + 1, updatedAtEpochMs = :now
        WHERE leaseId = :leaseId AND state = 'STAGED_NO_IO' AND revision = :revision
          AND updatedAtEpochMs <= :now AND expiresAtEpochMs > :now""")
    suspend fun abandonCas(leaseId: String, revision: Long, now: Long): Int

    /** Expiry is exact-lease CAS; never change unrelated admissions during settlement. */
    @Query("""UPDATE artist_bulk_provider_lease SET state = 'EXPIRED_NO_IO',
        revision = revision + 1, updatedAtEpochMs = :now
        WHERE leaseId = :leaseId AND permitId = :permitId AND state = 'STAGED_NO_IO'
          AND revision = :revision AND expiresAtEpochMs <= :now AND updatedAtEpochMs <= :now""")
    suspend fun expireOneCas(leaseId: String, permitId: String, revision: Long, now: Long): Int

    /** No operator returns any form of a network capability; admission is local evidence only. */
    @Transaction
    suspend fun stageWithoutIo(
        preview: ArtistBulkProfileLookupPreview,
        scope: ArtistBulkTerminalScope,
        conditions: ArtistBulkProviderConditions,
        now: Long,
    ): ArtistBulkProviderLeaseRecord? {
        if (now <= 0 || preview.mayDispatch) return null
        // Only one transaction can advance the quota row, even across processes.
        expireLeases(now)
        val actual = permission(preview.sessionId, preview.fingerprint) ?: return null
        val owningSession = session(preview.sessionId) ?: return null
        val receipt = sourceReceipt(preview.sessionId, preview.fingerprint) ?: return null
        if (terminalCount(preview.sessionId, preview.fingerprint) != 0 ||
            !ArtistBulkProviderLeasePolicy.mayStage(preview, actual, owningSession, receipt, scope,
                conditions, now, preparedCount(preview.sessionId), activeReservations())
        ) return null
        val previous = leaseForPermit(preview.permitId)
        if (previous != null) {
            // Replays get the SAME receipt only while the original exact scope,
            // permission and session remain current. No new quota is consumed.
            return previous.takeIf { ArtistBulkProviderLeasePolicy.matches(it, preview) &&
                now >= it.issuedAtEpochMs && now < it.expiresAtEpochMs &&
                liveStagedLeases(now) == 1
            }
        }
        if (liveStagedLeases(now) != 0) return null
        val providerKey = preview.provider.name
        seedQuota(ArtistBulkProviderQuotaRecord(providerKey, 0L, 0L, 0L))
        val currentQuota = quota(providerKey) ?: return null
        val (expiry, next) = ArtistBulkProviderLeasePolicy.deadlines(
            now, actual.expiresAtEpochMs, currentQuota,
        ) ?: return null
        if (advanceQuotaCas(providerKey, currentQuota.revision, now, next) != 1) return null
        val lease = ArtistBulkProviderLeaseRecord(
            leaseId = UUID.randomUUID().toString(), sessionId = actual.sessionId,
            fingerprint = actual.fingerprint, permitId = actual.permitId,
            provider = providerKey, mbid = preview.mbid, permissionRevision = actual.revision,
            libraryId = scope.libraryId, sourceId = scope.sourceId, rootEpoch = scope.rootEpoch,
            catalogRevision = scope.catalogRevision, libraryRevision = scope.libraryRevision,
            issuedAtEpochMs = now, expiresAtEpochMs = expiry, updatedAtEpochMs = now,
            state = ArtistBulkProviderLeaseRecord.STAGED_NO_IO, revision = 0L,
        )
        insertLease(lease)
        return lease // Explicitly NOT HTTP permission.
    }

    /** Irreversible, local-only. No effect on D24 consent or D25 terminal receipt. */
    @Transaction
    suspend fun abandonWithoutIo(expected: ArtistBulkProviderLeaseRecord, now: Long): Boolean {
        if (now <= 0 || now < expected.updatedAtEpochMs || expected.state != ArtistBulkProviderLeaseRecord.STAGED_NO_IO ||
            now >= expected.expiresAtEpochMs || leaseForPermit(expected.permitId) != expected
        ) return false
        return abandonCas(expected.leaseId, expected.revision, now) == 1
    }
}
