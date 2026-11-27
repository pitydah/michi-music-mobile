package org.michimusic.mobile.artist

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.michimusic.data.artist.ArtistBulkProfileLookupPreview
import org.michimusic.data.artist.ArtistBulkProviderConditions
import org.michimusic.data.artist.ArtistBulkProviderLeaseDao
import org.michimusic.data.artist.ArtistBulkProviderLeaseRecord
import org.michimusic.data.artist.ArtistBulkTerminalScope

/**
 * D27 explicit preview-only operation. Does not run from composition or schedule work.
 * A Room stage can only write a NO_IO receipt. No HTTP client is reachable here.
 * Root cannot be part of the SQLite transaction; compare before/after and fail closed.
 */
class ArtistBulkProviderLeaseReview(
    private val dao: ArtistBulkProviderLeaseDao,
    private val snapshots: ArtistBulkSnapshotReader,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    suspend fun stageOneForReview(
        preview: ArtistBulkProfileLookupPreview,
        conditions: ArtistBulkProviderConditions,
    ): ArtistBulkProviderLeaseRecord? {
        currentCoroutineContext().ensureActive()
        val first = snapshots.current().takeIf(ArtistBulkReadSnapshot::isValid) ?: return null
        val scope = ArtistBulkTerminalScope(first.libraryId, first.sourceId, first.rootEpoch,
            first.catalogRevision, first.libraryRevision)
        if (scope != preview.expectedScope) return null
        val startedAt = clock()
        val staged = dao.stageWithoutIo(preview, scope, conditions, startedAt) ?: return null
        currentCoroutineContext().ensureActive()
        val end = snapshots.current()
        val endAt = clock()
        if (end != first || endAt < startedAt || endAt >= staged.expiresAtEpochMs) {
            // Never represent a stage as currently valid if external state changed.
            // A failed/expired stage remains consumed; no renewal is permitted.
            dao.abandonWithoutIo(staged, endAt)
            return null
        }
        return staged
    }
}
