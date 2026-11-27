package org.michimusic.mobile.artist

import org.michimusic.data.artist.ArtistBulkProfileLookupPreview
import org.michimusic.data.artist.ArtistBulkPermissionRecord
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.michimusic.data.artist.ArtistBulkProviderConditions
import org.michimusic.data.artist.ArtistBulkProviderPreflight
import org.michimusic.data.artist.ArtistBulkTerminalScope

/**
 * D26 read-only bridge from the D23-D24 durable journal to the D26 policy.
 * It does not reserve, release, contact providers, schedule WorkManager or
 * produce an executable request. It must never be called from composition
 * to trigger hidden network traffic.
 */
class ArtistBulkProviderReadiness(
    private val readPermissions: suspend (String) -> List<ArtistBulkPermissionRecord>,
    private val snapshots: ArtistBulkSnapshotReader,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    constructor(store: ArtistBulkDurableSessionStore, snapshots: ArtistBulkSnapshotReader,
        clock: () -> Long = System::currentTimeMillis) : this(store::permissions, snapshots, clock)

    suspend fun inspect(
        sessionId: String,
        conditions: ArtistBulkProviderConditions,
    ): List<ArtistBulkProfileLookupPreview> {
        if (!org.michimusic.data.artist.ArtistBulkNoIoOperationGate.permits(
                org.michimusic.data.artist.ArtistBulkNoIoOperation.INSPECT_LOCAL)) return emptyList()
        if (sessionId.isBlank() || sessionId.length > 128) return emptyList()
        currentCoroutineContext().ensureActive()
        val first = snapshots.current().takeIf(ArtistBulkReadSnapshot::isValid) ?: return emptyList()
        val permissions = readPermissions(sessionId)
        if (permissions.size !in 1..64) return emptyList()
        currentCoroutineContext().ensureActive()
        if (snapshots.current() != first) return emptyList()
        val current = ArtistBulkTerminalScope(
            first.libraryId, first.sourceId, first.rootEpoch,
            first.catalogRevision, first.libraryRevision,
        )
        val at = clock()
        val previews = permissions.mapNotNull { permit ->
            if (permit.sessionId != sessionId) null
            else ArtistBulkProviderPreflight.inspect(permit, current, conditions, at)
        }
        // No successful preview is returned if the root changed while inspecting.
        currentCoroutineContext().ensureActive()
        val checkedAt = clock()
        if (checkedAt < at || snapshots.current() != first ||
            checkedAt >= (previews.minOfOrNull { it.expiresAtEpochMs } ?: Long.MAX_VALUE)) {
            return emptyList()
        }
        return previews.takeIf { it.size <= 1 }.orEmpty() // Room D24 permits one global active reservation.
    }
}
