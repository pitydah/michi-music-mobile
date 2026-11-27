package org.michimusic.data.artist

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

/** D22. Durable evidence of *preparation only*. This model grants no execution capability. */
@Entity(
    tableName = "artist_bulk_session",
    indices = [Index(value = ["libraryId", "createdAtEpochMs"])],
)
data class ArtistBulkSessionRecord(
    @androidx.room.PrimaryKey val sessionId: String,
    val libraryId: String,
    val sourceId: String,
    val rootEpoch: Long,
    val catalogRevision: Long,
    val libraryRevision: Long,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
    val status: String = PREPARED_NOT_SUBMITTED,
    val revision: Long = 0,
    val receiptCount: Int,
) {
    companion object {
        const val PREPARED_NOT_SUBMITTED = "PREPARED_NOT_SUBMITTED"
        const val CANCELLED_NOT_SUBMITTED = "CANCELLED_NOT_SUBMITTED"
    }
}

@Entity(
    tableName = "artist_bulk_receipt",
    primaryKeys = ["sessionId", "fingerprint"],
    indices = [Index(value = ["sessionId"])],
    foreignKeys = [ForeignKey(
        entity = ArtistBulkSessionRecord::class,
        parentColumns = ["sessionId"], childColumns = ["sessionId"],
        onDelete = ForeignKey.CASCADE,
    )],
)
data class ArtistBulkReceiptRecord(
    val sessionId: String,
    val fingerprint: String,
    val libraryId: String,
    val artistCredit: String,
    val albumIdsJson: String,
    val mbid: String,
    val associationId: String,
    val status: String = ArtistBulkSessionRecord.PREPARED_NOT_SUBMITTED,
    val revision: Long = 0,
    val updatedAtEpochMs: Long,
)

/** Only two terminal states; neither authorizes a provider call or a metadata write. */
@Dao
interface ArtistBulkSessionDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertSession(record: ArtistBulkSessionRecord)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertReceipts(records: List<ArtistBulkReceiptRecord>)

    /** D23: read/insert a unique, explicitly scoped consent receipt. */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertPermission(record: ArtistBulkPermissionRecord)

    @Query("SELECT * FROM artist_bulk_permission WHERE sessionId = :sessionId AND fingerprint = :fingerprint LIMIT 1")
    suspend fun getPermission(sessionId: String, fingerprint: String): ArtistBulkPermissionRecord?

    @Query("SELECT * FROM artist_bulk_permission WHERE sessionId = :sessionId ORDER BY artistCredit, fingerprint")
    suspend fun listPermissions(sessionId: String): List<ArtistBulkPermissionRecord>

    @Query("""UPDATE artist_bulk_permission SET status = 'REVOKED_NOT_DISPATCHED',
        revision = revision + 1, updatedAtEpochMs = :at
        WHERE sessionId = :sessionId AND fingerprint = :fingerprint AND revision = :expectedRevision
          AND status IN ('CONSENT_STORED_NOT_DISPATCHED', 'RESERVED_NO_IO')""")
    suspend fun revokePermissionCas(sessionId: String, fingerprint: String, expectedRevision: Long, at: Long): Int

    @Query("""UPDATE artist_bulk_permission SET status = 'REVOKED_NOT_DISPATCHED',
        revision = revision + 1, updatedAtEpochMs = :at
        WHERE sessionId = :sessionId AND status IN ('CONSENT_STORED_NOT_DISPATCHED', 'RESERVED_NO_IO')""")
    suspend fun revokeSessionPermissions(sessionId: String, at: Long): Int

    /** D23: a permission is never inferred from PREPARED_NOT_SUBMITTED. */
    @Transaction
    suspend fun storePermission(
        request: ArtistBulkPermissionRecord,
        expectedSessionRevision: Long,
        now: Long,
    ): Boolean {
        val session = getSession(request.sessionId) ?: return false
        if (session.status != ArtistBulkSessionRecord.PREPARED_NOT_SUBMITTED ||
            session.revision != expectedSessionRevision ||
            session.libraryId != request.libraryId || session.sourceId != request.sourceId ||
            session.rootEpoch != request.rootEpoch || session.catalogRevision != request.catalogRevision ||
            session.libraryRevision != request.libraryRevision ||
            now < session.createdAtEpochMs ||
            now - session.createdAtEpochMs > ArtistBulkPermissionRecord.MAX_LIFETIME_MS
        ) return false
        if (request.permitId.isBlank() || request.permitId.length > 128 ||
            request.permitId.any(Char::isWhitespace) || request.fingerprint.isBlank() ||
            request.allowedOperation != ArtistBulkPermissionRecord.PROFILE_LOOKUP_ONLY ||
            request.status != ArtistBulkPermissionRecord.CONSENT_STORED_NOT_DISPATCHED ||
            request.revision != 0L || request.issuedAtEpochMs != now ||
            request.updatedAtEpochMs != now || request.expiresAtEpochMs <= now ||
            request.expiresAtEpochMs - now > ArtistBulkPermissionRecord.MAX_LIFETIME_MS
        ) return false
        val receipts = getReceipts(session.sessionId)
        if (receipts.size != session.receiptCount || receipts.any {
                it.libraryId != session.libraryId ||
                    it.status != ArtistBulkSessionRecord.PREPARED_NOT_SUBMITTED ||
                    it.revision != 0L
            }) return false
        val receipt = receipts.singleOrNull { it.fingerprint == request.fingerprint } ?: return false
        if (receipt.artistCredit != request.artistCredit || receipt.mbid != request.mbid ||
            receipt.associationId != request.associationId || receipt.libraryId != request.libraryId
        ) return false
        // ABORT not REPLACE: revoked or expired permissions can never be silently resurrected.
        if (getPermission(request.sessionId, request.fingerprint) != null) return false
        insertPermission(request)
        return getPermission(request.sessionId, request.fingerprint) == request
    }

    @Transaction
    suspend fun revokePermission(sessionId: String, fingerprint: String, revision: Long, now: Long): Boolean {
        require(now > 0)
        val row = getPermission(sessionId, fingerprint) ?: return false
        if (row.status == ArtistBulkPermissionRecord.REVOKED_NOT_DISPATCHED) return true
        if (row.revision != revision || row.status !in setOf(
                ArtistBulkPermissionRecord.CONSENT_STORED_NOT_DISPATCHED,
                ArtistBulkPermissionRecord.RESERVED_NO_IO,
            )) return false
        return revokePermissionCas(sessionId, fingerprint, revision, now) == 1
    }

    // D24: a single global reservation across all libraries. This is only a durable
    // scheduling-intent placeholder; there is deliberately no Worker or provider call.
    @Query("SELECT COUNT(*) FROM artist_bulk_permission WHERE status = 'RESERVED_NO_IO'")
    suspend fun activeNoIoReservations(): Int

    @Query("""UPDATE artist_bulk_permission SET status = 'RELEASED_NO_IO',
        revision = revision + 1, updatedAtEpochMs = :at
        WHERE status = 'RESERVED_NO_IO' AND expiresAtEpochMs <= :at""")
    suspend fun releaseExpiredNoIoReservations(at: Long): Int

    @Query("""UPDATE artist_bulk_permission SET status = 'RESERVED_NO_IO',
        revision = revision + 1, updatedAtEpochMs = :at
        WHERE sessionId = :sessionId AND fingerprint = :fingerprint
          AND permitId = :permitId AND revision = :expectedRevision
          AND status = 'CONSENT_STORED_NOT_DISPATCHED'
          AND issuedAtEpochMs <= :at AND expiresAtEpochMs > :at""")
    suspend fun reserveNoIoCas(
        sessionId: String, fingerprint: String, permitId: String,
        expectedRevision: Long, at: Long,
    ): Int

    @Query("""UPDATE artist_bulk_permission SET status = 'RELEASED_NO_IO',
        revision = revision + 1, updatedAtEpochMs = :at
        WHERE sessionId = :sessionId AND fingerprint = :fingerprint
          AND permitId = :permitId AND revision = :expectedRevision
          AND status = 'RESERVED_NO_IO'""")
    suspend fun releaseNoIoCas(
        sessionId: String, fingerprint: String, permitId: String,
        expectedRevision: Long, at: Long,
    ): Int

    /**
     * Atomically reserves exactly one eligible consent for a future, separately
     * certified executor. No I/O or queue insertion occurs here. Global budget 1.
     * Both expired and cancelled sessions fail closed; an old permit ID never wins.
     */
    @Transaction
    suspend fun reserveWithoutDispatch(
        expected: ArtistBulkPermissionRecord,
        expectedSessionRevision: Long,
        now: Long,
    ): ArtistBulkPermissionRecord? {
        if (now <= 0L) return null
        val session = getSession(expected.sessionId) ?: return null
        val permission = getPermission(expected.sessionId, expected.fingerprint) ?: return null
        val receipts = getReceipts(session.sessionId)
        if (receipts.size != session.receiptCount) return null
        val receipt = receipts.singleOrNull { it.fingerprint == expected.fingerprint } ?: return null
        if (permission != expected || !ArtistBulkReservationPolicy.mayReserve(
                permission, session, receipt, expected.revision,
                expectedSessionRevision, now,
            )) return null
        // Prevent a stale reservation from permanently exhausting the budget.
        releaseExpiredNoIoReservations(now)
        if (activeNoIoReservations() != 0) return null
        if (reserveNoIoCas(permission.sessionId, permission.fingerprint,
                permission.permitId, permission.revision, now) != 1) return null
        val claimed = getPermission(permission.sessionId, permission.fingerprint) ?: return null
        check(claimed == permission.copy(
            status = ArtistBulkPermissionRecord.RESERVED_NO_IO,
            revision = permission.revision + 1, updatedAtEpochMs = now,
        )) { "No-I/O reservation mismatch; rolled back" }
        return claimed
    }

    /** Terminal release; never restores consent, so replay cannot dispatch twice. */
    @Transaction
    suspend fun releaseWithoutDispatch(
        expected: ArtistBulkPermissionRecord, now: Long,
    ): ArtistBulkPermissionRecord? {
        if (now <= 0L || now < expected.updatedAtEpochMs ||
            expected.status != ArtistBulkPermissionRecord.RESERVED_NO_IO) return null
        if (getPermission(expected.sessionId, expected.fingerprint) != expected) return null
        if (releaseNoIoCas(expected.sessionId, expected.fingerprint,
                expected.permitId, expected.revision, now) != 1) return null
        val closed = getPermission(expected.sessionId, expected.fingerprint) ?: return null
        check(closed == expected.copy(
            status = ArtistBulkPermissionRecord.RELEASED_NO_IO,
            revision = expected.revision + 1, updatedAtEpochMs = now,
        )) { "No-I/O release mismatch; rolled back" }
        return closed
    }

    @Query("SELECT * FROM artist_bulk_session WHERE sessionId = :sessionId LIMIT 1")
    suspend fun getSession(sessionId: String): ArtistBulkSessionRecord?

    @Query("SELECT * FROM artist_bulk_receipt WHERE sessionId = :sessionId ORDER BY artistCredit, fingerprint")
    suspend fun getReceipts(sessionId: String): List<ArtistBulkReceiptRecord>

    @Query("SELECT * FROM artist_bulk_session WHERE libraryId = :libraryId ORDER BY createdAtEpochMs DESC, sessionId DESC LIMIT 1")
    suspend fun latestForLibrary(libraryId: String): ArtistBulkSessionRecord?

    @Query("""UPDATE artist_bulk_session SET status = 'CANCELLED_NOT_SUBMITTED',
        revision = revision + 1, updatedAtEpochMs = :at
        WHERE sessionId = :sessionId AND revision = :expectedRevision
            AND status = 'PREPARED_NOT_SUBMITTED'""")
    suspend fun cancelSessionCas(sessionId: String, expectedRevision: Long, at: Long): Int

    @Query("""UPDATE artist_bulk_receipt SET status = 'CANCELLED_NOT_SUBMITTED',
        revision = revision + 1, updatedAtEpochMs = :at
        WHERE sessionId = :sessionId AND status = 'PREPARED_NOT_SUBMITTED' AND revision = 0""")
    suspend fun cancelReceiptRows(sessionId: String, at: Long): Int

    @Transaction
    suspend fun createPrepared(session: ArtistBulkSessionRecord, receipts: List<ArtistBulkReceiptRecord>) {
        require(session.status == ArtistBulkSessionRecord.PREPARED_NOT_SUBMITTED && session.revision == 0L)
        require(session.sessionId.isNotBlank() && session.libraryId.isNotBlank() && session.sourceId.isNotBlank())
        require(session.rootEpoch >= 0 && session.catalogRevision >= 0 && session.libraryRevision >= 0)
        require(session.createdAtEpochMs > 0L && session.receiptCount in 1..64 && receipts.size == session.receiptCount)
        require(receipts.map { it.fingerprint }.toSet().size == receipts.size)
        require(receipts.all {
            it.sessionId == session.sessionId && it.libraryId == session.libraryId &&
                it.status == ArtistBulkSessionRecord.PREPARED_NOT_SUBMITTED && it.revision == 0L &&
                it.fingerprint.length == 64 && it.fingerprint.all { c -> c in '0'..'9' || c in 'a'..'f' } &&
                it.artistCredit.isNotBlank() && it.albumIdsJson.isNotBlank() &&
                it.mbid.isNotBlank() && it.associationId.isNotBlank()
        })
        // ABORT on conflict. Never use SQLite REPLACE to substitute prior consent/identity.
        insertSession(session)
        insertReceipts(receipts)
    }

    @Transaction
    suspend fun cancelPrepared(sessionId: String, expectedRevision: Long, now: Long): Boolean {
        require(now > 0L)
        val current = getSession(sessionId) ?: return false
        if (current.status == ArtistBulkSessionRecord.CANCELLED_NOT_SUBMITTED) {
            return getReceipts(sessionId).size == current.receiptCount &&
                getReceipts(sessionId).all { it.status == ArtistBulkSessionRecord.CANCELLED_NOT_SUBMITTED } &&
                listPermissions(sessionId).none {
                    it.status == ArtistBulkPermissionRecord.CONSENT_STORED_NOT_DISPATCHED ||
                        it.status == ArtistBulkPermissionRecord.RESERVED_NO_IO
                }
        }
        if (current.status != ArtistBulkSessionRecord.PREPARED_NOT_SUBMITTED ||
            current.revision != expectedRevision) return false
        if (cancelSessionCas(sessionId, expectedRevision, now) != 1) return false
        val pendingPermissions = listPermissions(sessionId).count {
            it.status == ArtistBulkPermissionRecord.CONSENT_STORED_NOT_DISPATCHED ||
                it.status == ArtistBulkPermissionRecord.RESERVED_NO_IO
        }
        check(revokeSessionPermissions(sessionId, now) == pendingPermissions) {
            "Incomplete permission revocation; rolled back"
        }
        val rowsChanged = cancelReceiptRows(sessionId, now)
        // A mismatch throws within Room @Transaction so neither parent nor children commit.
        check(rowsChanged == current.receiptCount) { "Incomplete bulk cancellation; rolled back" }
        return true
    }

    @Transaction
    suspend fun readConsistent(sessionId: String): Pair<ArtistBulkSessionRecord, List<ArtistBulkReceiptRecord>>? {
        val session = getSession(sessionId) ?: return null
        val receipts = getReceipts(sessionId)
        if (receipts.size != session.receiptCount || receipts.any {
                it.sessionId != session.sessionId || it.libraryId != session.libraryId || it.status != session.status
            }) return null
        return session to receipts
    }
}
