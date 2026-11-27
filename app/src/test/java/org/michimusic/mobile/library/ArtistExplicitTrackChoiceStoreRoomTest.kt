package org.michimusic.mobile.library

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.michimusic.data.artist.ArtistEnrichmentCacheDatabase

/** D44 Room integration cases: pending Gradle execution in OpenCode. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
internal class ArtistExplicitTrackChoiceStoreRoomTest {
    private lateinit var database: ArtistEnrichmentCacheDatabase
    private val mbidA = "11111111-1111-4111-8111-111111111111"
    private val mbidB = "22222222-2222-4222-8222-222222222222"
    private val claim = ArtistExactOccurrenceClaim("lib", "album-a", "Alex", "Alex",
        "source:local:track-a", "local_track_a", "content-hash:123")
    private val req = ArtistTrackChoiceRequest("lib", "album-a", "local_track_a", "Alex",
        claim.trackIdentity, mbidA, listOf(mbidA, mbidB), "device_music_root", 0, 5, true)
    private var authority = ArtistTrackChoiceAuthority("lib", "device_music_root", 0, 5)
    private var live: ArtistTrackChoiceLiveReview? = ArtistTrackChoiceLiveReview(claim, listOf(mbidA, mbidB))

    @Before fun setup() {
        database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(),
            ArtistEnrichmentCacheDatabase::class.java).build()
    }
    @After fun teardown() { database.close() }

    private fun store(
        view: (ArtistTrackChoiceRequest) -> ArtistTrackChoiceLiveReview? = { live },
        bytes: suspend (ArtistExactOccurrenceClaim) -> ArtistTrackByteProof? = {
            ArtistTrackByteProof("f".repeat(64), 42)
        },
    ) = ArtistExplicitTrackChoiceStore(database,{authority},view,
            currentSourceProof=bytes,
            clock={1_800_000_000_000L})

    private suspend fun stored() = database.artistEnrichmentDao().explicitTrackIdentity(
        "lib", "album-a", "local_track_a", "alex")

    @Test fun neitherSha256ReadRunsInsideRoomTransaction() = runBlocking {
        var reads = 0
        val result = store(bytes = {
            assertFalse("SHA-256 work must not hold a Room transaction", database.inTransaction())
            reads++
            ArtistTrackByteProof("f".repeat(64), 42)
        }).confirmFirst(req, claim)
        assertTrue(result)
        org.junit.Assert.assertEquals(2, reads)
    }

    @Test fun inaccessibleSourceNeverCreatesIdentity() = runBlocking {
        assertFalse(store(bytes={ null }).confirmFirst(req,claim))
        assertNull(stored())
    }
    @Test fun changedSourceBytesInsideTransactionRollsBackInsert() = runBlocking {
        var calls = 0
        val first = ArtistTrackByteProof("f".repeat(64),42)
        val second = ArtistTrackByteProof("a".repeat(64),42)
        val result = store(bytes={ if (++calls == 1) first else second }).confirmFirst(req,claim)
        assertFalse(result)
        assertNull(stored())
    }
    @Test fun trueFreshFirstClickInsertsOnlyOneTrack() = runBlocking {
        assertTrue(store().confirmFirst(req,claim))
        assertTrue(stored()?.confirmedMbid == mbidA)
    }
    @Test fun changedContentDoesNotProduceIdentity() = runBlocking {
        live = live?.copy(claim = claim.copy(localContentSignature="replaced"))
        assertFalse(store().confirmFirst(req,claim))
        assertNull(stored())
    }
    @Test fun changedCandidateSetDoesNotProduceIdentity() = runBlocking {
        live = live?.copy(offeredMbids=listOf(mbidB))
        assertFalse(store().confirmFirst(req,claim))
        assertNull(stored())
    }
    @Test fun changedLibraryAuthorityDoesNotProduceIdentity() = runBlocking {
        authority = authority.copy(libraryId="other")
        assertFalse(store().confirmFirst(req,claim))
        assertNull(stored())
    }
    @Test fun changingAfterFirstGateRollsBackInsert() = runBlocking {
        var reads=0
        val v=store(view = {
            reads++
            if (reads==1) ArtistTrackChoiceLiveReview(claim,listOf(mbidA,mbidB))
            else ArtistTrackChoiceLiveReview(claim.copy(localContentSignature="new bytes"),listOf(mbidA,mbidB))
        })
        assertFalse(v.confirmFirst(req,claim))
        assertNull(stored())
    }
}
