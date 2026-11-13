package org.michimusic.data.cache

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class RecoveredSafGenreFingerprintDaoTest {
    @Test fun recoveryRequiresIndexedMtimeAndKeepsOldSidecarOnRejectedUpdate() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(), MichiDatabase::class.java,
        ).allowMainThreadQueries().build()
        try {
            val dao = db.trackDao()
            dao.insert(CachedTrack(id = "song", title = "Song", sourceId = "saf-a",
                filepath = "content://provider/document/song", size = 2048, genre = ""))
            val indexed = EmbeddedTagEvidence("saf-a", "song", 2048, 43, 3)
            dao.putTagEvidence(listOf(indexed))
            val proposed = indexed.copy(genre = "Jazz", genreValues = "Jazz")
            val applied = dao.persistRecoveredGenres(listOf(proposed), listOf(RecoveredSafGenre(
                "saf-a", "song", "content://provider/document/song", 2048, "Jazz", 42,
            )))
            assertEquals(emptySet<Pair<String, String>>(), applied)
            assertEquals("", dao.getTrackBySource("saf-a", "song")?.genre)
            assertEquals("", dao.getTagEvidenceForSource("saf-a").single().genre)
        } finally { db.close() }
    }

    @Test fun firstRecoveryCreatesEvidenceAndUpdatesMissingGenreAtomically() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(), MichiDatabase::class.java,
        ).allowMainThreadQueries().build()
        try {
            val dao = db.trackDao()
            dao.insert(CachedTrack("song", "Song", sourceId = "saf-a", size = 2048,
                filepath = "content://provider/document/song", genre = ""))
            // C6 incorrectly required a sidecar row BEFORE the recovery could insert it.
            assertEquals(emptyList<EmbeddedTagEvidence>(), dao.getTagEvidenceForSource("saf-a"))
            val proposed = EmbeddedTagEvidence("saf-a", "song", 2048, 42, 3,
                genre = "Pop rock", genreValues = "Pop rock")
            val result = dao.persistRecoveredGenres(listOf(proposed), listOf(RecoveredSafGenre(
                "saf-a", "song", "content://provider/document/song", 2048, "Pop rock", 42)))
            assertEquals(setOf("saf-a" to "song"), result)
            assertEquals("Pop rock", dao.getTrackBySource("saf-a", "song")?.genre)
            assertEquals("Pop rock", dao.getTagEvidenceForSource("saf-a").single().genre)
        } finally { db.close() }
    }

    @Test fun missingOrMismatchedFreshProofNeverWritesASafGenre() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(), MichiDatabase::class.java,
        ).allowMainThreadQueries().build()
        try {
            val dao = db.trackDao()
            dao.insert(CachedTrack("song", "Song", sourceId = "saf-a", size = 2048,
                filepath = "content://provider/document/song", genre = ""))
            val change = RecoveredSafGenre("saf-a", "song",
                "content://provider/document/song", 2048, "Rock", 42)
            assertEquals(emptySet<Pair<String, String>>(), dao.persistRecoveredGenres(
                emptyList(), listOf(change)))
            assertEquals(emptySet<Pair<String, String>>(), dao.persistRecoveredGenres(
                listOf(EmbeddedTagEvidence("saf-a", "song", 2048, 41, 3, genre = "Rock")),
                listOf(change)))
            assertEquals("", dao.getTrackBySource("saf-a", "song")?.genre)
            assertEquals(emptyList<EmbeddedTagEvidence>(), dao.getTagEvidenceForSource("saf-a"))
        } finally { db.close() }
    }

    @Test fun stableStampAllowsAtomicRecoveryWithoutCrossingOwner() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(), MichiDatabase::class.java,
        ).allowMainThreadQueries().build()
        try {
            val dao = db.trackDao()
            dao.insertAll(listOf(
                CachedTrack("song", "Song", sourceId = "saf-a", size = 2048,
                    filepath = "content://provider/document/song", genre = ""),
                CachedTrack("song", "Other", sourceId = "saf-b", size = 2048,
                    filepath = "content://provider/document/other", genre = ""),
            ))
            val old = EmbeddedTagEvidence("saf-a", "song", 2048, 42, 3)
            dao.putTagEvidence(listOf(old))
            val applied = dao.persistRecoveredGenres(listOf(old.copy(genre = "Jazz", genreValues = "Jazz")),
                listOf(RecoveredSafGenre("saf-a", "song",
                    "content://provider/document/song", 2048, "Jazz", 42)))
            assertEquals(setOf("saf-a" to "song"), applied)
            assertEquals("Jazz", dao.getTrackBySource("saf-a", "song")?.genre)
            assertEquals("", dao.getTrackBySource("saf-b", "song")?.genre)
            assertEquals("Jazz", dao.getTagEvidenceForSource("saf-a").single().genre)
        } finally { db.close() }
    }
}
