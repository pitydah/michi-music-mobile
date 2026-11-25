package org.michimusic.data.cache

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MetadataRevisionClockDaoTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun revisionIsMonotonicAcrossUpdateDeleteAndReinsert() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, MichiDatabase::class.java)
            .addCallback(MichiDatabase.METADATA_REVISION_CALLBACK)
            .allowMainThreadQueries()
            .build()
        try {
            val dao = db.trackDao()
            dao.insert(CachedTrack(id = "track", sourceId = "source", title = "A"))
            assertEquals(1L, dao.getTrackBySource("source", "track")!!.metadataRevision)

            dao.markDownloaded("source", "track")
            assertEquals(2L, dao.getTrackBySource("source", "track")!!.metadataRevision)

            dao.deleteForSource("source")
            dao.insert(CachedTrack(id = "track", sourceId = "source", title = "A"))
            assertEquals(4L, dao.getTrackBySource("source", "track")!!.metadataRevision)
        } finally {
            db.close()
        }
    }
}
