package org.michimusic.data.intelligence

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.michimusic.data.cache.CachedTrack
import org.michimusic.data.cache.EmbeddedTagEvidence
import org.michimusic.data.cache.MichiDatabase
import org.robolectric.RobolectricTestRunner

/** Runs in :data with REAL Room/SQLite under Robolectric; no mocked DAO. */
@RunWith(RobolectricTestRunner::class)
class MichiGenrePostWriteDaoTest {
    private lateinit var context: Context
    private lateinit var db: MichiDatabase
    private val dbName = "mi24-genre-reconcile.db"
    private val path = "content://media/external/audio/media/17"
    private val oldSize = 1000L

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(dbName)
        db = Room.databaseBuilder(context, MichiDatabase::class.java, dbName)
            .allowMainThreadQueries().build()
    }
    @After fun tearDown() {
        db.close()
        context.deleteDatabase(dbName)
    }
    private fun evidence(owner: String, id: String, size: Long, genres: String) =
        EmbeddedTagEvidence(
            sourceId = owner, trackId = id, sizeBytes = size,
            modifiedAt = 2000L, parserVersion = EmbeddedTagEvidence.CURRENT_PARSER_VERSION,
            genre = genres, genreValues = genres, title = "Track", artist = "Artist", album = "Album",
        )

    @Test fun verifiedWriteUpdatesOnlyExactOwnerAndClearsStaleSyncHash() = runBlocking {
        val dao = db.trackDao()
        dao.insertAll(listOf(
            CachedTrack(id="17", sourceId="saf-A", filepath=path, size=oldSize,genre="Jazz",title="Track A",
                verifiedSize=oldSize,verifiedSha256="not-a-valid-after-edit-hash",verifiedAlgorithm="sha256",verifiedAt=1000),
            CachedTrack(id="17", sourceId="saf-B", filepath="content://other/17",size=oldSize,genre="Blues",title="Track B"),
        ))
        assertTrue(dao.replaceGenreAfterVerifiedWrite("saf-A","17",path,oldSize,"Jazz",1012,
            evidence("saf-A","17",1012,"Progressive Rock")))
        val after = dao.getTrackBySource("saf-A", "17")!!
        val other = dao.getTrackBySource("saf-B", "17")!!
        assertEquals("Progressive Rock",after.genre)
        assertEquals(1012L,after.size)
        assertEquals(0L,after.verifiedSize)
        assertEquals("",after.verifiedSha256)
        assertEquals("Blues",other.genre)
        assertNotNull(dao.getTagEvidenceForTrack("saf-A","17"))
        assertEquals(null,dao.getTagEvidenceForTrack("saf-B","17"))
    }

    @Test fun staleOwnerPathSizeOrGenreNeverWritesGhostEvidence() = runBlocking {
        val dao = db.trackDao()
        dao.insert(CachedTrack(id="17",sourceId="saf-A",filepath=path,size=oldSize,genre="Jazz",title="Track"))
        val next = evidence("saf-A","17",1012,"Rock")
        assertFalse(dao.replaceGenreAfterVerifiedWrite("saf-B","17",path,oldSize,"Jazz",1012,next))
        assertFalse(dao.replaceGenreAfterVerifiedWrite("saf-A","17","content://wrong",oldSize,"Jazz",1012,next))
        assertFalse(dao.replaceGenreAfterVerifiedWrite("saf-A","17",path,999,"Jazz",1012,next))
        assertFalse(dao.replaceGenreAfterVerifiedWrite("saf-A","17",path,oldSize,"Blues",1012,next))
        assertEquals("Jazz",dao.getTrackBySource("saf-A","17")!!.genre)
        assertEquals(null,dao.getTagEvidenceForTrack("saf-A","17"))
    }
}
