package org.michimusic.data.cache

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Exercises the actual Room projection; only execute in Android/Robolectric. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class GenreMetadataDaoTest {
    @Test fun genreProjectionNeverFusesRowsWithSameIdFromDifferentOwners() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(), MichiDatabase::class.java,
        ).allowMainThreadQueries().build()
        try {
            db.trackDao().insertAll(listOf(
                CachedTrack(id = "shared", title = "Song", sourceId = "peer-a", genre = "Jazz"),
                CachedTrack(id = "shared", title = "Song", sourceId = "peer-b", genre = "Metal"),
                CachedTrack(id = "only", title = "Only", sourceId = "peer-a", genre = "Folk"),
                CachedTrack(id = "unknown", title = "Untagged", sourceId = "peer-a"),
            ))
            val metadata = db.trackDao().observeGenreEvidenceSnapshot().first()
            assertEquals(4, metadata.size)
            assertEquals(setOf("peer-a", "peer-b"), metadata.filter { it.id == "shared" }
                .map { it.sourceId }.toSet())
            assertEquals(setOf("Jazz", "Metal"), metadata.filter { it.id == "shared" }
                .map { it.genre }.toSet())
            assertEquals(setOf("shared"), metadata.groupBy { it.id }
                .filterValues { rows -> rows.map { it.sourceId }.toSet().size > 1 }.keys)
            // Untagged rows are essential: they may be the other owner of a tagged ID.
            assertTrue(metadata.any { it.id == "unknown" && it.genre.isBlank() })
        } finally { db.close() }
    }

    @Test fun structuredSidecarProjectionRequiresMatchingOwnerSizeVersionAndGenre() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(), MichiDatabase::class.java,
        ).allowMainThreadQueries().build()
        try {
            db.trackDao().insertAll(listOf(
                CachedTrack(id = "one", title = "A", sourceId = "saf-a", size = 42,
                    genre = "Jazz; Fusion", filepath = "content://a"),
                CachedTrack(id = "one", title = "A", sourceId = "saf-b", size = 42,
                    genre = "Rock", filepath = "content://b"),
                CachedTrack(id = "two", title = "B", sourceId = "saf-a", size = 99,
                    genre = "Metal", filepath = "content://c"),
            ))
            db.trackDao().putTagEvidence(listOf(
                EmbeddedTagEvidence("saf-a", "one", 42, 12, 3,
                    genre = "Jazz; Fusion", genreValues = "Jazz\u0000Fusion"),
                EmbeddedTagEvidence("saf-b", "one", 42, 12, 3,
                    genre = "Different", genreValues = "Poison"),
                EmbeddedTagEvidence("saf-a", "two", 19, 12, 3,
                    genre = "Metal", genreValues = "Poison"),
            ))
            val rows = db.trackDao().observeGenreEvidenceSnapshot().first()
            assertEquals("Jazz\u0000Fusion", rows.single { it.sourceId == "saf-a" && it.id == "one" }.genreValues)
            assertEquals("", rows.single { it.sourceId == "saf-b" && it.id == "one" }.genreValues)
            assertEquals("", rows.single { it.sourceId == "saf-a" && it.id == "two" }.genreValues)
        } finally { db.close() }
    }

    @Test fun downloadFlagUpdateDoesNotAlterGenreProjection() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(), MichiDatabase::class.java,
        ).allowMainThreadQueries().build()
        try {
            db.trackDao().insert(CachedTrack(id = "one", title = "One",
                sourceId = "peer-a", genre = "Jazz", downloaded = false))
            val before = db.trackDao().observeGenreEvidenceSnapshot().first()
            db.trackDao().markDownloaded("peer-a", "one")
            val after = db.trackDao().observeGenreEvidenceSnapshot().first()
            assertEquals(before, after)
        } finally { db.close() }
    }

    @Test fun untaggedOwnerIsPresentInSnapshotWhenOtherOwnerHasGenre() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(), MichiDatabase::class.java,
        ).allowMainThreadQueries().build()
        try {
            db.trackDao().insertAll(listOf(
                CachedTrack(id = "same", title = "Same", sourceId = "peer-a", genre = "Jazz"),
                CachedTrack(id = "same", title = "Same", sourceId = "peer-b", genre = ""),
            ))
            val snapshot = db.trackDao().observeGenreEvidenceSnapshot().first()
            assertEquals(2, snapshot.size)
            assertEquals(setOf("peer-a", "peer-b"), snapshot.map { it.sourceId }.toSet())
        } finally { db.close() }
    }
}
