package org.michimusic.mobile.artist

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.michimusic.data.artist.ArtistBulkProfileLookupPreview
import org.michimusic.data.artist.ArtistBulkProviderBoundedObservation
import org.michimusic.data.artist.ArtistBulkProviderLeaseRecord
import org.michimusic.data.artist.ArtistBulkProviderObservationDao
import org.michimusic.data.artist.ArtistBulkProviderObservationRecord
import org.michimusic.data.artist.ArtistBulkTerminalScope

/** Explicit local review of caller-supplied, UNTRUSTED observations. No HTTP/WorkManager. */
class ArtistBulkProviderObservationReview(
    private val dao: ArtistBulkProviderObservationDao,
    private val snapshots: ArtistBulkSnapshotReader,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    suspend fun recordForLocalReview(
        preview: ArtistBulkProfileLookupPreview,
        lease: ArtistBulkProviderLeaseRecord,
        bounded: ArtistBulkProviderBoundedObservation,
    ): ArtistBulkProviderObservationRecord? {
        currentCoroutineContext().ensureActive()
        val before = snapshots.current().takeIf(ArtistBulkReadSnapshot::isValid) ?: return null
        val scope = ArtistBulkTerminalScope(before.libraryId, before.sourceId, before.rootEpoch,
            before.catalogRevision, before.libraryRevision)
        if (preview.expectedScope != scope) return null
        val startedAt = clock()
        val record = dao.recordWithoutDispatch(preview, lease, scope, bounded, startedAt) ?: return null
        currentCoroutineContext().ensureActive()
        val after = snapshots.current()
        val finishedAt = clock()
        if (after != before || finishedAt < startedAt || finishedAt >= lease.expiresAtEpochMs ||
            finishedAt >= preview.expiresAtEpochMs
        ) return null
        return record // Still an untrusted review only. Cannot dispatch or persist artist profile.
    }
}
