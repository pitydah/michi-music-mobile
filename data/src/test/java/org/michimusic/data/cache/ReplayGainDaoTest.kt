package org.michimusic.data.cache

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.michimusic.data.local.ReplayGainReader
import org.michimusic.data.repository.isReplayGainPersistable
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// Exercises replaygain_cache against a real in-memory Room/SQLite database (not a mock), the
// same table that crashed on device with "NOT NULL constraint failed: replaygain_cache.trackGain".
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ReplayGainDaoTest {

    private lateinit var database: MichiDatabase
    private lateinit var dao: ReplayGainDao

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, MichiDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = database.replayGainDao()
    }

    @After
    fun teardown() {
        database.close()
    }

    @Test
    fun upsertingNaNGain_reproducesTheDeviceCrash() = runBlocking {
        // Proves the root cause: SQLite has no NaN storage class for REAL, so it silently
        // binds a NaN double as SQL NULL - which trackGain/albumGain's NOT NULL then rejects.
        try {
            dao.upsert(ReplayGainEntity(trackId = "no-tags", trackGain = Float.NaN, albumGain = Float.NaN))
            fail("expected a NOT NULL constraint violation when persisting NaN, as seen on device")
        } catch (e: SQLiteConstraintException) {
            assertTrue(e.message.orEmpty().contains("NOT NULL"))
        }
    }

    @Test
    fun trackWithoutAnyReplayGainTag_isNotCached_andDoesNotCrash() = runBlocking {
        val noTags = ReplayGainReader.ReplayGainData() // trackGain=NaN, albumGain=NaN
        assertTrue(!isReplayGainPersistable(noTags))

        // Mirrors LocalMediaRepository.queryTracks(): only upsert when persistable.
        if (isReplayGainPersistable(noTags)) {
            dao.upsert(ReplayGainEntity("track-without-tags", noTags.trackGain, noTags.albumGain))
        }

        assertNull(dao.getReplayGain("track-without-tags"))
    }

    @Test
    fun trackMissingOnlyAlbumGain_isNotCached_andDoesNotCrash() = runBlocking {
        // A file can have REPLAYGAIN_TRACK_GAIN without REPLAYGAIN_ALBUM_GAIN - both columns
        // are NOT NULL, so a partial reading can't be persisted without a schema change.
        val trackGainOnly = ReplayGainReader.ReplayGainData(trackGain = -6.5f, albumGain = Float.NaN)
        assertTrue(!isReplayGainPersistable(trackGainOnly))

        if (isReplayGainPersistable(trackGainOnly)) {
            dao.upsert(ReplayGainEntity("track-missing-album-gain", trackGainOnly.trackGain, trackGainOnly.albumGain))
        }

        assertNull(dao.getReplayGain("track-missing-album-gain"))
    }

    @Test
    fun trackWithBothGains_isCachedAndRetrievable() = runBlocking {
        val bothGains = ReplayGainReader.ReplayGainData(trackGain = -6.5f, albumGain = -7.1f)
        assertTrue(isReplayGainPersistable(bothGains))

        dao.upsert(ReplayGainEntity("track-with-gains", bothGains.trackGain, bothGains.albumGain))

        val stored = dao.getReplayGain("track-with-gains")
        assertNotNull(stored)
        assertEquals(-6.5f, stored!!.trackGain, 0.001f)
        assertEquals(-7.1f, stored.albumGain, 0.001f)
    }
}
