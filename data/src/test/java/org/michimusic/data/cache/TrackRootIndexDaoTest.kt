package org.michimusic.data.cache

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class TrackRootIndexDaoTest {
    private lateinit var database: MichiDatabase
    private lateinit var trackDao: TrackDao

    @Before
    fun setup() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            MichiDatabase::class.java,
        ).allowMainThreadQueries().build()
        trackDao = database.trackDao()
    }

    @After
    fun teardown() {
        database.close()
    }

    @Test
    fun replaceForSourceRemovesStaleRowsAndKeepsOtherRootsUntouched() = runBlocking {
        trackDao.insertAll(
            listOf(
                CachedTrack(id = "old", title = "Old", sourceId = "root-a"),
                CachedTrack(id = "keep", title = "Keep", sourceId = "root-b"),
            ),
        )

        trackDao.replaceForSource(
            "root-a",
            listOf(CachedTrack(id = "new", title = "New", sourceId = "root-a")),
        )

        val rootA = trackDao.getTracksForSource("root-a").first()
        val rootB = trackDao.getTracksForSource("root-b").first()
        assertEquals(listOf("new"), rootA.map { it.id })
        assertEquals(listOf("keep"), rootB.map { it.id })
    }
}
