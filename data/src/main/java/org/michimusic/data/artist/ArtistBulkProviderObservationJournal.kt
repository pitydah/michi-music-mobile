package org.michimusic.data.artist

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

/** D28: local untrusted review note, not proof that HTTP ever occurred.
 * A single lease may have ONE immutable review, and no review is an identity authority.
 */
@Entity(
    tableName = "artist_bulk_provider_observation",
    indices = [Index(value = ["sessionId", "fingerprint"])],
    foreignKeys = [ForeignKey(
        entity = ArtistBulkProviderLeaseRecord::class,
        parentColumns = ["leaseId"], childColumns = ["leaseId"],
        onDelete = ForeignKey.CASCADE,
    )],
)
data class ArtistBulkProviderObservationRecord(
    @androidx.room.PrimaryKey val leaseId: String,
    val sessionId: String,
    val fingerprint: String,
    val permitId: String,
    val provider: String,
    val mbid: String,
    val observedAtEpochMs: Long,
    val recordedAtEpochMs: Long,
    val httpStatusUnverified: Int,
    val responseBytes: Long,
    val responseSha256Unverified: String?,
    val contentType: String?,
    val classification: String,
    val retryAtEpochMs: Long?,
    val origin: String = ORIGIN_UNVERIFIED,
) {
    companion object { const val ORIGIN_UNVERIFIED = "UNVERIFIED_LOCAL_REVIEW_NO_IO" }
    val mayDispatch: Boolean get() = false
    val mayPublishIdentity: Boolean get() = false
    val mayPublishArtwork: Boolean get() = false
}

object ArtistBulkProviderObservationPolicy {
    /** Only a live, exactly scoped, non-terminal NO_IO lease can receive an untrusted note. */
    fun mayRecord(lease: ArtistBulkProviderLeaseRecord,
        preview: ArtistBulkProfileLookupPreview, permission: ArtistBulkPermissionRecord,
        session: ArtistBulkSessionRecord, receipt: ArtistBulkReceiptRecord,
        scope: ArtistBulkTerminalScope, note: ArtistBulkProviderBoundedObservation,
        now: Long, liveLeases: Int, activeReservations: Int, terminalCount: Int): Boolean {
        if (now <= 0L || now < lease.issuedAtEpochMs || now >= lease.expiresAtEpochMs ||
            now >= preview.expiresAtEpochMs || liveLeases != 1 || activeReservations != 1 ||
            terminalCount != 0 || !scope.isValid() || scope != preview.expectedScope ||
            !ArtistBulkProviderLeasePolicy.matches(lease, preview) ||
            permission.status != ArtistBulkPermissionRecord.RESERVED_NO_IO ||
            permission.permitId != lease.permitId || permission.revision != lease.permissionRevision ||
            permission.sessionId != lease.sessionId || permission.fingerprint != lease.fingerprint ||
            permission.mbid.lowercase(java.util.Locale.ROOT) != lease.mbid ||
            permission.expiresAtEpochMs <= now ||
            session.sessionId != lease.sessionId ||
            session.status != ArtistBulkSessionRecord.PREPARED_NOT_SUBMITTED ||
            session.libraryId != lease.libraryId || session.sourceId != lease.sourceId ||
            session.rootEpoch != lease.rootEpoch || session.catalogRevision != lease.catalogRevision ||
            session.libraryRevision != lease.libraryRevision ||
            receipt.sessionId != lease.sessionId || receipt.fingerprint != lease.fingerprint ||
            receipt.status != ArtistBulkSessionRecord.PREPARED_NOT_SUBMITTED ||
            receipt.libraryId != lease.libraryId || receipt.artistCredit != permission.artistCredit ||
            receipt.associationId != permission.associationId ||
            receipt.mbid.lowercase(java.util.Locale.ROOT) != lease.mbid ||
            note.mayDispatch || note.mayPersistVerifiedProfile ||
            note.observation.provider.name != lease.provider ||
            note.observation.observedAtEpochMs < lease.issuedAtEpochMs ||
            note.observation.observedAtEpochMs > now ||
            note.observation.responseByteCount !in 0..ArtistBulkProviderResultPolicy.MAX_BODY_BYTES ||
            note.classifiedAtEpochMs < note.observation.observedAtEpochMs ||
            note.classifiedAtEpochMs > now ||
            note.classifiedAtEpochMs >= lease.expiresAtEpochMs ||
            (note.observation.responseByteCount > 0 && note.bodySha256 == null) ||
            (note.observation.responseByteCount == 0L && note.bodySha256 != null) ||
            (note.bodySha256 != null && !Regex("^[a-f0-9]{64}$").matches(note.bodySha256)) ||
            ArtistBulkProviderResultPolicy.classify(preview, note.observation, scope,
                note.classifiedAtEpochMs, note.attempt, note.lastRequestAtEpochMs) != note.assessment
        ) return false
        return true
    }

    fun sameNote(row: ArtistBulkProviderObservationRecord, note: ArtistBulkProviderBoundedObservation,
        lease: ArtistBulkProviderLeaseRecord): Boolean =
        row.origin == ArtistBulkProviderObservationRecord.ORIGIN_UNVERIFIED &&
        row.leaseId == lease.leaseId && row.sessionId == lease.sessionId &&
        row.fingerprint == lease.fingerprint && row.permitId == lease.permitId &&
        row.provider == lease.provider && row.mbid == lease.mbid &&
        row.observedAtEpochMs == note.observation.observedAtEpochMs &&
        row.httpStatusUnverified == note.observation.httpStatus &&
        row.responseBytes == note.observation.responseByteCount &&
        row.responseSha256Unverified == note.bodySha256 &&
        row.contentType == note.observation.contentType &&
        row.classification == note.assessment.kind.name &&
        row.retryAtEpochMs == note.assessment.retryAtEpochMs
}

@Dao
interface ArtistBulkProviderObservationDao {
    @Query("SELECT * FROM artist_bulk_provider_observation WHERE leaseId = :leaseId LIMIT 1")
    suspend fun existing(leaseId: String): ArtistBulkProviderObservationRecord?

    @Query("SELECT * FROM artist_bulk_provider_lease WHERE leaseId = :leaseId LIMIT 1")
    suspend fun lease(leaseId: String): ArtistBulkProviderLeaseRecord?

    @Query("SELECT * FROM artist_bulk_permission WHERE sessionId = :sessionId AND fingerprint = :fingerprint LIMIT 1")
    suspend fun permission(sessionId: String, fingerprint: String): ArtistBulkPermissionRecord?

    @Query("SELECT * FROM artist_bulk_session WHERE sessionId = :sessionId LIMIT 1")
    suspend fun session(sessionId: String): ArtistBulkSessionRecord?

    @Query("SELECT * FROM artist_bulk_receipt WHERE sessionId = :sessionId AND fingerprint = :fingerprint LIMIT 1")
    suspend fun sourceReceipt(sessionId: String, fingerprint: String): ArtistBulkReceiptRecord?

    @Query("SELECT COUNT(*) FROM artist_bulk_terminal_receipt WHERE sessionId = :sessionId AND fingerprint = :fingerprint")
    suspend fun terminalCount(sessionId: String, fingerprint: String): Int

    @Query("SELECT COUNT(*) FROM artist_bulk_permission WHERE status = 'RESERVED_NO_IO'")
    suspend fun activeReservations(): Int

    @Query("SELECT COUNT(*) FROM artist_bulk_provider_lease WHERE state = 'STAGED_NO_IO' AND expiresAtEpochMs > :now")
    suspend fun liveStagedLeases(now: Long): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(record: ArtistBulkProviderObservationRecord)

    /** An immutable untrusted note. This does NOT close a terminal receipt nor enable HTTP. */
    @Transaction
    suspend fun recordWithoutDispatch(
        preview: ArtistBulkProfileLookupPreview,
        expectedLease: ArtistBulkProviderLeaseRecord,
        scope: ArtistBulkTerminalScope,
        bounded: ArtistBulkProviderBoundedObservation,
        now: Long,
    ): ArtistBulkProviderObservationRecord? {
        if (now <= 0 || preview.mayDispatch || expectedLease.mayDispatch) return null
        val current = lease(expectedLease.leaseId) ?: return null
        if (current != expectedLease) return null
        val permit = permission(current.sessionId, current.fingerprint) ?: return null
        val session = session(current.sessionId) ?: return null
        val receipt = sourceReceipt(current.sessionId, current.fingerprint) ?: return null
        if (!ArtistBulkProviderObservationPolicy.mayRecord(current, preview, permit,
                session, receipt, scope, bounded, now, liveStagedLeases(now),
                activeReservations(), terminalCount(current.sessionId, current.fingerprint))
        ) return null
        val previous = existing(current.leaseId)
        if (previous != null) {
            return previous.takeIf { ArtistBulkProviderObservationPolicy.sameNote(it, bounded, current) }
        }
        val record = ArtistBulkProviderObservationRecord(
            leaseId = current.leaseId, sessionId = current.sessionId, fingerprint = current.fingerprint,
            permitId = current.permitId, provider = current.provider, mbid = current.mbid,
            observedAtEpochMs = bounded.observation.observedAtEpochMs,
            recordedAtEpochMs = now, httpStatusUnverified = bounded.observation.httpStatus,
            responseBytes = bounded.observation.responseByteCount,
            responseSha256Unverified = bounded.bodySha256,
            contentType = bounded.observation.contentType,
            classification = bounded.assessment.kind.name,
            retryAtEpochMs = bounded.assessment.retryAtEpochMs,
        )
        insert(record)
        return record
    }
}
