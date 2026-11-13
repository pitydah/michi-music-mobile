package org.michimusic.data.cache

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Cold sidecar cannot outlive its parser or contradict the cached authoritative genre. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class VersionedTagEvidenceGateTest {
    @Test fun versionSkewAndMissingTimestampHideAllStaleEvidence() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(), MichiDatabase::class.java,
        ).allowMainThreadQueries().build()
        try {
            val dao = db.trackDao()
            dao.insert(CachedTrack(id = "one", title = "A", sourceId = "owner",
                genre = "Jazz", size = 99, filepath = "content://a"))
            dao.putTagEvidence(listOf(EmbeddedTagEvidence("owner", "one", 99, 42,
                EmbeddedTagEvidence.CURRENT_PARSER_VERSION,
                genre = "Jazz", genreValues = "Jazz", composer = "Composer")))
            assertNotNull(dao.getCurrentExtendedTagEvidence("owner", "one"))
            assertEquals("Jazz", dao.observeGenreEvidenceSnapshot().first().single().genreValues)
            val future = EmbeddedTagEvidence.CURRENT_PARSER_VERSION + 1
            assertNull(dao.getCurrentExtendedTagEvidence("owner", "one", future))
            assertEquals("", dao.observeGenreEvidenceSnapshot(future).first().single().genreValues)
            assertEquals(emptyList<EmbeddedTagEvidence>(),
                dao.getCurrentExtendedTagEvidenceBatch("owner", listOf("one"), future))
            dao.putTagEvidence(listOf(EmbeddedTagEvidence("owner", "one", 99, 0,
                EmbeddedTagEvidence.CURRENT_PARSER_VERSION,
                genre = "Jazz", genreValues = "Jazz", composer = "Untrusted")))
            assertNull(dao.getCurrentExtendedTagEvidence("owner", "one"))
            assertEquals("", dao.observeGenreEvidenceSnapshot().first().single().genreValues)
            dao.putTagEvidence(listOf(EmbeddedTagEvidence("owner", "one", 99, 42,
                EmbeddedTagEvidence.CURRENT_PARSER_VERSION,
                genre = "Metal", genreValues = "Metal", composer = "Wrong")))
            assertNull(dao.getCurrentExtendedTagEvidence("owner", "one"))
            assertEquals("", dao.observeGenreEvidenceSnapshot().first().single().genreValues)
        } finally { db.close() }
    }
}
