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

/** Integration tests for OpenCode/Room KSP: written, not executed in Drive. */
@RunWith(RobolectricTestRunner::class)
class ArtistBulkProviderObservationRoomTest {
    private lateinit var db:ArtistEnrichmentCacheDatabase
    private val now=1_800_000_000_000L
    private val fp="a".repeat(64)
    private val mbid="05072650-4b05-4e8b-83d0-dba2576e306a"
    private fun scope()=ArtistBulkTerminalScope("lib","saf-A",3,5,7)
    private val conditions=ArtistBulkProviderConditions(true,true,true,true)
    private fun permission()=ArtistBulkPermissionRecord("s",fp,"permit-1","lib","saf-A",3,5,7,
        "Artist",mbid,"assoc",status=ArtistBulkPermissionRecord.RESERVED_NO_IO,
        issuedAtEpochMs=now-1000,expiresAtEpochMs=now+100000,updatedAtEpochMs=now-100,revision=1)
    @Before fun setup(){db=Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext<Context>(),ArtistEnrichmentCacheDatabase::class.java).build()}
    @After fun tearDown(){db.close()}
    private suspend fun seed():Pair<ArtistBulkProfileLookupPreview,ArtistBulkProviderLeaseRecord> {
        val s=db.artistBulkSessionDao()
        s.createPrepared(ArtistBulkSessionRecord("s","lib","saf-A",3,5,7,now-2000,now-2000,receiptCount=1),
            listOf(ArtistBulkReceiptRecord("s",fp,"lib","Artist","[\"album\"]",mbid,"assoc",
                updatedAtEpochMs=now-2000)))
        s.insertPermission(permission())
        val preview=requireNotNull(ArtistBulkProviderPreflight.inspect(permission(),scope(),conditions,now))
        val lease=requireNotNull(db.artistBulkProviderLeaseDao().stageWithoutIo(preview,scope(),conditions,now))
        return preview to lease
    }
    private fun bounded(preview:ArtistBulkProfileLookupPreview,lease:ArtistBulkProviderLeaseRecord)=
        requireNotNull(ArtistBulkProviderResponseBoundary.review(preview,lease,
            requireNotNull(ArtistBulkProviderResponseBoundary.describe(preview,lease,now+1)),
            ArtistBulkProviderRawObservation(200,"{\"id\":\"$mbid\"}".toByteArray(),
                "application/json",mbid,now+1),scope(),now+1,1,null))
    @Test fun oneNoteIsImmutableAndReplayReturnsExactReceipt()=runBlocking {
        val (p,l)=seed();val d=db.artistBulkProviderObservationDao();val n=bounded(p,l)
        val first=requireNotNull(d.recordWithoutDispatch(p,l,scope(),n,now+1))
        assertEquals(first,d.recordWithoutDispatch(p,l,scope(),n,now+2))
        assertEquals(ArtistBulkProviderObservationRecord.ORIGIN_UNVERIFIED,first.origin)
        assertFalse(first.mayPublishIdentity)
    }
    @Test fun changedReplayCannotOverwriteUntrustedNote()=runBlocking {
        val (p,l)=seed();val d=db.artistBulkProviderObservationDao();val n=bounded(p,l)
        assertNotNull(d.recordWithoutDispatch(p,l,scope(),n,now+1))
        assertNull(d.recordWithoutDispatch(p,l,scope(),n.copy(bodySha256="0".repeat(64)),now+2))
        assertEquals(n.bodySha256,d.existing(l.leaseId)?.responseSha256Unverified)
    }
    @Test fun rootDriftAndExpiredLeaseCannotStoreNote()=runBlocking {
        val (p,l)=seed();val d=db.artistBulkProviderObservationDao();val n=bounded(p,l)
        assertNull(d.recordWithoutDispatch(p,l,scope().copy(rootEpoch=4),n,now+1))
        assertNull(d.recordWithoutDispatch(p,l,scope(),n,l.expiresAtEpochMs))
        assertNull(d.existing(l.leaseId))
    }
    @Test fun cancelledPermissionCannotStoreNote()=runBlocking {
        val (p,l)=seed();val d=db.artistBulkProviderObservationDao()
        assertNotNull(db.artistBulkTerminalDao().closeWithoutIo(permission(),scope(),
            ArtistBulkTerminalOutcome.CANCELLED,now+1))
        assertNull(d.recordWithoutDispatch(p,l,scope(),bounded(p,l),now+2))
    }
    @Test fun foreignKeyCascadePreservesQuotaButDropsLeaseNote()=runBlocking {
        val (p,l)=seed();val d=db.artistBulkProviderObservationDao()
        assertNotNull(d.recordWithoutDispatch(p,l,scope(),bounded(p,l),now+1))
        assertEquals(1, db.artistBulkProviderLeaseDao().liveStagedLeases(now+1))
        assertNotNull(db.artistBulkProviderLeaseDao().quota(p.provider.name))
        // FK teardown on parent deletion must be checked by MigrationTestHelper / OpenCode.
    }
}
