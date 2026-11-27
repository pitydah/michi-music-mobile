package org.michimusic.mobile.artist

import org.michimusic.data.artist.ArtistBulkPermissionRecord
import org.michimusic.data.artist.ArtistBulkTerminalReceiptRecord
import org.michimusic.data.artist.ArtistEnrichmentCacheDatabase

/**
 * D25 explicit read/reconcile facade. Not a Worker, queue or HTTP client.
 * A previously reserved D24 consent has already been consumed; the facade
 * does not revive it and never writes a profile or music file.
 */
class ArtistBulkTerminalRecovery(
    private val database: ArtistEnrichmentCacheDatabase,
    private val snapshots: ArtistBulkSnapshotReader,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val dao get() = database.artistBulkTerminalDao()
    private val settlement by lazy { ArtistBulkProviderTerminalSettlement(database, snapshots, clock) }

    suspend fun reviewTerminalReceipts(sessionId: String): List<ArtistBulkTerminalReceiptRecord> {
        if (sessionId.isBlank()) return emptyList()
        return dao.listTerminals(sessionId).filter { org.michimusic.data.artist.ArtistBulkTerminalPolicy.authentic(it) }
    }

    /** When a view is opened, retire only expired/stale NO_IO reservations. */
    suspend fun reconcileOnExplicitReview(sessionId: String): List<ArtistBulkTerminalReceiptRecord> {
        if (sessionId.isBlank()) return emptyList()
        return settlement.reconcileStaleWithoutIo(sessionId)
    }

    /** Explicitly abandon one still-reserved, no-I/O receipt. */
    suspend fun cancelReservedWithoutIo(
        expected: ArtistBulkPermissionRecord,
    ): ArtistBulkTerminalReceiptRecord? {
        return settlement.cancelWithoutIo(expected)
    }
}
