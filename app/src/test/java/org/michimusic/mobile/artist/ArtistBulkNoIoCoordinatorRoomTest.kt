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
import org.michimusic.data.artist.ArtistBulkPermissionRecord
import org.michimusic.data.artist.ArtistBulkReceiptRecord
import org.michimusic.data.artist.ArtistBulkSessionRecord
import org.michimusic.data.artist.ArtistEnrichmentCacheDatabase
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** D24 integration tests for OpenCode: real Room, coordinator, no HTTP/Workers. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ArtistBulkNoIoCoordinatorRoomTest {
    private lateinit var db: ArtistEnrichmentCacheDatabase
    private val t = 1_800_000_000_000L
    private val a = "a".repeat(64)
    private val b = "b".repeat(64)
    private val snapshot = ArtistBulkReadSnapshot("lib", "source", 3, 5, 7)
    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            ArtistEnrichmentCacheDatabase::class.java,
        ).build()
    }
    @After fun teardown() { db.close() }

    private suspend fun seed() {
        db.artistBulkSessionDao().createPrepared(
            ArtistBulkSessionRecord("s", "lib", "source", 3, 5, 7, t, t, receiptCount=2),
            listOf(a, b).mapIndexed { index, fp -> ArtistBulkReceiptRecord(
                "s", fp, "lib", "Artist-$index", "[\"album-$index\"]",
                "mbid-$index", "assoc-$index", updatedAtEpochMs=t,
            ) },
        )
        for ((index,fp) in listOf(a,b).withIndex()) {
            assertTrue(db.artistBulkSessionDao().storePermission(
                ArtistBulkPermissionRecord(
                    "s", fp, "permit-$index", "lib", "source", 3, 5, 7,
                    "Artist-$index", "mbid-$index", "assoc-$index",
                    issuedAtEpochMs=t+100, expiresAtEpochMs=t+300_100, updatedAtEpochMs=t+100,
                ), 0, t+100,
            ))
        }
    }
    @Test fun reservesOnlyOneInStableOrder() = runBlocking {
        seed()
        val coordinator = ArtistBulkNoIoCoordinator(
            ArtistBulkDurableSessionStore(db), ArtistBulkSnapshotReader { snapshot }, { t+101 },
        )
        val first = coordinator.reserveOneForReview("s")
        assertEquals(a, first?.fingerprint)
        assertEquals(1, db.artistBulkSessionDao().activeNoIoReservations())
        assertNull(coordinator.reserveOneForReview("s"))
        assertTrue(coordinator.releaseWithoutDispatch(first!!))
        val second = coordinator.reserveOneForReview("s")
        assertEquals(b, second?.fingerprint)
    }
    @Test fun changedRootFailsClosedBeforeClaim() = runBlocking {
        seed()
        var call = 0
        val reader = ArtistBulkSnapshotReader {
            call++
            if (call == 1) snapshot else snapshot.copy(rootEpoch=4)
        }
        val coordinator = ArtistBulkNoIoCoordinator(ArtistBulkDurableSessionStore(db), reader, { t+101 })
        assertNull(coordinator.reserveOneForReview("s"))
        assertEquals(0, db.artistBulkSessionDao().activeNoIoReservations())
    }
    @Test fun changedContextAfterClaimReleasesImmediately() = runBlocking {
        seed()
        var call = 0
        val reader = ArtistBulkSnapshotReader {
            call++
            if (call == 3) snapshot.copy(catalogRevision=6) else snapshot
        }
        val coordinator = ArtistBulkNoIoCoordinator(ArtistBulkDurableSessionStore(db),reader,{t+101})
        assertNull(coordinator.reserveOneForReview("s"))
        assertEquals(0, db.artistBulkSessionDao().activeNoIoReservations())
        assertEquals(ArtistBulkPermissionRecord.RELEASED_NO_IO,
            db.artistBulkSessionDao().getPermission("s",a)?.status)
    }
    @Test fun cancelledSessionCannotReserve() = runBlocking {
        seed()
        assertTrue(db.artistBulkSessionDao().cancelPrepared("s",0,t+101))
        val coordinator=ArtistBulkNoIoCoordinator(
            ArtistBulkDurableSessionStore(db),ArtistBulkSnapshotReader{snapshot},{t+102})
        assertNull(coordinator.reserveOneForReview("s"))
    }
}
