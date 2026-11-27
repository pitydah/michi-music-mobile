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

/** D24 real Room assertions; to execute only inside OpenCode/Android Gradle. */
@RunWith(RobolectricTestRunner::class)
class ArtistBulkReservationRoomTest {
    private lateinit var db: ArtistEnrichmentCacheDatabase
    private val t = 1_800_000_000_000L
    private val a = "a".repeat(64)
    private val b = "b".repeat(64)
    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(), ArtistEnrichmentCacheDatabase::class.java,
        ).build()
    }
    @After fun tearDown() { db.close() }
    private fun session(id:String) = ArtistBulkSessionRecord(
        sessionId=id,libraryId="lib",sourceId="source",rootEpoch=3,
        catalogRevision=5,libraryRevision=7,createdAtEpochMs=t,updatedAtEpochMs=t,receiptCount=2)
    private fun receipts(id:String) = listOf(a,b).mapIndexed { index,fp -> ArtistBulkReceiptRecord(
        sessionId=id,fingerprint=fp,libraryId="lib",artistCredit="Artist-$index",
        albumIdsJson="[\"album-$index\"]",mbid="mbid-$index",
        associationId="association-$index",updatedAtEpochMs=t)
    }
    private fun permit(id:String,fp:String=a):ArtistBulkPermissionRecord {
        val index=if(fp==a) 0 else 1
        return ArtistBulkPermissionRecord(
            sessionId=id,fingerprint=fp,permitId="permit-$id-$index",libraryId="lib",
            sourceId="source",rootEpoch=3,catalogRevision=5,libraryRevision=7,
            artistCredit="Artist-$index",mbid="mbid-$index",associationId="association-$index",
            issuedAtEpochMs=t+100,expiresAtEpochMs=t+300_100,updatedAtEpochMs=t+100)
    }
    private suspend fun seed(id:String){
        db.artistBulkSessionDao().createPrepared(session(id),receipts(id))
        assertTrue(db.artistBulkSessionDao().storePermission(permit(id,a),0,t+100))
        assertTrue(db.artistBulkSessionDao().storePermission(permit(id,b),0,t+100))
    }
    @Test fun oneReservationAndTerminalRelease() = runBlocking {
        val dao=db.artistBulkSessionDao();seed("s")
        val x=dao.reserveWithoutDispatch(permit("s"),0,t+101)
        assertNotNull(x);assertEquals(ArtistBulkPermissionRecord.RESERVED_NO_IO,x?.status)
        assertEquals(1,dao.activeNoIoReservations())
        assertNull(dao.reserveWithoutDispatch(permit("s",b),0,t+102))
        val end=dao.releaseWithoutDispatch(x!!,t+103)
        assertEquals(ArtistBulkPermissionRecord.RELEASED_NO_IO,end?.status)
        assertNull(dao.reserveWithoutDispatch(permit("s"),0,t+104))
        assertNotNull(dao.reserveWithoutDispatch(permit("s",b),0,t+104))
    }
    @Test fun duplicateReservationAndStaleRevisionRejected() = runBlocking {
        val dao=db.artistBulkSessionDao();seed("s")
        val claim=dao.reserveWithoutDispatch(permit("s"),0,t+101)!!
        assertNull(dao.reserveWithoutDispatch(permit("s"),0,t+102))
        assertNull(dao.releaseWithoutDispatch(permit("s"),t+103))
        assertNotNull(dao.releaseWithoutDispatch(claim,t+104))
    }
    @Test fun globalBudgetAcrossSessions() = runBlocking {
        val dao=db.artistBulkSessionDao();seed("s1");seed("s2")
        assertNotNull(dao.reserveWithoutDispatch(permit("s1"),0,t+101))
        assertNull(dao.reserveWithoutDispatch(permit("s2"),0,t+102))
    }
    @Test fun cancellingSessionRevokesReservationAndUnreservedConsent() = runBlocking {
        val dao=db.artistBulkSessionDao();seed("s")
        assertNotNull(dao.reserveWithoutDispatch(permit("s"),0,t+101))
        assertTrue(dao.cancelPrepared("s",0,t+102))
        assertEquals(0,dao.activeNoIoReservations())
        assertTrue(dao.listPermissions("s").all { it.status==ArtistBulkPermissionRecord.REVOKED_NOT_DISPATCHED })
        assertNull(dao.reserveWithoutDispatch(permit("s",b),1,t+103))
    }
    @Test fun revokeReservedPermission() = runBlocking {
        val dao=db.artistBulkSessionDao();seed("s")
        val claim=dao.reserveWithoutDispatch(permit("s"),0,t+101)!!
        assertTrue(dao.revokePermission("s",a,claim.revision,t+102))
        assertEquals(0,dao.activeNoIoReservations())
        assertNull(dao.releaseWithoutDispatch(claim,t+103))
    }
    @Test fun expiredClaimReleasesButDoesNotBecomeEligible() = runBlocking {
        val dao=db.artistBulkSessionDao();seed("s")
        assertNotNull(dao.reserveWithoutDispatch(permit("s"),0,t+101))
        assertEquals(1,dao.releaseExpiredNoIoReservations(t+300_100))
        assertEquals(0,dao.activeNoIoReservations())
        assertEquals(ArtistBulkPermissionRecord.RELEASED_NO_IO,dao.getPermission("s",a)?.status)
        assertNull(dao.reserveWithoutDispatch(permit("s"),0,t+300_101))
    }
    @Test fun wrongPermitAndRootFailClosed() = runBlocking {
        val dao=db.artistBulkSessionDao();seed("s")
        assertNull(dao.reserveWithoutDispatch(permit("s").copy(permitId="unknown"),0,t+101))
        assertNull(dao.reserveWithoutDispatch(permit("s").copy(rootEpoch=4),0,t+101))
        assertEquals(0,dao.activeNoIoReservations())
    }
    @Test fun reservationRereadsFromRoom() = runBlocking {
        val dao=db.artistBulkSessionDao();seed("s")
        assertNotNull(dao.reserveWithoutDispatch(permit("s"),0,t+101))
        assertEquals(ArtistBulkPermissionRecord.RESERVED_NO_IO,dao.getPermission("s",a)?.status)
        assertEquals(1,dao.activeNoIoReservations())
    }
    @Test fun noIoReservationNeverChangesArtistReceipt() = runBlocking {
        val dao=db.artistBulkSessionDao();seed("s")
        val before=dao.getReceipts("s")
        assertNotNull(dao.reserveWithoutDispatch(permit("s"),0,t+101))
        assertEquals(before,dao.getReceipts("s"))
        assertEquals(ArtistBulkSessionRecord.PREPARED_NOT_SUBMITTED,dao.getSession("s")?.status)
    }
}
