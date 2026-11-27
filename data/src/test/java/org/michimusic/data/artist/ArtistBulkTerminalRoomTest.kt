package org.michimusic.data.artist

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** D25 Room/Robolectric cases for OpenCode: not claimed to run in Drive. */
@RunWith(RobolectricTestRunner::class)
class ArtistBulkTerminalRoomTest {
    private lateinit var db: ArtistEnrichmentCacheDatabase
    private val key = "a".repeat(64)
    private fun scope(epoch: Long = 3L) = ArtistBulkTerminalScope("library", "saf-A", epoch, 5, 7)
    private fun session() = ArtistBulkSessionRecord("session", "library", "saf-A", 3, 5, 7,
        1000, 1000, receiptCount = 1)
    private fun receipt() = ArtistBulkReceiptRecord("session", key, "library", "Artist",
        "[\"album\"]", "mbid", "association", updatedAtEpochMs = 1000)
    private fun permission() = ArtistBulkPermissionRecord(
        sessionId="session", fingerprint=key, permitId="permit-one", libraryId="library",
        sourceId="saf-A", rootEpoch=3, catalogRevision=5, libraryRevision=7,
        artistCredit="Artist", mbid="mbid", associationId="association",
        status=ArtistBulkPermissionRecord.RESERVED_NO_IO,
        issuedAtEpochMs=1100, expiresAtEpochMs=301100,
        updatedAtEpochMs=1200, revision=1)

    @Before fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, ArtistEnrichmentCacheDatabase::class.java).build()
    }
    @After fun teardown() { db.close() }
    private suspend fun seed(): ArtistBulkPermissionRecord {
        val d=db.artistBulkSessionDao()
        d.createPrepared(session(),listOf(receipt()))
        val p=permission()
        d.insertPermission(p)
        return p
    }

    @Test fun staleScopeRetiresExactlyOnceAndReplaysSameReceipt() = runBlocking {
        val p=seed(); val dao=db.artistBulkTerminalDao()
        val first=requireNotNull(dao.closeWithoutIo(p,scope(4),ArtistBulkTerminalOutcome.STALE_SCOPE,1300))
        assertTrue(ArtistBulkTerminalPolicy.authentic(first))
        assertEquals(first,dao.closeWithoutIo(p,scope(4),ArtistBulkTerminalOutcome.STALE_SCOPE,1300))
        assertEquals(1,dao.listTerminals("session").size)
        assertEquals(ArtistBulkPermissionRecord.RELEASED_NO_IO,
            dao.findPermission("session", key)?.status)
        assertEquals(2L,dao.findPermission("session",key)?.revision)
    }
    @Test fun freshReservationAndProviderResultsNeverProduceReceipts() = runBlocking {
        val p=seed(); val dao=db.artistBulkTerminalDao()
        assertNull(dao.closeWithoutIo(p,scope(),ArtistBulkTerminalOutcome.STALE_SCOPE,1300))
        assertNull(dao.closeWithoutIo(p,scope(),ArtistBulkTerminalOutcome.VERIFIED_PROFILE,1300))
        assertNull(dao.closeWithoutIo(p,scope(),ArtistBulkTerminalOutcome.NO_MATCH,1300))
        assertNull(dao.closeWithoutIo(p,scope(),ArtistBulkTerminalOutcome.NETWORK_FAILURE,1300))
        assertEquals(0,dao.listTerminals("session").size)
        assertEquals(ArtistBulkPermissionRecord.RESERVED_NO_IO,
            dao.findPermission("session",key)?.status)
    }
    @Test fun explicitCancellationConsumesReservationWithoutNetwork() = runBlocking {
        val p=seed();val dao=db.artistBulkTerminalDao()
        val row=requireNotNull(dao.closeWithoutIo(p,scope(),ArtistBulkTerminalOutcome.CANCELLED,1300))
        assertEquals(ArtistBulkTerminalOutcome.CANCELLED.name,row.outcome)
        assertNull(dao.closeWithoutIo(p,scope(),ArtistBulkTerminalOutcome.STALE_SCOPE,301200))
        assertEquals(1,dao.listTerminals("session").size)
    }
    @Test fun reconciliationSkipsFreshAndClosesExpiredWithoutResurrection() = runBlocking {
        seed();val dao=db.artistBulkTerminalDao()
        assertTrue(dao.reconcileStaleWithoutIo("session",scope(),1300).isEmpty())
        assertEquals(1,dao.reconcileStaleWithoutIo("session",scope(),301100).size)
        assertTrue(dao.reconcileStaleWithoutIo("session",scope(),301200).isEmpty())
        assertEquals(1,dao.listTerminals("session").size)
    }
    @Test fun revokedOrCancelledPermissionNeverYieldsProviderProof() = runBlocking {
        val p=seed();val sessions=db.artistBulkSessionDao();val dao=db.artistBulkTerminalDao()
        assertTrue(sessions.cancelPrepared("session",0,1300))
        assertEquals(ArtistBulkPermissionRecord.REVOKED_NOT_DISPATCHED,
            dao.findPermission("session",key)?.status)
        assertNull(dao.closeWithoutIo(p,scope(4),ArtistBulkTerminalOutcome.STALE_SCOPE,1300))
        assertTrue(dao.reconcileStaleWithoutIo("session",scope(4),1300).isEmpty())
    }
}
