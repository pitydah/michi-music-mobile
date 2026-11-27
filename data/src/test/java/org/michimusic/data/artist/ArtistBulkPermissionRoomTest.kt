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

/** D23: real Room regressions for OpenCode. Not executed in the Drive preparation environment. */
@RunWith(RobolectricTestRunner::class)
class ArtistBulkPermissionRoomTest {
    private lateinit var context: Context
    private lateinit var db: ArtistEnrichmentCacheDatabase
    private val t = 1_800_000_000_000L
    private val first = "1".repeat(64)
    private val second = "2".repeat(64)
    private fun sid() = "bulk-${UUID.randomUUID()}"

    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, ArtistEnrichmentCacheDatabase::class.java).build()
    }
    @After fun teardown() { db.close() }

    private fun session(id: String, count: Int = 2) = ArtistBulkSessionRecord(
        sessionId = id, libraryId = "lib", sourceId = "source", rootEpoch = 3,
        catalogRevision = 5, libraryRevision = 7,
        createdAtEpochMs = t, updatedAtEpochMs = t, receiptCount = count,
    )
    private fun receipts(id: String) = listOf(first, second).mapIndexed { n, fp ->
        ArtistBulkReceiptRecord(
            sessionId = id, fingerprint = fp, libraryId = "lib", artistCredit = "Artist-$n",
            albumIdsJson = "[\"album-$n\"]", mbid = "mbid-$n",
            associationId = "association-$n", updatedAtEpochMs = t,
        )
    }
    private fun permit(id: String, fp: String = first, now: Long = t + 100) =
        ArtistBulkPermissionRecord(
            sessionId = id, fingerprint = fp, permitId = UUID.randomUUID().toString(),
            libraryId = "lib", sourceId = "source", rootEpoch = 3,
            catalogRevision = 5, libraryRevision = 7,
            artistCredit = if (fp == first) "Artist-0" else "Artist-1",
            mbid = if (fp == first) "mbid-0" else "mbid-1",
            associationId = if (fp == first) "association-0" else "association-1",
            issuedAtEpochMs = now, expiresAtEpochMs = now + 5 * 60_000,
            updatedAtEpochMs = now,
        )
    private suspend fun seed(id: String) = db.artistBulkSessionDao().createPrepared(session(id), receipts(id))

    @Test fun exactSingleArtistConsentPersistsButNoDispatchState() = runBlocking {
        val id = sid(); seed(id)
        val request = permit(id)
        assertTrue(db.artistBulkSessionDao().storePermission(request, 0, t + 100))
        assertEquals(request, db.artistBulkSessionDao().getPermission(id, first))
        assertNull(db.artistBulkSessionDao().getPermission(id, second))
        assertEquals(ArtistBulkPermissionRecord.CONSENT_STORED_NOT_DISPATCHED, request.status)
        assertTrue(request.isCurrent(t + 120))
        assertFalse(request.isCurrent(t + 5 * 60_000 + 100))
    }
    @Test fun noImplicitConsentFromPreparedReceipt() = runBlocking {
        val id = sid(); seed(id)
        assertTrue(db.artistBulkSessionDao().listPermissions(id).isEmpty())
        assertNotNull(db.artistBulkSessionDao().readConsistent(id))
    }
    @Test fun rejectsWrongArtistMbidAndRoot() = runBlocking {
        val id = sid(); seed(id)
        val dao = db.artistBulkSessionDao()
        assertFalse(dao.storePermission(permit(id).copy(mbid = "another-mbid"), 0, t + 100))
        assertFalse(dao.storePermission(permit(id).copy(rootEpoch = 4), 0, t + 100))
        assertFalse(dao.storePermission(permit(id).copy(fingerprint = second), 0, t + 100))
        assertEquals(0, dao.listPermissions(id).size)
    }
    @Test fun rejectsTooOldSessionAndTooLongValidity() = runBlocking {
        val id = sid(); seed(id)
        val dao = db.artistBulkSessionDao()
        assertFalse(dao.storePermission(permit(id, now = t + 6 * 60_000), 0, t + 6 * 60_000))
        assertFalse(dao.storePermission(permit(id).copy(expiresAtEpochMs = t + 10 * 60_000), 0, t + 100))
    }
    @Test fun duplicateCannotReplaceOrResurrectRevokedPermission() = runBlocking {
        val id = sid(); seed(id)
        val dao = db.artistBulkSessionDao()
        assertTrue(dao.storePermission(permit(id), 0, t + 100))
        assertFalse(dao.storePermission(permit(id), 0, t + 100))
        assertTrue(dao.revokePermission(id, first, 0, t + 200))
        assertFalse(dao.storePermission(permit(id), 0, t + 300))
        assertEquals(ArtistBulkPermissionRecord.REVOKED_NOT_DISPATCHED,
            dao.getPermission(id, first)?.status)
    }
    @Test fun staleRevocationCannotChangeRevision() = runBlocking {
        val id = sid(); seed(id)
        val dao = db.artistBulkSessionDao()
        assertTrue(dao.storePermission(permit(id), 0, t + 100))
        assertFalse(dao.revokePermission(id, first, 9, t + 101))
        assertTrue(dao.revokePermission(id, first, 0, t + 102))
        assertTrue(dao.revokePermission(id, first, 0, t + 103))
        assertEquals(1L, dao.getPermission(id, first)?.revision)
    }
    @Test fun cancellingSessionRevokesEveryGrantAtomically() = runBlocking {
        val id = sid(); seed(id)
        val dao = db.artistBulkSessionDao()
        assertTrue(dao.storePermission(permit(id, first), 0, t + 100))
        assertTrue(dao.storePermission(permit(id, second), 0, t + 100))
        assertTrue(dao.cancelPrepared(id, 0, t + 200))
        assertTrue(dao.listPermissions(id).all {
            it.status == ArtistBulkPermissionRecord.REVOKED_NOT_DISPATCHED
        })
        assertFalse(dao.storePermission(permit(id), 1, t + 300))
    }
    @Test fun permissionForeignKeyRejectsUnselectedArtist() = runBlocking {
        val id = sid(); seed(id)
        val fake = permit(id).copy(fingerprint = "3".repeat(64))
        try {
            db.artistBulkSessionDao().insertPermission(fake)
            fail("Foreign key must reject a fingerprint absent from this session")
        } catch (_: android.database.sqlite.SQLiteConstraintException) {
            assertNull(db.artistBulkSessionDao().getPermission(id, fake.fingerprint))
        }
    }
    @Test fun failedCancelRollsBackPermissionRevocation() = runBlocking {
        val id = sid(); seed(id)
        val dao = db.artistBulkSessionDao()
        assertTrue(dao.storePermission(permit(id), 0, t + 100))
        db.openHelper.writableDatabase.execSQL(
            "DELETE FROM artist_bulk_receipt WHERE sessionId=? AND fingerprint=?",
            arrayOf(id, second),
        )
        try {
            dao.cancelPrepared(id, 0, t + 150)
            fail("Expected transaction rollback when receipt count is incomplete")
        } catch (_: IllegalStateException) {
            assertEquals(ArtistBulkSessionRecord.PREPARED_NOT_SUBMITTED, dao.getSession(id)?.status)
            assertEquals(ArtistBulkPermissionRecord.CONSENT_STORED_NOT_DISPATCHED,
                dao.getPermission(id, first)?.status)
        }
    }
    @Test fun grantedConsentIsVisibleAfterDatabaseReopenButDoesNotExecute() = runBlocking {
        val name = "artist-bulk-permit-${UUID.randomUUID()}.db"
        val id = sid()
        val a = Room.databaseBuilder(context, ArtistEnrichmentCacheDatabase::class.java, name).build()
        try {
            a.artistBulkSessionDao().createPrepared(session(id), receipts(id))
            assertTrue(a.artistBulkSessionDao().storePermission(permit(id), 0, t + 100))
        } finally { a.close() }
        val b = Room.databaseBuilder(context, ArtistEnrichmentCacheDatabase::class.java, name).build()
        try {
            assertEquals(1, b.artistBulkSessionDao().listPermissions(id).size)
            assertEquals(ArtistBulkPermissionRecord.CONSENT_STORED_NOT_DISPATCHED,
                b.artistBulkSessionDao().getPermission(id, first)?.status)
        } finally {
            b.close(); context.deleteDatabase(name)
        }
    }
}
