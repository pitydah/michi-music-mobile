package org.michimusic.mobile.artist

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.michimusic.data.artist.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** D31 executable Robolectric specifications; must run in OpenCode, NOT Drive. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ArtistBulkProviderTerminalSettlementRoomTest {
    private lateinit var db: ArtistEnrichmentCacheDatabase
    private val t = 1_800_000_000_000L
    private val fp = "a".repeat(64)
    private val mbid = "05072650-4b05-4e8b-83d0-dba2576e306a"
    private var currentScope = ArtistBulkReadSnapshot("lib", "saf-A", 3, 5, 7)
    private val snapshots = ArtistBulkSnapshotReader { currentScope }
    private fun permission() = ArtistBulkPermissionRecord(
        "s", fp, "permit-1", "lib", "saf-A", 3, 5, 7, "Artist", mbid, "assoc",
        status = ArtistBulkPermissionRecord.RESERVED_NO_IO,
        issuedAtEpochMs = t - 1000, expiresAtEpochMs = t + 100_000,
        updatedAtEpochMs = t - 100, revision = 1,
    )
    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(),
            ArtistEnrichmentCacheDatabase::class.java).build()
        currentScope = ArtistBulkReadSnapshot("lib", "saf-A", 3, 5, 7)
    }
    @After fun cleanup() { db.close() }
    private suspend fun seed(): Pair<ArtistBulkPermissionRecord, ArtistBulkProviderLeaseRecord> {
        db.artistBulkSessionDao().createPrepared(
            ArtistBulkSessionRecord("s", "lib", "saf-A", 3, 5, 7, t - 1000, t - 1000,
                receiptCount = 1),
            listOf(ArtistBulkReceiptRecord("s", fp, "lib", "Artist", "[\"album\"]",
                mbid, "assoc", updatedAtEpochMs = t - 1000)),
        )
        val permit = permission()
        db.artistBulkSessionDao().insertPermission(permit)
        val scope = ArtistBulkTerminalScope("lib", "saf-A", 3, 5, 7)
        val preview = requireNotNull(ArtistBulkProviderPreflight.inspect(permit, scope,
            ArtistBulkProviderConditions(true, true, true, true), t))
        val lease = requireNotNull(db.artistBulkProviderLeaseDao().stageWithoutIo(preview, scope,
            ArtistBulkProviderConditions(true, true, true, true), t))
        return permit to lease
    }
    @Test fun cancelAtomicallyClosesBothAndCanReplay() = runBlocking {
        val (permit, lease) = seed()
        val settlement = ArtistBulkProviderTerminalSettlement(db, snapshots) { t + 1 }
        val receipt = requireNotNull(settlement.cancelWithoutIo(permit))
        assertTrue(ArtistBulkTerminalPolicy.authentic(receipt))
        assertEquals(ArtistBulkProviderLeaseRecord.ABANDONED_NO_IO,
            db.artistBulkProviderLeaseDao().leaseForPermit(lease.permitId)?.state)
        assertEquals(ArtistBulkPermissionRecord.RELEASED_NO_IO,
            db.artistBulkTerminalDao().findPermission(permit.sessionId, permit.fingerprint)?.status)
        assertEquals(receipt, settlement.cancelWithoutIo(permit))
        assertEquals(1, db.artistBulkTerminalDao().listTerminals("s").size)
    }
    @Test fun invalidatedRootClosesAsStaleNoIo() = runBlocking {
        val (permit, lease) = seed()
        currentScope = currentScope.copy(rootEpoch = 4)
        val settlement = ArtistBulkProviderTerminalSettlement(db, snapshots) { t + 2 }
        val records = settlement.reconcileStaleWithoutIo("s")
        assertEquals(1, records.size)
        assertEquals(ArtistBulkTerminalOutcome.STALE_SCOPE.name, records.single().outcome)
        assertEquals(ArtistBulkProviderLeaseRecord.ABANDONED_NO_IO,
            db.artistBulkProviderLeaseDao().leaseForPermit(lease.permitId)?.state)
        assertEquals(ArtistBulkPermissionRecord.RELEASED_NO_IO,
            db.artistBulkTerminalDao().findPermission(permit.sessionId, permit.fingerprint)?.status)
    }
    @Test fun expiredLeaseAndExpiredConsentCloseTogether() = runBlocking {
        val (permit, lease) = seed()
        val settlement = ArtistBulkProviderTerminalSettlement(db, snapshots) { t + 100_001 }
        val rows = settlement.reconcileStaleWithoutIo("s")
        assertEquals(1, rows.size)
        assertEquals(ArtistBulkProviderLeaseRecord.EXPIRED_NO_IO,
            db.artistBulkProviderLeaseDao().leaseForPermit(lease.permitId)?.state)
        assertEquals(ArtistBulkPermissionRecord.RELEASED_NO_IO,
            db.artistBulkTerminalDao().findPermission(permit.sessionId, permit.fingerprint)?.status)
    }
    @Test fun alreadyAbandonedLeaseStillAllowsTerminalRecovery() = runBlocking {
        val (permit, lease) = seed()
        assertTrue(db.artistBulkProviderLeaseDao().abandonWithoutIo(lease, t + 1))
        val settlement = ArtistBulkProviderTerminalSettlement(db, snapshots) { t + 2 }
        val terminal = requireNotNull(settlement.cancelWithoutIo(permit))
        assertEquals(terminal, settlement.cancelWithoutIo(permit))
        assertEquals(ArtistBulkProviderLeaseRecord.ABANDONED_NO_IO,
            db.artistBulkProviderLeaseDao().leaseForPermit(lease.permitId)?.state)
        assertEquals(ArtistBulkPermissionRecord.RELEASED_NO_IO,
            db.artistBulkTerminalDao().findPermission(permit.sessionId, permit.fingerprint)?.status)
    }
    @Test fun freshReservationDoesNotFabricateStaleResult() = runBlocking {
        val (permit, lease) = seed()
        val settlement = ArtistBulkProviderTerminalSettlement(db, snapshots) { t + 1 }
        assertTrue(settlement.reconcileStaleWithoutIo("s").isEmpty())
        assertNull(settlement.closeWithoutIo(permit, ArtistBulkTerminalOutcome.VERIFIED_PROFILE))
        assertEquals(ArtistBulkProviderLeaseRecord.STAGED_NO_IO,
            db.artistBulkProviderLeaseDao().leaseForPermit(lease.permitId)?.state)
    }
    @Test fun databaseInsertFailureMustRollBackLeaseAndPermission() = runBlocking {
        val (permit, lease) = seed()
        db.openHelper.writableDatabase.execSQL("""
            CREATE TRIGGER deny_terminal BEFORE INSERT ON artist_bulk_terminal_receipt
            BEGIN SELECT RAISE(ABORT, 'injected terminal refusal'); END
        """.trimIndent())
        val settlement = ArtistBulkProviderTerminalSettlement(db, snapshots) { t + 1 }
        try { settlement.cancelWithoutIo(permit); fail("expected injected SQLite failure") }
        catch (_: android.database.SQLException) { /* a real DB failure, not a policy denial */ }
        assertEquals(ArtistBulkProviderLeaseRecord.STAGED_NO_IO,
            db.artistBulkProviderLeaseDao().leaseForPermit(lease.permitId)?.state)
        assertEquals(ArtistBulkPermissionRecord.RESERVED_NO_IO,
            db.artistBulkTerminalDao().findPermission(permit.sessionId, permit.fingerprint)?.status)
        assertTrue(db.artistBulkTerminalDao().listTerminals("s").isEmpty())
    }
}
