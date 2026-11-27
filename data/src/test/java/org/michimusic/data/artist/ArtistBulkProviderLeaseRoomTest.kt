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

/** D27 integration cases WRITTEN for OpenCode; not executed with Gradle here. */
@RunWith(RobolectricTestRunner::class)
class ArtistBulkProviderLeaseRoomTest {
    private lateinit var db: ArtistEnrichmentCacheDatabase
    private val t=1_800_000_000_000L
    private val fp="a".repeat(64)
    private val mbid="05072650-4b05-4e8b-83d0-dba2576e306a"
    private val conditions=ArtistBulkProviderConditions(true,true,true,true)
    private fun scope()=ArtistBulkTerminalScope("lib","saf-A",3,5,7)
    private fun session()=ArtistBulkSessionRecord("s","lib","saf-A",3,5,7,t-1000,t-1000,receiptCount=1)
    private fun receipt()=ArtistBulkReceiptRecord("s",fp,"lib","Artist","[\"album\"]",mbid,"assoc",updatedAtEpochMs=t-1000)
    private fun permit()=ArtistBulkPermissionRecord("s",fp,"permit-1","lib","saf-A",3,5,7,
        "Artist",mbid,"assoc",status=ArtistBulkPermissionRecord.RESERVED_NO_IO,
        issuedAtEpochMs=t-1000,expiresAtEpochMs=t+100000,updatedAtEpochMs=t-100,revision=1)
    @Before fun setUp(){db=Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext<Context>(),ArtistEnrichmentCacheDatabase::class.java).build()}
    @After fun tearDown(){db.close()}
    private suspend fun seed():ArtistBulkProfileLookupPreview {
        val s=db.artistBulkSessionDao();s.createPrepared(session(),listOf(receipt()))
        s.insertPermission(permit())
        return requireNotNull(ArtistBulkProviderPreflight.inspect(permit(),scope(),conditions,t))
    }
    @Test fun firstStageIsDurableAndDoubleTapReturnsSameNoIoReceipt()=runBlocking {
        val p=seed();val d=db.artistBulkProviderLeaseDao()
        val first=requireNotNull(d.stageWithoutIo(p,scope(),conditions,t))
        assertFalse(first.mayDispatch)
        assertEquals(first,d.stageWithoutIo(p,scope(),conditions,t+1))
        assertEquals(1L,d.quota(p.provider.name)?.revision)
        assertEquals(t+1100,d.quota(p.provider.name)?.nextEligibleAtEpochMs)
    }
    @Test fun changingIdentityRootOrSessionStopsStage()=runBlocking {
        val p=seed();val d=db.artistBulkProviderLeaseDao()
        assertNull(d.stageWithoutIo(p,scope().copy(rootEpoch=4),conditions,t))
        assertNull(d.stageWithoutIo(p.copy(mbid="00000000-0000-0000-0000-000000000001"),scope(),conditions,t))
        assertNull(d.stageWithoutIo(p,scope(),conditions.copy(unmeteredNetwork=false),t))
        assertNull(d.quota(p.provider.name))
    }
    @Test fun noRearmAfterAbandonmentOrExpiration()=runBlocking {
        val p=seed();val d=db.artistBulkProviderLeaseDao()
        val staged=requireNotNull(d.stageWithoutIo(p,scope(),conditions,t))
        assertTrue(d.abandonWithoutIo(staged,t+1))
        assertFalse(d.abandonWithoutIo(staged,t+2))
        assertNull(d.stageWithoutIo(p,scope(),conditions,t+2000))
        assertEquals(ArtistBulkProviderLeaseRecord.ABANDONED_NO_IO,d.leaseForPermit(p.permitId)?.state)
    }
    @Test fun quotaAndLiveLeaseBlockOtherConsentGlobally()=runBlocking {
        val p=seed();val d=db.artistBulkProviderLeaseDao()
        assertNotNull(d.stageWithoutIo(p,scope(),conditions,t))
        assertEquals(1,d.liveStagedLeases(t+1))
        assertNull(ArtistBulkProviderLeasePolicy.deadlines(t+100,p.expiresAtEpochMs,
            requireNotNull(d.quota(p.provider.name))))
    }
    @Test fun terminalReceiptPreventsNewLease()=runBlocking {
        val p=seed();val d=db.artistBulkProviderLeaseDao()
        assertNotNull(db.artistBulkTerminalDao().closeWithoutIo(permit(),scope(),
            ArtistBulkTerminalOutcome.CANCELLED,t))
        assertNull(d.stageWithoutIo(p,scope(),conditions,t+1))
    }
    @Test fun expirationDoesNotTurnIntoAuthorization()=runBlocking {
        val p=seed();val d=db.artistBulkProviderLeaseDao()
        val staged=requireNotNull(d.stageWithoutIo(p,scope(),conditions,t))
        assertEquals(1,d.expireLeases(staged.expiresAtEpochMs))
        assertEquals(0,d.liveStagedLeases(staged.expiresAtEpochMs))
        assertNull(d.stageWithoutIo(p,scope(),conditions,staged.expiresAtEpochMs+1100))
        assertEquals(ArtistBulkProviderLeaseRecord.EXPIRED_NO_IO,d.leaseForPermit(p.permitId)?.state)
    }
}
