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
class ExistingGenreEvidenceBatchDaoTest {
    @Test fun exactBatchSelectsRequestedIdsForOneOwnerOnly() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(), MichiDatabase::class.java,
        ).allowMainThreadQueries().build()
        try {
            val dao = db.trackDao()
            dao.putTagEvidence(listOf(
                EmbeddedTagEvidence("saf-a", "one", 10, 5, 3),
                EmbeddedTagEvidence("saf-a", "two", 10, 5, 3),
                EmbeddedTagEvidence("saf-b", "one", 10, 5, 3),
            ))
            assertEquals(listOf("two"),
                dao.getTagEvidenceForTracks("saf-a", listOf("two")).map { it.trackId })
            assertEquals(emptyList<EmbeddedTagEvidence>(),
                dao.getTagEvidenceForTracks("saf-a", emptyList()))
        } finally { db.close() }
    }
}
