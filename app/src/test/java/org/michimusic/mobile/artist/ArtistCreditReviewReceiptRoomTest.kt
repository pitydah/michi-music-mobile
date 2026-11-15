package org.michimusic.mobile.artist

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.michimusic.data.artist.ArtistCreditReviewReceiptRecord
import org.michimusic.data.artist.ArtistEnrichmentCacheDatabase
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Candidate Android/Robolectric tests: NOT executed outside an Android SDK checkout. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ArtistCreditReviewReceiptRoomTest {
    private lateinit var db: ArtistEnrichmentCacheDatabase
    private val artist = "05072650-4b05-4e8b-83d0-dba2576e306a"
    private val recording = "11111111-1111-4111-8111-111111111111"
    private val release = "22222222-2222-4222-8222-222222222222"

    @Before fun before() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, ArtistEnrichmentCacheDatabase::class.java)
            .allowMainThreadQueries().build()
    }

    @After fun after() { db.close() }

    private fun record(
        library: String = "local", source: String = "device_music_root",
        album: String = "album-1", track: String = "track-1", timestamp: Long = 1000L,
    ) = ArtistCreditReviewReceiptRecord(library, source, "band", album, track,
        artist, recording, release, "TRACK_ARTIST", timestamp)

    @Test fun exactReceiptSurvivesRoomReadAndDoesNotAuthorizeAnotherAlbum() = runBlocking {
        val dao = db.artistEnrichmentDao()
        dao.putCreditReviewReceipt(record())
        assertEquals(record(), dao.getCreditReviewReceipt("local", "device_music_root",
            "band", "album-1", "track-1"))
        assertNull(dao.getCreditReviewReceipt("local", "device_music_root",
            "band", "album-2", "track-1"))
        assertNull(dao.getCreditReviewReceipt("local", "another_root",
            "band", "album-1", "track-1"))
    }

    @Test fun differentSourcesAndLibrariesRemainIsolated() = runBlocking {
        val dao = db.artistEnrichmentDao()
        dao.putCreditReviewReceipt(record(source = "root-A"))
        dao.putCreditReviewReceipt(record(library = "another", source = "root-A"))
        dao.putCreditReviewReceipt(record(source = "root-B"))
        assertEquals("root-A", dao.getCreditReviewReceipt("local", "root-A",
            "band", "album-1", "track-1")?.sourceId)
        assertEquals("another", dao.getCreditReviewReceipt("another", "root-A",
            "band", "album-1", "track-1")?.libraryId)
        assertEquals("root-B", dao.getCreditReviewReceipt("local", "root-B",
            "band", "album-1", "track-1")?.sourceId)
    }

    @Test fun repeatedUserReviewUpsertsOneRowAndPruneDoesNotTouchRecentOtherLibrary() = runBlocking {
        val dao = db.artistEnrichmentDao()
        dao.putCreditReviewReceipt(record(timestamp = 10L))
        dao.putCreditReviewReceipt(record(timestamp = 9000L))
        dao.putCreditReviewReceipt(record(library = "other", timestamp = 10L))
        assertEquals(9000L, dao.getCreditReviewReceipt("local", "device_music_root",
            "band", "album-1", "track-1")?.verifiedAtMs)
        assertEquals(0, dao.pruneExpiredCreditReviewReceipts("local", 100L))
        assertEquals(1, dao.pruneExpiredCreditReviewReceipts("other", 100L))
        assertTrue(dao.getCreditReviewReceipt("local", "device_music_root",
            "band", "album-1", "track-1") != null)
    }
}
