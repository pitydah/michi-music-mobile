package org.michimusic.data.cache

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MetadataRevisionTriggerTest {
    private lateinit var db: MichiDatabase
    private val source = "source"
    private val track = "track"
    private val uri = "content://media/1"

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            MichiDatabase::class.java,
        ).addCallback(MichiDatabase.METADATA_REVISION_CALLBACK)
            .allowMainThreadQueries().build()
    }

    @After fun tearDown() { db.close() }

    private fun row() = CachedTrack(
        id = track, title = "Antes", artist = "Artista", album = "Álbum",
        sourceId = source, filepath = uri, size = 100L, verifiedSize = 100L,
        verifiedSha256 = "a".repeat(64), verifiedAt = 7L,
        verifiedAlgorithm = "sha256", genre = "Rock", trackNumber = 1, year = 1990,
    )

    @Test fun explicitRevisionLoweringIsAbortedAndRevisionStaysMonotonic() = runBlocking {
        db.trackDao().insertAll(listOf(row()))
        val before = db.trackDao().getTrackBySource(source, track)!!
        var threw = false
        try {
            db.openHelper.writableDatabase.execSQL(
                "UPDATE cached_tracks SET metadataRevision=metadataRevision-1 " +
                    "WHERE sourceId='$source' AND id='$track'",
            )
        } catch (_: Exception) {
            threw = true
        }
        assertTrue("lowering metadataRevision must be rejected", threw)
        assertEquals(
            before.metadataRevision,
            db.trackDao().getTrackBySource(source, track)!!.metadataRevision,
        )
    }

    @Test fun deleteThenInsertAdvancesRevision() = runBlocking {
        db.trackDao().insertAll(listOf(row()))
        val first = db.trackDao().getTrackBySource(source, track)!!
        db.openHelper.writableDatabase.execSQL(
            "DELETE FROM cached_tracks WHERE sourceId='$source' AND id='$track'",
        )
        db.trackDao().insertAll(listOf(row()))
        val second = db.trackDao().getTrackBySource(source, track)!!
        assertTrue(
            "delete+insert must advance revision: first=${first.metadataRevision} " +
                "second=${second.metadataRevision}",
            second.metadataRevision > first.metadataRevision,
        )
    }
}
