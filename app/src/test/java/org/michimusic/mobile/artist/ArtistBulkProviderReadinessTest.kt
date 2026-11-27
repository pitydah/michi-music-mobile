package org.michimusic.mobile.artist

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.michimusic.data.artist.ArtistBulkPermissionRecord
import org.michimusic.data.artist.ArtistBulkProviderConditions

/** D26 S4-G4 read-only bridge; no Room or provider calls in the fake. */
class ArtistBulkProviderReadinessTest {
    private val snapshot = ArtistBulkReadSnapshot("lib", "source", 1L, 2L, 3L)
    private val conditions = ArtistBulkProviderConditions(true,true,true,true)
    private fun permission() = ArtistBulkPermissionRecord(
        sessionId="s", fingerprint="a".repeat(64), permitId="permit",
        libraryId="lib", sourceId="source", rootEpoch=1L,
        catalogRevision=2L, libraryRevision=3L, artistCredit="Example Artist",
        mbid="b10bbbfc-cf9e-42e0-be17-e2c3e1d2600d", associationId="assoc",
        status=ArtistBulkPermissionRecord.RESERVED_NO_IO,
        issuedAtEpochMs=1000L, expiresAtEpochMs=301000L,
        updatedAtEpochMs=1500L, revision=1L,
    )
    @Test fun oneExistingReservationProducesOnlyAnInspection() = runBlocking {
        var reads=0
        val inspector = ArtistBulkProviderReadiness(
            readPermissions={ reads++; listOf(permission()) },
            snapshots=ArtistBulkSnapshotReader { snapshot }, clock={2000L},
        )
        val answer=inspector.inspect("s", conditions)
        assertEquals(1,reads)
        assertEquals(1,answer.size)
        assertFalse(answer.single().mayDispatch)
    }
    @Test fun changeMidReadFailsClosedWithoutReturningStalePermit() = runBlocking {
        var call = 0
        val inspector = ArtistBulkProviderReadiness(
            readPermissions={ listOf(permission()) },
            snapshots=ArtistBulkSnapshotReader { call++; if(call==1) snapshot else snapshot.copy(rootEpoch=9) },
            clock={2000L},
        )
        assertTrue(inspector.inspect("s",conditions).isEmpty())
    }
    @Test fun twoReservationsFailClosedRatherThanFakingGlobalBudget() = runBlocking {
        val inspector=ArtistBulkProviderReadiness(
            readPermissions={ listOf(permission(),permission().copy(fingerprint="b".repeat(64),permitId="other")) },
            snapshots=ArtistBulkSnapshotReader{snapshot}, clock={2000L},
        )
        assertTrue(inspector.inspect("s",conditions).isEmpty())
    }
    @Test fun noPermissionOrExpiredReceiptNeverStartsAnything() = runBlocking {
        val inspector=ArtistBulkProviderReadiness(
            readPermissions={ listOf(permission()) },
            snapshots=ArtistBulkSnapshotReader {snapshot},clock={301000L},
        )
        assertTrue(inspector.inspect("s",conditions).isEmpty())
        assertTrue(inspector.inspect("",conditions).isEmpty())
    }
}
