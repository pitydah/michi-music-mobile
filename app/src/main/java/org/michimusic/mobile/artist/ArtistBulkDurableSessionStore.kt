package org.michimusic.mobile.artist

import org.json.JSONArray
import org.michimusic.data.artist.ArtistBulkPermissionRecord
import org.michimusic.data.artist.ArtistBulkReceiptRecord
import org.michimusic.data.artist.ArtistBulkSessionRecord
import org.michimusic.data.artist.ArtistEnrichmentCacheDatabase
import java.util.UUID

/** D22: disk-backed non-executable receipts, scoped to the existing artist Room database. */
class ArtistBulkDurableSessionStore(private val database: ArtistEnrichmentCacheDatabase) {
    private val dao get() = database.artistBulkSessionDao()

    suspend fun persist(draft: ArtistBulkPreparedSession): ArtistBulkPreparedSession {
        require(!draft.cancelled && !draft.mayExecute && draft.snapshot.isValid())
        require(draft.sessionId.isNotBlank() && draft.sessionId.length <= 128 && draft.createdAtEpochMs > 0)
        require(draft.receipts.size in 1..64)
        require(draft.receipts.map { it.fingerprint }.toSet().size == draft.receipts.size)
        val session = ArtistBulkSessionRecord(
            sessionId = draft.sessionId, libraryId = draft.snapshot.libraryId,
            sourceId = draft.snapshot.sourceId, rootEpoch = draft.snapshot.rootEpoch,
            catalogRevision = draft.snapshot.catalogRevision, libraryRevision = draft.snapshot.libraryRevision,
            createdAtEpochMs = draft.createdAtEpochMs, updatedAtEpochMs = draft.createdAtEpochMs,
            receiptCount = draft.receipts.size,
        )
        val receipts = draft.receipts.map { receipt ->
            require(receipt.libraryId == session.libraryId &&
                receipt.status == ArtistBulkPlannedStatus.PREPARED_NOT_SUBMITTED &&
                receipt.albumIds.isNotEmpty() && receipt.albumIds.none(String::isBlank) &&
                receipt.albumIds.toSet().size == receipt.albumIds.size)
            ArtistBulkReceiptRecord(
                sessionId = session.sessionId, fingerprint = receipt.fingerprint,
                libraryId = receipt.libraryId, artistCredit = receipt.artistCredit,
                albumIdsJson = JSONArray(receipt.albumIds).toString(),
                mbid = receipt.mbid, associationId = receipt.associationId,
                updatedAtEpochMs = session.createdAtEpochMs,
            )
        }
        dao.createPrepared(session, receipts)
        // Reread authoritative rows: never advertise a purely optimistic in-memory receipt.
        return load(draft.sessionId) ?: error("The saved session could not be verified")
    }

    suspend fun load(sessionId: String): ArtistBulkPreparedSession? =
        dao.readConsistent(sessionId)?.let { (record, receipts) ->
            val snapshot = ArtistBulkReadSnapshot(
                record.libraryId, record.sourceId, record.rootEpoch,
                record.catalogRevision, record.libraryRevision,
            )
            if (!snapshot.isValid() || record.status !in setOf(
                    ArtistBulkSessionRecord.PREPARED_NOT_SUBMITTED,
                    ArtistBulkSessionRecord.CANCELLED_NOT_SUBMITTED,
                )) return@let null
            val parsed = receipts.map { row ->
                if (row.status != record.status) return@let null
                val array = try { JSONArray(row.albumIdsJson) } catch (_: Exception) { return@let null }
                val ids = (0 until array.length()).map { index ->
                    array.optString(index).takeIf(String::isNotBlank) ?: return@let null
                }
                if (ids.isEmpty() || ids.distinct().size != ids.size) return@let null
                ArtistBulkPlannedReceipt(
                    fingerprint = row.fingerprint, libraryId = row.libraryId,
                    artistCredit = row.artistCredit, albumIds = ids,
                    mbid = row.mbid, associationId = row.associationId,
                    status = if (record.status == ArtistBulkSessionRecord.CANCELLED_NOT_SUBMITTED)
                        ArtistBulkPlannedStatus.CANCELLED_NOT_SUBMITTED
                    else ArtistBulkPlannedStatus.PREPARED_NOT_SUBMITTED,
                )
            }
            ArtistBulkPreparedSession(record.sessionId, snapshot, record.createdAtEpochMs, parsed)
        }

    suspend fun latest(libraryId: String): ArtistBulkPreparedSession? {
        if (libraryId.isBlank() || libraryId == "SCOPE_ALL") return null
        val id = dao.latestForLibrary(libraryId)?.sessionId ?: return null
        return load(id)
    }

    /** D23: explicit per-artist consent, only for future profile lookup; never dispatches. */
    suspend fun grantProfileLookup(
        sessionId: String,
        fingerprint: String,
        current: ArtistBulkReadSnapshot,
        acknowledgedNetworkConsent: Boolean,
        now: Long = System.currentTimeMillis(),
    ): ArtistBulkPermissionRecord? {
        if (!acknowledgedNetworkConsent || !current.isValid() || now <= 0L) return null
        val session = dao.getSession(sessionId) ?: return null
        if (session.status != ArtistBulkSessionRecord.PREPARED_NOT_SUBMITTED ||
            current != ArtistBulkReadSnapshot(session.libraryId, session.sourceId,
                session.rootEpoch, session.catalogRevision, session.libraryRevision)
        ) return null
        // Read the exact, persisted receipt; never construct a permission from screen text.
        val receipt = dao.getReceipts(sessionId).singleOrNull { it.fingerprint == fingerprint }
            ?: return null
        val proposed = ArtistBulkPermissionRecord(
            sessionId = sessionId, fingerprint = fingerprint,
            permitId = UUID.randomUUID().toString(), libraryId = session.libraryId,
            sourceId = session.sourceId, rootEpoch = session.rootEpoch,
            catalogRevision = session.catalogRevision, libraryRevision = session.libraryRevision,
            artistCredit = receipt.artistCredit, mbid = receipt.mbid,
            associationId = receipt.associationId,
            issuedAtEpochMs = now, expiresAtEpochMs = now + ArtistBulkPermissionRecord.MAX_LIFETIME_MS,
            updatedAtEpochMs = now,
        )
        return if (dao.storePermission(proposed, session.revision, now)) {
            dao.getPermission(sessionId, fingerprint)?.takeIf { it == proposed }
        } else null
    }

    suspend fun permissions(sessionId: String): List<ArtistBulkPermissionRecord> {
        if (dao.readConsistent(sessionId) == null) return emptyList()
        return dao.listPermissions(sessionId)
    }

    suspend fun revokePermission(
        sessionId: String, fingerprint: String,
        now: Long = System.currentTimeMillis(),
    ): ArtistBulkPermissionRecord? {
        val old = dao.getPermission(sessionId, fingerprint) ?: return null
        if (!dao.revokePermission(sessionId, fingerprint, old.revision, now)) return null
        return dao.getPermission(sessionId, fingerprint)
    }

    /** D24: bounded, durable reservation receipt. Never starts a provider or Worker. */
    suspend fun reserveProfileLookupWithoutDispatch(
        sessionId: String,
        fingerprint: String,
        expectedPermitId: String,
        expectedPermissionRevision: Long,
        current: ArtistBulkReadSnapshot,
        now: Long = System.currentTimeMillis(),
    ): ArtistBulkPermissionRecord? {
        if (!current.isValid() || now <= 0L || expectedPermitId.isBlank()) return null
        val session = dao.getSession(sessionId) ?: return null
        if (session.status != ArtistBulkSessionRecord.PREPARED_NOT_SUBMITTED ||
            current != ArtistBulkReadSnapshot(
                session.libraryId, session.sourceId, session.rootEpoch,
                session.catalogRevision, session.libraryRevision,
            )) return null
        val expected = dao.getPermission(sessionId, fingerprint) ?: return null
        if (expected.permitId != expectedPermitId ||
            expected.revision != expectedPermissionRevision) return null
        return dao.reserveWithoutDispatch(expected, session.revision, now)
    }

    /** A release is terminal; it does not re-enable the old consent. */
    suspend fun releaseProfileLookupWithoutDispatch(
        expected: ArtistBulkPermissionRecord, now: Long = System.currentTimeMillis(),
    ): ArtistBulkPermissionRecord? = dao.releaseWithoutDispatch(expected, now)

    suspend fun cancel(sessionId: String, now: Long = System.currentTimeMillis()): ArtistBulkPreparedSession? {
        val existing = dao.getSession(sessionId) ?: return null
        if (!dao.cancelPrepared(sessionId, existing.revision, now)) return null
        return load(sessionId)
    }
}
