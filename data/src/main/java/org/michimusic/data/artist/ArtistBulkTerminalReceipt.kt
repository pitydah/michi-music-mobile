package org.michimusic.data.artist

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import java.security.MessageDigest

/**
 * D25: a terminal observation is evidence, NOT permission to query a provider or
 * publish artist identity, biographies, photographs or music-file metadata.
 * D25 only persists the two outcomes provable without external I/O.
 */
@androidx.room.Entity(
    tableName = "artist_bulk_terminal_receipt",
    primaryKeys = ["sessionId", "fingerprint"],
    indices = [Index(value = ["sessionId"]), Index(value = ["permitId"], unique = true)],
    foreignKeys = [ForeignKey(
        entity = ArtistBulkPermissionRecord::class,
        parentColumns = ["sessionId", "fingerprint"],
        childColumns = ["sessionId", "fingerprint"],
        onDelete = ForeignKey.CASCADE,
    )],
)
data class ArtistBulkTerminalReceiptRecord(
    val sessionId: String,
    val fingerprint: String,
    val permitId: String,
    val outcome: String,
    val evidenceKind: String,
    val reservationRevision: Long,
    val libraryId: String,
    val sourceId: String,
    val rootEpoch: Long,
    val catalogRevision: Long,
    val libraryRevision: Long,
    val completedAtEpochMs: Long,
    val receiptDigest: String,
) {
    companion object {
        const val NO_IO_RECOVERY = "NO_IO_RECOVERY"
    }
}

/** Results expected from a later, certified provider adapter are only a vocabulary here. */
enum class ArtistBulkTerminalOutcome {
    NO_MATCH, VERIFIED_PROFILE, NETWORK_FAILURE, CANCELLED, STALE_SCOPE;

    val mayBeObservedWithoutIo: Boolean
        get() = this == CANCELLED || this == STALE_SCOPE
}

/** A complete root/catalog/library scope; never inferred from a UI credit alone. */
data class ArtistBulkTerminalScope(
    val libraryId: String,
    val sourceId: String,
    val rootEpoch: Long,
    val catalogRevision: Long,
    val libraryRevision: Long,
) {
    fun isValid(): Boolean = libraryId.isNotBlank() && libraryId != "SCOPE_ALL" &&
        sourceId.isNotBlank() && rootEpoch >= 0 && catalogRevision >= 0 && libraryRevision >= 0

    fun matches(permission: ArtistBulkPermissionRecord): Boolean =
        libraryId == permission.libraryId && sourceId == permission.sourceId &&
        rootEpoch == permission.rootEpoch && catalogRevision == permission.catalogRevision &&
        libraryRevision == permission.libraryRevision
}

/** Deterministic corruption/checksum marker, not a signature or proof of provider origin. */
object ArtistBulkTerminalPolicy {
    fun mayCloseWithoutIo(
        reservation: ArtistBulkPermissionRecord,
        observed: ArtistBulkTerminalScope,
        outcome: ArtistBulkTerminalOutcome,
        at: Long,
    ): Boolean = reservation.status == ArtistBulkPermissionRecord.RESERVED_NO_IO &&
        reservation.allowedOperation == ArtistBulkPermissionRecord.PROFILE_LOOKUP_ONLY &&
        reservation.permitId.isNotBlank() && reservation.sessionId.isNotBlank() &&
        reservation.fingerprint.matches(Regex("[0-9a-f]{64}")) &&
        reservation.revision > 0 && reservation.issuedAtEpochMs > 0 &&
        reservation.expiresAtEpochMs > reservation.issuedAtEpochMs &&
        reservation.expiresAtEpochMs - reservation.issuedAtEpochMs <= ArtistBulkPermissionRecord.MAX_LIFETIME_MS &&
        observed.isValid() && outcome.mayBeObservedWithoutIo &&
        at > 0 && at >= reservation.updatedAtEpochMs && at >= reservation.issuedAtEpochMs &&
        (outcome == ArtistBulkTerminalOutcome.CANCELLED ||
            !observed.matches(reservation) || at >= reservation.expiresAtEpochMs)

    fun digest(row: ArtistBulkTerminalReceiptRecord): String {
        // All fields length-prefixed: no ambiguous concatenations and no raw provider payload.
        val fields = listOf("ARTFIX2-D25-v1", row.sessionId, row.fingerprint, row.permitId,
            row.outcome, row.evidenceKind, row.reservationRevision.toString(),
            row.libraryId, row.sourceId, row.rootEpoch.toString(),
            row.catalogRevision.toString(), row.libraryRevision.toString(),
            row.completedAtEpochMs.toString())
        val bytes = fields.joinToString("") { "${it.length}:$it" }.toByteArray(Charsets.UTF_8)
        return MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
    }

    fun authentic(row: ArtistBulkTerminalReceiptRecord): Boolean =
        row.evidenceKind == ArtistBulkTerminalReceiptRecord.NO_IO_RECOVERY &&
        row.outcome in setOf(ArtistBulkTerminalOutcome.CANCELLED.name,
            ArtistBulkTerminalOutcome.STALE_SCOPE.name) &&
        row.receiptDigest.matches(Regex("[0-9a-f]{64}")) && row.receiptDigest == digest(row)
}

/**
 * The journal is the sole authority for atomic terminal evidence. No scheduler,
 * network adapter or writer is reachable from this DAO.
 */
@Dao
interface ArtistBulkTerminalDao {
    @Query("SELECT * FROM artist_bulk_terminal_receipt WHERE sessionId = :sessionId AND fingerprint = :fingerprint LIMIT 1")
    suspend fun findTerminal(sessionId: String, fingerprint: String): ArtistBulkTerminalReceiptRecord?

    @Query("SELECT * FROM artist_bulk_terminal_receipt WHERE sessionId = :sessionId ORDER BY completedAtEpochMs, fingerprint LIMIT :limit")
    suspend fun listTerminals(sessionId: String, limit: Int = 64): List<ArtistBulkTerminalReceiptRecord>

    @Query("SELECT * FROM artist_bulk_permission WHERE sessionId = :sessionId AND fingerprint = :fingerprint LIMIT 1")
    suspend fun findPermission(sessionId: String, fingerprint: String): ArtistBulkPermissionRecord?

    @Query("SELECT * FROM artist_bulk_session WHERE sessionId = :sessionId LIMIT 1")
    suspend fun findSession(sessionId: String): ArtistBulkSessionRecord?

    @Query("SELECT * FROM artist_bulk_receipt WHERE sessionId = :sessionId AND fingerprint = :fingerprint LIMIT 1")
    suspend fun findSourceReceipt(sessionId: String, fingerprint: String): ArtistBulkReceiptRecord?

    @Query("SELECT COUNT(*) FROM artist_bulk_receipt WHERE sessionId = :sessionId AND status = 'PREPARED_NOT_SUBMITTED'")
    suspend fun countPreparedSourceReceipts(sessionId: String): Int

    @Query("SELECT * FROM artist_bulk_permission WHERE sessionId = :sessionId AND status = 'RESERVED_NO_IO' ORDER BY artistCredit, fingerprint LIMIT :limit")
    suspend fun listReservedNoIo(sessionId: String, limit: Int = 65): List<ArtistBulkPermissionRecord>

    @Query("""UPDATE artist_bulk_permission SET status = 'RELEASED_NO_IO',
        revision = revision + 1, updatedAtEpochMs = :at
        WHERE sessionId = :sessionId AND fingerprint = :fingerprint
          AND permitId = :permitId AND status = 'RESERVED_NO_IO' AND revision = :revision""")
    suspend fun releaseCas(sessionId: String, fingerprint: String, permitId: String, revision: Long, at: Long): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertTerminal(record: ArtistBulkTerminalReceiptRecord)

    /** Idempotent compare-and-swap and insert, in a single Room transaction. */
    @Transaction
    suspend fun closeWithoutIo(
        expected: ArtistBulkPermissionRecord,
        observed: ArtistBulkTerminalScope,
        outcome: ArtistBulkTerminalOutcome,
        now: Long,
    ): ArtistBulkTerminalReceiptRecord? {
        val prior = findTerminal(expected.sessionId, expected.fingerprint)
        if (prior != null) return prior.takeIf {
            ArtistBulkTerminalPolicy.authentic(it) && it.permitId == expected.permitId &&
                it.reservationRevision == expected.revision && it.outcome == outcome.name &&
                it.libraryId == expected.libraryId && it.sourceId == expected.sourceId &&
                it.rootEpoch == expected.rootEpoch && it.catalogRevision == expected.catalogRevision &&
                it.libraryRevision == expected.libraryRevision
        }
        if (!ArtistBulkTerminalPolicy.mayCloseWithoutIo(expected, observed, outcome, now)) return null
        val session = findSession(expected.sessionId) ?: return null
        val source = findSourceReceipt(expected.sessionId, expected.fingerprint) ?: return null
        val actual = findPermission(expected.sessionId, expected.fingerprint) ?: return null
        if (session.status != ArtistBulkSessionRecord.PREPARED_NOT_SUBMITTED ||
            actual != expected || source.status != ArtistBulkSessionRecord.PREPARED_NOT_SUBMITTED ||
            source.libraryId != expected.libraryId || source.artistCredit != expected.artistCredit ||
            source.mbid != expected.mbid || source.associationId != expected.associationId ||
            session.libraryId != expected.libraryId || session.sourceId != expected.sourceId ||
            session.rootEpoch != expected.rootEpoch || session.catalogRevision != expected.catalogRevision ||
            session.libraryRevision != expected.libraryRevision ||
            session.receiptCount !in 1..64 ||
            countPreparedSourceReceipts(session.sessionId) != session.receiptCount
        ) return null
        if (releaseCas(expected.sessionId, expected.fingerprint, expected.permitId,
                expected.revision, now) != 1) return null
        val row = ArtistBulkTerminalReceiptRecord(
            sessionId = expected.sessionId, fingerprint = expected.fingerprint,
            permitId = expected.permitId, outcome = outcome.name,
            evidenceKind = ArtistBulkTerminalReceiptRecord.NO_IO_RECOVERY,
            reservationRevision = expected.revision, libraryId = expected.libraryId,
            sourceId = expected.sourceId, rootEpoch = expected.rootEpoch,
            catalogRevision = expected.catalogRevision, libraryRevision = expected.libraryRevision,
            completedAtEpochMs = now, receiptDigest = "",
        ).let { it.copy(receiptDigest = ArtistBulkTerminalPolicy.digest(it)) }
        insertTerminal(row)
        return findTerminal(row.sessionId, row.fingerprint)?.takeIf { it == row }
            ?: error("The terminal receipt was not durably committed")
    }

    /**
     * Manual/on-open recovery only. Old D24 releases without a terminal receipt
     * cannot be classified retroactively. Such rows stay absent, never fabricated.
     */
    @Transaction
    suspend fun reconcileStaleWithoutIo(
        sessionId: String,
        observed: ArtistBulkTerminalScope,
        now: Long,
    ): List<ArtistBulkTerminalReceiptRecord> {
        if (sessionId.isBlank() || !observed.isValid() || now <= 0) return emptyList()
        val rows = listReservedNoIo(sessionId)
        if (rows.size > 64) return emptyList() // corrupted/oversized queue: fail closed
        return rows.mapNotNull { expected ->
            if (!observed.matches(expected) || now >= expected.expiresAtEpochMs) {
                closeWithoutIo(expected, observed, ArtistBulkTerminalOutcome.STALE_SCOPE, now)
            } else null
        }
    }
}
