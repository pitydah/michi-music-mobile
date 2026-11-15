package org.michimusic.data.artist

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Needs the real Room 11 schema/KSP: written, not certified without Android Gradle. */
@RunWith(RobolectricTestRunner::class)
class ArtistObservedTagEvidenceTest {
    private lateinit var database: ArtistEnrichmentCacheDatabase

    @Before fun before() {
        val context: Context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, ArtistEnrichmentCacheDatabase::class.java).build()
    }
    @After fun after() { database.close() }

    private fun observed(track: String, field: String, ordinal: Int, value: String) =
        ArtistTrackEvidenceRecord("local", track, field, ordinal, value, 10, "EXISTING_TAG_READ:device_music_root")

    @Test fun shrinkAndRemoveAutoEvidencePreservesManualAndOtherTracks() = runBlocking {
        val dao = database.artistEnrichmentDao()
        val manual = observed("t1", "ARTIST_MBID", 0, "manual").copy(source="USER_REQUESTED_EMBEDDED_TAG_SCAN")
        dao.insertTrackEvidence(listOf(
            manual,
            observed("t1", "AUTO_ARTIST_MBID", 0, "uuid-a"),
            observed("t1", "AUTO_ARTIST_MBID", 1, "uuid-b"),
            observed("t1", "AUTO_ALBUM_ARTIST_MBID", 0, "uuid-c"),
            observed("t2", "AUTO_ARTIST_MBID", 0, "other-track"),
            observed("t1", "COMMENT", 0, "unrelated"),
        ))
        dao.replaceObservedArtistMbidsForBatch("local", listOf("t1"),
            listOf(observed("t1", "AUTO_ARTIST_MBID", 0, "uuid-new")))
        val refreshed = dao.getTrackEvidence("local", "t1")
        assertEquals(setOf("ARTIST_MBID", "AUTO_ARTIST_MBID", "COMMENT"), refreshed.map { it.field }.toSet())
        assertEquals("uuid-new", refreshed.single { it.field=="AUTO_ARTIST_MBID" }.value)
        dao.replaceObservedArtistMbidsForBatch("local", listOf("t1"), emptyList())
        assertEquals(setOf("ARTIST_MBID", "COMMENT"),
            dao.getTrackEvidence("local", "t1").map { it.field }.toSet())
        assertEquals("manual", dao.getTrackEvidence("local", "t1").single { it.field=="ARTIST_MBID" }.value)
        assertEquals("other-track", dao.getTrackEvidence("local", "t2").single().value)
    }

    @Test fun repeatWithUnchangedMbidsDoesNotRewriteRoomOrTimestamp() = runBlocking {
        val dao = database.artistEnrichmentDao()
        val baseline = observed("idempotent-track", "AUTO_ARTIST_MBID", 0, "uuid-a")
        assertEquals(true, dao.replaceObservedArtistMbidsForBatch("local",
            listOf("idempotent-track"), listOf(baseline)))
        assertEquals(false, dao.replaceObservedArtistMbidsForBatch("local",
            listOf("idempotent-track"), listOf(baseline.copy(observedAtMs = 999))))
        val unchanged = dao.getTrackEvidence("local", "idempotent-track").single()
        assertEquals(baseline.observedAtMs, unchanged.observedAtMs)
        assertEquals(true, dao.replaceObservedArtistMbidsForBatch("local",
            listOf("idempotent-track"), listOf(baseline.copy(
                observedAtMs = 999, source = "EXISTING_TAG_READ:another_root"))))
        assertEquals("EXISTING_TAG_READ:another_root",
            dao.getTrackEvidence("local", "idempotent-track").single().source)
    }

    @Test fun batchCannotChangeOtherLibrariesOrSneakManualFields() = runBlocking {
        val dao = database.artistEnrichmentDao()
        dao.insertTrackEvidence(listOf(observed("x", "AUTO_ARTIST_MBID", 0, "library-a").copy(libraryId="another")))
        dao.replaceObservedArtistMbidsForBatch("local", listOf("x"),
            listOf(observed("x", "AUTO_ALBUM_ARTIST_MBID", 0, "library-local")))
        assertEquals("library-a", dao.getTrackEvidence("another", "x").single().value)
        assertEquals("library-local", dao.getTrackEvidence("local", "x").single().value)
        var rejected = false
        try {
            dao.replaceObservedArtistMbidsForBatch("local", listOf("x"),
                listOf(observed("x", "ARTIST_MBID", 0, "forged-manual")))
        } catch (_: IllegalArgumentException) { rejected = true }
        assertEquals(true, rejected)
    }
    @Test fun releaseAndRecordingEvidenceAreReplacedButNeverUserEvidence() = runBlocking {
        val dao = database.artistEnrichmentDao()
        dao.insertTrackEvidence(listOf(
            observed("music-1", "RECORDING_MBID", 0, "user-recording").copy(source="USER_REQUESTED_EMBEDDED_TAG_SCAN"),
            observed("music-1", "AUTO_RECORDING_MBID", 0, "old-recording"),
            observed("music-1", "AUTO_RELEASE_MBID", 0, "old-release"),
            observed("music-2", "AUTO_RELEASE_MBID", 0, "other-track"),
        ))
        assertEquals(true, dao.replaceObservedArtistMbidsForBatch("local", listOf("music-1"), listOf(
            observed("music-1", "AUTO_RECORDING_MBID", 0, "new-recording"),
            observed("music-1", "AUTO_RELEASE_MBID", 0, "new-release"),
        )))
        assertEquals(setOf("RECORDING_MBID", "AUTO_RECORDING_MBID", "AUTO_RELEASE_MBID"),
            dao.getTrackEvidence("local", "music-1").map { it.field }.toSet())
        assertEquals("other-track", dao.getTrackEvidence("local", "music-2").single().value)
        assertEquals(false, dao.replaceObservedArtistMbidsForBatch("local", listOf("music-1"), listOf(
            observed("music-1", "AUTO_RECORDING_MBID", 0, "new-recording").copy(observedAtMs=99),
            observed("music-1", "AUTO_RELEASE_MBID", 0, "new-release").copy(observedAtMs=99),
        )))
        dao.replaceObservedArtistMbidsForBatch("local", listOf("music-1"), emptyList())
        assertEquals(listOf("RECORDING_MBID"), dao.getTrackEvidence("local", "music-1").map { it.field })
    }

}
