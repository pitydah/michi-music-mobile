package org.michimusic.data.artist

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.UUID

/** D22: real Room test cases for OpenCode/Gradle; not certified by patch packaging. */
@RunWith(RobolectricTestRunner::class)
class ArtistBulkSessionJournalRoomTest {
    private lateinit var context: Context
    private lateinit var db: ArtistEnrichmentCacheDatabase
    private val id get() = "bulk-${UUID.randomUUID()}"
    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, ArtistEnrichmentCacheDatabase::class.java).build()
    }
    @After fun teardown() { db.close() }

    private fun session(id: String, count: Int) = ArtistBulkSessionRecord(
        sessionId = id, libraryId = "lib1", sourceId = "tree1",
        rootEpoch = 2, catalogRevision = 4, libraryRevision = 5,
        createdAtEpochMs = 100, updatedAtEpochMs = 100, receiptCount = count,
    )
    private fun receipts(id: String, count: Int) = (0 until count).map { i ->
        ArtistBulkReceiptRecord(
            sessionId = id, fingerprint = i.toString(16).padStart(64, '0'), libraryId = "lib1",
            artistCredit = "Artist$i", albumIdsJson = "[\"album$i\"]",
            mbid = "00000000-0000-0000-0000-${i.toString().padStart(12, '0')}",
            associationId = "association$i", updatedAtEpochMs = 100,
        )
    }
    @Test fun saveIsAtomicAndReadable() = runBlocking {
        val key = id
        db.artistBulkSessionDao().createPrepared(session(key, 3), receipts(key, 3))
        val pair = db.artistBulkSessionDao().readConsistent(key)!!
        assertEquals(3, pair.second.size)
        assertEquals(ArtistBulkSessionRecord.PREPARED_NOT_SUBMITTED, pair.first.status)
    }
    @Test fun duplicateSessionCannotReplaceConsent() = runBlocking {
        val key = id
        val dao = db.artistBulkSessionDao()
        dao.createPrepared(session(key, 1), receipts(key, 1))
        try {
            dao.createPrepared(
                session(key, 1).copy(libraryId = "other"),
                receipts(key, 1).map { it.copy(libraryId = "other") },
            )
            fail("Expected duplicate-key rejection")
        } catch (_: android.database.sqlite.SQLiteConstraintException) {
            // SQLite ABORT must preserve the first authoritative session.
        }
        assertEquals("lib1", dao.getSession(key)!!.libraryId)
    }
    @Test fun cancelCasIsDurableIdempotentAndHasNoExecuteTransition() = runBlocking {
        val key = id
        val dao = db.artistBulkSessionDao()
        dao.createPrepared(session(key, 2), receipts(key, 2))
        assertFalse(dao.cancelPrepared(key, 99, 101))
        assertTrue(dao.cancelPrepared(key, 0, 102))
        assertTrue(dao.cancelPrepared(key, 0, 103))
        val (parent, rows) = dao.readConsistent(key)!!
        assertEquals(1, parent.revision)
        assertEquals(ArtistBulkSessionRecord.CANCELLED_NOT_SUBMITTED, parent.status)
        assertTrue(rows.all { it.status == ArtistBulkSessionRecord.CANCELLED_NOT_SUBMITTED })
    }
    @Test fun incompleteReceiptCancellationRollsBackParent() = runBlocking {
        val key = id
        val dao = db.artistBulkSessionDao()
        dao.createPrepared(session(key, 2), receipts(key, 2))
        db.openHelper.writableDatabase.execSQL(
            "DELETE FROM artist_bulk_receipt WHERE sessionId = ? AND fingerprint = ?",
            arrayOf(key, "0".padStart(64, '0')),
        )
        try {
            dao.cancelPrepared(key, 0, 111)
            fail("Expected incomplete cancellation refusal")
        } catch (_: IllegalStateException) {
            // @Transaction must roll back parent state.
        }
        assertEquals(ArtistBulkSessionRecord.PREPARED_NOT_SUBMITTED, dao.getSession(key)!!.status)
    }
    @Test fun fileBackedSessionSurvivesDatabaseReopen() = runBlocking {
        val name = "artist-bulk-${UUID.randomUUID()}.db"
        val first = Room.databaseBuilder(context, ArtistEnrichmentCacheDatabase::class.java, name).build()
        val key = id
        try {
            first.artistBulkSessionDao().createPrepared(session(key, 1), receipts(key, 1))
        } finally { first.close() }
        val second = Room.databaseBuilder(context, ArtistEnrichmentCacheDatabase::class.java, name).build()
        try {
            assertNotNull(second.artistBulkSessionDao().readConsistent(key))
            assertTrue(second.artistBulkSessionDao().cancelPrepared(key, 0, 120))
        } finally {
            second.close()
            context.deleteDatabase(name)
        }
    }
}
