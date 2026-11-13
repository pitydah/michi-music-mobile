package org.michimusic.data.cache

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ColdMetadataDaoGuardTest {
    @Test fun sameOwnerAndIdAreNotEnoughWhenUriOrSizeChanges() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(), MichiDatabase::class.java,
        ).allowMainThreadQueries().build()
        try {
            val dao = db.trackDao()
            dao.insertAll(listOf(CachedTrack(id = "same", title = "A", sourceId = "saf-a",
                size = 512, genre = "Jazz", filepath = "content://a/one")))
            dao.putTagEvidence(listOf(EmbeddedTagEvidence("saf-a", "same", 512, 77, 3,
                genre = "Jazz", composer = "Composer A")))
            assertEquals("Composer A", dao.getCurrentExtendedTagEvidenceForPath(
                "saf-a", "same", "content://a/one", 512)?.composer)
            assertNull(dao.getCurrentExtendedTagEvidenceForPath(
                "saf-a", "same", "content://b/one", 512))
            assertNull(dao.getCurrentExtendedTagEvidenceForPath(
                "saf-a", "same", "content://a/one", 513))
            assertNull(dao.getCurrentExtendedTagEvidenceForPath(
                "saf-b", "same", "content://a/one", 512))
        } finally { db.close() }
    }
    @Test fun ordinaryRecoveryEvidenceCannotReplaceStaleSafSidecar() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(), MichiDatabase::class.java,
        ).allowMainThreadQueries().build()
        try {
            val dao = db.trackDao()
            dao.insertAll(listOf(CachedTrack(id = "same", title = "A", sourceId = "saf-a",
                size = 512, genre = "", filepath = "content://a/one")))
            dao.putTagEvidence(listOf(EmbeddedTagEvidence("saf-a", "same", 512, 77, 3,
                genre = "", composer = "Old")))
            dao.persistRecoveredGenres(listOf(EmbeddedTagEvidence("saf-a", "same", 512, 99, 3,
                genre = "Jazz", composer = "New")), emptyList())
            val saved = dao.getTagEvidenceForTrack("saf-a", "same")
            assertEquals("Old", saved?.composer)
            assertEquals(77L, saved?.modifiedAt)
        } finally { db.close() }
    }
}
