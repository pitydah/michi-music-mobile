package org.michimusic.data.artist

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import android.content.Context
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ArtistTrackEvidenceTest {
    @Test fun evidenceIsScopedByLibraryTrackAndNotMixedIntoSongMetadata() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(),
            ArtistEnrichmentCacheDatabase::class.java).build()
        try {
            val dao = db.artistEnrichmentDao()
            val a = ArtistTrackEvidenceRecord("lib-a", "local_1", "ARTIST_MBID", 0,
                "bd6d4f63-5cd0-4d78-a292-167997794c21", 1L)
            dao.replaceTrackEvidence("lib-a", "local_1", listOf(a))
            assertEquals(1, dao.getTrackEvidence("lib-a", "local_1").size)
            assertTrue(dao.getTrackEvidence("lib-b", "local_1").isEmpty())
            dao.replaceTrackEvidence("lib-a", "local_1", emptyList())
            assertTrue(dao.getTrackEvidence("lib-a", "local_1").isEmpty())
        } finally { db.close() }
    }
}
