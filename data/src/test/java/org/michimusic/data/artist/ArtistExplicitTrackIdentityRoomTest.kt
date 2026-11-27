package org.michimusic.data.artist

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** D43 Room gates WRITTEN for OpenCode; not certified in the Drive environment. */
@RunWith(RobolectricTestRunner::class)
class ArtistExplicitTrackIdentityRoomTest {
    private lateinit var db: ArtistEnrichmentCacheDatabase
    private val mbid = "11111111-1111-4111-8111-111111111111"
    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            ArtistEnrichmentCacheDatabase::class.java,
        ).build()
    }
    @After fun tearDown() { db.close() }

    private fun item(lib: String = "libraryA", track: String = "track-1") =
        ArtistExplicitTrackIdentityRecord(lib, "album-1", track, "alex",
            "a".repeat(64), mbid, "device_music_root", 0, 7,
            "USER_EXPLICIT_TRACK_CONFIRMATION", 1, 1_800_000_000_000L)

    @Test fun proofColumnsSurviveARoomRead() = runBlocking {
        val dao = db.artistEnrichmentDao()
        val proof = item().copy(sourceContentSha256="a".repeat(64),sourceContentBytes=42L)
        dao.insertExplicitTrackIdentity(proof)
        assertEquals(proof, dao.explicitTrackIdentity("libraryA", "album-1", "track-1", "alex"))
    }
    @Test fun onlyAnExactLegacyRowCanBeReattested() = runBlocking {
        val dao = db.artistEnrichmentDao()
        dao.insertExplicitTrackIdentity(item())
        fun update(mbid: String) = kotlinx.coroutines.runBlocking {
            dao.reverifyLegacyExplicitTrackIdentityProof("libraryA", "album-1", "track-1", "alex",
                "a".repeat(64), mbid, "device_music_root", 0,
                "f".repeat(64), 42L, 1_800_000_000_500L)
        }
        assertEquals(0, update("22222222-2222-4222-8222-222222222222"))
        assertEquals(1, update(mbid))
        assertEquals(0, update(mbid))
        val row = dao.explicitTrackIdentity("libraryA", "album-1", "track-1", "alex")
        assertEquals("f".repeat(64), row?.sourceContentSha256)
        assertEquals(42L, row?.sourceContentBytes)
        assertEquals(2L, row?.revision)
    }
    @Test fun insertAndReadExactOneTrack() = runBlocking {
        val dao = db.artistEnrichmentDao()
        dao.insertExplicitTrackIdentity(item())
        assertEquals(item(),dao.explicitTrackIdentity("libraryA", "album-1", "track-1", "alex"))
        assertEquals(listOf(item()),dao.explicitTrackIdentities("libraryA",listOf("track-1")))
    }
    @Test fun otherLibraryIsNotVisible() = runBlocking {
        val dao = db.artistEnrichmentDao()
        dao.insertExplicitTrackIdentity(item())
        assertNull(dao.explicitTrackIdentity("libraryB", "album-1", "track-1", "alex"))
    }
    @Test fun secondTrackNeverInheritsFirst() = runBlocking {
        val dao = db.artistEnrichmentDao()
        dao.insertExplicitTrackIdentity(item())
        assertEquals(emptyList<ArtistExplicitTrackIdentityRecord>(),
            dao.explicitTrackIdentities("libraryA",listOf("track-2")))
    }
    @Test fun duplicatedInsertCannotReplaceChoice() = runBlocking {
        val dao = db.artistEnrichmentDao()
        dao.insertExplicitTrackIdentity(item())
        val error = runCatching {
            dao.insertExplicitTrackIdentity(item().copy(confirmedMbid="22222222-2222-4222-8222-222222222222"))
        }.exceptionOrNull()
        assertNotNull("Conflicting insert must abort", error)
        assertEquals(mbid,dao.explicitTrackIdentity("libraryA", "album-1", "track-1", "alex")?.confirmedMbid)
    }
}
