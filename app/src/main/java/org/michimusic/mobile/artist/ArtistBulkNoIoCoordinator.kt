package org.michimusic.mobile.artist

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.michimusic.data.artist.ArtistBulkPermissionRecord

/**
 * D24. Bounded, deterministic handoff PREPARATION. There is deliberately no
 * WorkManager scheduling, HTTP adapter, metadata write, or provider callback.
 * A RESERVED_NO_IO record is never a capability to contact an external service.
 *
 * Global concurrency is enforced in the Room transaction, not this coroutine.
 * Cancellation or a changed snapshot releases any newly acquired reservation.
 */
class ArtistBulkNoIoCoordinator(
    private val store: ArtistBulkDurableSessionStore,
    private val snapshots: ArtistBulkSnapshotReader,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    suspend fun reserveOneForReview(sessionId: String): ArtistBulkPermissionRecord? {
        if (!org.michimusic.data.artist.ArtistBulkNoIoOperationGate.permits(
                org.michimusic.data.artist.ArtistBulkNoIoOperation.RESERVE_NO_IO)) return null
        if (sessionId.isBlank()) return null
        val expected = snapshots.current().takeIf(ArtistBulkReadSnapshot::isValid) ?: return null
        val storedPermissions = store.permissions(sessionId)
        if (storedPermissions.size !in 1..64) return null
        val permissions = storedPermissions.asSequence()
            .filter { it.status == ArtistBulkPermissionRecord.CONSENT_STORED_NOT_DISPATCHED }
            .sortedWith(compareBy<ArtistBulkPermissionRecord> { it.artistCredit }
                .thenBy { it.fingerprint })
            .take(64)
            .toList()
        var acquired: ArtistBulkPermissionRecord? = null
        try {
            for (candidate in permissions) {
                val now = clock()
                if (now <= 0L || !candidate.isCurrent(now)) continue
                if (snapshots.current() != expected) return null
                acquired = store.reserveProfileLookupWithoutDispatch(
                    sessionId = candidate.sessionId,
                    fingerprint = candidate.fingerprint,
                    expectedPermitId = candidate.permitId,
                    expectedPermissionRevision = candidate.revision,
                    current = expected,
                    now = now,
                )
                if (acquired != null) {
                    if (snapshots.current() != expected) {
                        releaseSafely(acquired)
                        acquired = null
                        return null
                    }
                    // The caller only receives a durable NO_IO receipt, not a job ticket.
                    val result = acquired
                    acquired = null
                    return result
                }
            }
            return null
        } catch (cancelled: CancellationException) {
            if (acquired != null) releaseSafely(acquired)
            throw cancelled
        } catch (_: Exception) {
            if (acquired != null) releaseSafely(acquired)
            return null // fail closed: never fabricate a reserve receipt
        }
    }

    suspend fun releaseWithoutDispatch(expected: ArtistBulkPermissionRecord): Boolean =
        store.releaseProfileLookupWithoutDispatch(expected, now = clock()) != null

    private suspend fun releaseSafely(held: ArtistBulkPermissionRecord?) {
        if (held == null) return
        // Releasing a local no-I/O reservation must survive coroutine cancellation.
        try {
            withContext(NonCancellable) { store.releaseProfileLookupWithoutDispatch(held, now = clock()) }
        } catch (_: Exception) {
            // Durable stale reservation expires in the DAO; no network action exists.
        }
    }
}
