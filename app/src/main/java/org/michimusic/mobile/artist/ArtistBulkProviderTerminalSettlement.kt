package org.michimusic.mobile.artist

import androidx.room.withTransaction
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.michimusic.data.artist.ArtistBulkPermissionRecord
import org.michimusic.data.artist.ArtistBulkProviderLeaseRecord
import org.michimusic.data.artist.ArtistBulkProviderSettlementPolicy
import org.michimusic.data.artist.ArtistBulkTerminalOutcome
import org.michimusic.data.artist.ArtistBulkTerminalReceiptRecord
import org.michimusic.data.artist.ArtistBulkTerminalScope
import org.michimusic.data.artist.ArtistEnrichmentCacheDatabase

/**
 * D31: retire D27 lease, D24 permission and D25 terminal as ONE Room transaction.
 * This is a NO_IO recovery path, not a provider completion/dispatch mechanism.
 */
class ArtistBulkProviderTerminalSettlement(
    private val database: ArtistEnrichmentCacheDatabase,
    private val snapshots: ArtistBulkSnapshotReader,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val leaseDao get() = database.artistBulkProviderLeaseDao()
    private val terminalDao get() = database.artistBulkTerminalDao()

    private class Refused : RuntimeException()

    suspend fun cancelWithoutIo(expected: ArtistBulkPermissionRecord): ArtistBulkTerminalReceiptRecord? =
        closeWithoutIo(expected, ArtistBulkTerminalOutcome.CANCELLED)

    suspend fun reconcileStaleWithoutIo(sessionId: String): List<ArtistBulkTerminalReceiptRecord> {
        if (sessionId.isBlank()) return emptyList()
        currentCoroutineContext().ensureActive()
        val rows = terminalDao.listReservedNoIo(sessionId)
        if (rows.size > 64) return emptyList()
        val results = ArrayList<ArtistBulkTerminalReceiptRecord>(rows.size)
        for (expected in rows) {
            currentCoroutineContext().ensureActive()
            val current = snapshots.current().takeIf(ArtistBulkReadSnapshot::isValid) ?: return results
            val scope = scope(current)
            val now = clock()
            if (!scope.matches(expected) || now >= expected.expiresAtEpochMs) {
                closeWithoutIo(expected, ArtistBulkTerminalOutcome.STALE_SCOPE)?.let(results::add)
            }
        }
        return results
    }

    private fun scope(read: ArtistBulkReadSnapshot): ArtistBulkTerminalScope =
        ArtistBulkTerminalScope(read.libraryId, read.sourceId, read.rootEpoch,
            read.catalogRevision, read.libraryRevision)

    suspend fun closeWithoutIo(
        expected: ArtistBulkPermissionRecord,
        outcome: ArtistBulkTerminalOutcome,
    ): ArtistBulkTerminalReceiptRecord? {
        if (!outcome.mayBeObservedWithoutIo) return null
        currentCoroutineContext().ensureActive()
        val before = snapshots.current().takeIf(ArtistBulkReadSnapshot::isValid) ?: return null
        val observed = scope(before)
        val now = clock()
        if (now <= 0L) return null
        val result = try {
            // Outer transaction rolls back BOTH lease state and terminal on every refusal.
            database.withTransaction {
                val existing = leaseDao.leaseForPermit(expected.permitId)
                if (existing == null) {
                    // Legacy D24 reservations without a D27 lease retain D25 semantics.
                    terminalDao.closeWithoutIo(expected, observed, outcome, now) ?: throw Refused()
                } else {
                    val actual = leaseDao.permission(expected.sessionId, expected.fingerprint)
                        ?: throw Refused()
                    val terminal = terminalDao.findTerminal(expected.sessionId, expected.fingerprint)
                    if (terminal != null) {
                        if (!ArtistBulkProviderSettlementPolicy.isExactReplay(
                                existing, expected, actual, terminal, outcome)) throw Refused()
                        terminal
                    } else if (existing.state != ArtistBulkProviderLeaseRecord.STAGED_NO_IO) {
                        // D27 could have already retired the lease after root changed.
                        // Only the remaining permission+terminal are committed now.
                        if (!ArtistBulkProviderSettlementPolicy.mayFinalizeRetired(
                                existing, expected, actual, observed, outcome, now)) throw Refused()
                        terminalDao.closeWithoutIo(expected, observed, outcome, now) ?: throw Refused()
                    } else {
                        if (!ArtistBulkProviderSettlementPolicy.mayClose(
                                existing, expected, actual, observed, outcome, now)) throw Refused()
                        val changed = if (now >= existing.expiresAtEpochMs) {
                            leaseDao.expireOneCas(existing.leaseId, existing.permitId,
                                existing.revision, now) == 1
                        } else {
                            leaseDao.abandonWithoutIo(existing, now)
                        }
                        if (!changed) throw Refused()
                        // A null terminal means *rollback* of lease CAS as well.
                        terminalDao.closeWithoutIo(expected, observed, outcome, now) ?: throw Refused()
                    }
                }
            }
        } catch (_: Refused) { null }
        currentCoroutineContext().ensureActive()
        val after = snapshots.current()
        val finished = clock()
        // This is still a cancellation/staleness receipt, not a verified provider result.
        // If scope changed after commit, the durable row remains truthful but UI fails closed.
        return result.takeIf { after == before && finished >= now }
    }
}
