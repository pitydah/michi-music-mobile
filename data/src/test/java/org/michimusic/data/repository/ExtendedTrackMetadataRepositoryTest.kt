package org.michimusic.data.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.michimusic.data.cache.CachedTrack
import org.michimusic.data.cache.EmbeddedTagEvidence
import org.michimusic.data.cache.MichiDatabase
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ExtendedTrackMetadataRepositoryTest {
    @Test fun lookupNeverTransfersComposerOrDiscToAnotherOwner() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(), MichiDatabase::class.java,
        ).allowMainThreadQueries().build()
        try {
            val dao = db.trackDao()
            dao.insertAll(listOf(
                CachedTrack(id = "same", title = "A", sourceId = "owner-a", size = 800, genre = "Jazz; Fusion"),
                CachedTrack(id = "same", title = "B", sourceId = "owner-b", size = 800),
            ))
            dao.putTagEvidence(listOf(
                EmbeddedTagEvidence("owner-a", "same", 800, 500, 3,
                    composer = "Compositor A", discNumber = 2, discTotal = 3,
                    trackNumber = 4, trackTotal = 12, date = "2024-04-05",
                    genre = "Jazz; Fusion", genreValues = "Jazz\u0000Fusion"),
                EmbeddedTagEvidence("owner-b", "same", 800, 500, 3,
                    composer = "Compositor B", discNumber = 1, date = "2023"),
            ))
            val repo = ExtendedTrackMetadataRepository(dao)
            val a = repo.load("owner-a", "same")!!
            val b = repo.load("owner-b", "same")!!
            assertEquals("Compositor A", a.composer)
            assertEquals(2, a.discNumber)
            assertEquals(3, a.discTotal)
            assertEquals(12, a.trackTotal)
            assertEquals("2024-04-05", a.date)
            assertEquals(listOf("Jazz", "Fusion"), a.genres)
            assertEquals("Compositor B", b.composer)
            assertEquals(1, b.discNumber)
            assertNull(b.trackTotal)
            assertEquals(listOf("same"), repo.loadBatch("owner-a", listOf("same", "same")).map { it.trackId })
            dao.insert(CachedTrack(id = "same", title = "Replaced", sourceId = "owner-a", size = 801))
            assertNull(repo.load("owner-a", "same"))
        } finally { db.close() }
    }

    @Test fun verifiedReadReportsATypedReasonInsteadOfAnEmptyTag() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(), MichiDatabase::class.java,
        ).allowMainThreadQueries().build()
        try {
            val repo = ExtendedTrackMetadataRepository(db.trackDao())
            assertEquals(
                ExtendedTagEvidenceResult.Unavailable(
                    ExtendedTagUnavailableReason.NOT_CONTENT_URI),
                repo.loadVerifiedResult("owner", "id", "/storage/song.mp3", 10),
            )
            assertEquals(
                ExtendedTagEvidenceResult.Unavailable(
                    ExtendedTagUnavailableReason.INVALID_TRACK_ID),
                repo.loadVerifiedResult("owner", "", "content://michi/song", 10),
            )
            assertEquals(
                ExtendedTagEvidenceResult.Unavailable(
                    ExtendedTagUnavailableReason.INVALID_SIZE),
                repo.loadVerifiedResult("owner", "id", "content://michi/song", 0),
            )
            // Without a provider context the read cannot be proven, and that is
            // still not evidence that the file has no genre.
            assertEquals(
                ExtendedTagEvidenceResult.Unavailable(
                    ExtendedTagUnavailableReason.PROVIDER_UNAVAILABLE),
                repo.loadVerifiedResult("owner", "id", "content://michi/song", 10),
            )
        } finally { db.close() }
    }
}
