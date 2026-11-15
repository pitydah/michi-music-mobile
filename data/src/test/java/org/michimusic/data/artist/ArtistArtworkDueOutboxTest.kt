package org.michimusic.data.artist

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Critical: a future album must not be fetched because a sibling album is due. */
@RunWith(RobolectricTestRunner::class)
class ArtistArtworkDueOutboxTest {
    private lateinit var database: ArtistEnrichmentCacheDatabase
    @Before fun setup() {
        val context: Context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, ArtistEnrichmentCacheDatabase::class.java).build()
    }
    @After fun tearDown() { database.close() }

    @Test fun futureWorkIsNeverConsumedAsDue() = runBlocking {
        val journal = database.artistArtworkCatalogJournalDao()
        val now = 1000L
        journal.putOutbox(ArtistArtworkAutoOutboxRecord(
            libraryId = "local", creditKey = "artist", associationId = "a", albumKey = "due",
            sourceId = "root", updatedAtMs = now - 1,
        ))
        journal.putOutbox(ArtistArtworkAutoOutboxRecord(
            libraryId = "local", creditKey = "artist", associationId = "a", albumKey = "future",
            sourceId = "root", updatedAtMs = now + 86_400_000,
        ))
        assertEquals(listOf("due"), journal.pending("local", "artist", "a", now).map { it.albumKey })
        assertEquals(2, journal.pending("local", "artist", "a", now + 86_400_000).size)
    }

    @Test fun removedAssociationIsTerminalNotForeverPending() = runBlocking {
        val journal = database.artistArtworkCatalogJournalDao()
        journal.putOutbox(ArtistArtworkAutoOutboxRecord(
            libraryId = "local", creditKey = "artist", associationId = "deleted", albumKey = "gone",
            sourceId = "root", updatedAtMs = 1,
        ))
        assertEquals(1, journal.finishMissingAssociation("local", "artist", "deleted", "root", 2))
        assertEquals(emptyList<ArtistArtworkAutoOutboxRecord>(),
            journal.pending("local", "artist", "deleted", 3))
    }
    @Test fun earliestFutureWakeRespectsRootAndLibraryAndIgnoresCompleted() = runBlocking {
        val journal = database.artistArtworkCatalogJournalDao()
        fun row(library: String, source: String, album: String, due: Long, state: String = "PENDING") =
            ArtistArtworkAutoOutboxRecord(
                libraryId = library, creditKey = "artist", associationId = "a",
                albumKey = album, sourceId = source, state = state, updatedAtMs = due,
            )
        journal.putOutbox(row("local", "root", "past", 90))
        journal.putOutbox(row("local", "root", "later", 300))
        journal.putOutbox(row("local", "root", "first", 180))
        journal.putOutbox(row("local", "root", "completed", 120, "READY"))
        journal.putOutbox(row("local", "different-root", "wrong-source", 105))
        journal.putOutbox(row("another-library", "root", "wrong-library", 101))
        assertEquals(180L, journal.nextDeferredDueAtForRoot("local", "root", 100L))
        assertEquals(300L, journal.nextDeferredDueAtForRoot("local", "root", 180L))
        assertEquals(null, journal.nextDeferredDueAtForRoot("local", "root", 300L))
        assertEquals(105L, journal.nextDeferredDueAtForRoot("local", "different-root", 100L))
    }

    @Test fun overOneThousandUnboundRowsCannotHideConfirmedAlbum() = runBlocking {
        val db = database.openHelper.writableDatabase
        // These are the same real Room tables (including foreign keys), not a fake DAO.
        db.execSQL("""INSERT INTO artist_enrichment
            (libraryId, creditKey, albumGroupKey, confirmedMbid, profileJson,
             savedAtMs, staleAfterMs, source)
            VALUES('local', 'artist', 'identity', '05072650-4b05-4e8b-83d0-dba2576e306a',
                   '{}', 1, 1000, 'musicbrainz-manual-selection')""".trimIndent())
        db.execSQL("""INSERT INTO durable_artist_association
            (libraryId, creditKey, associationId, identityAssociationId, createdAtMs, updatedAtMs)
            VALUES('local', 'artist', 'a', 'identity', 1, 1)""".trimIndent())
        db.execSQL("""INSERT INTO artist_album_credit_binding
            (libraryId, albumKey, creditKey, associationId, evidenceType, confirmedAtMs)
            VALUES('local', 'verified', 'artist', 'a', 'USER_CONFIRMED', 1)""".trimIndent())
        db.beginTransaction()
        try {
            for (n in 0 until 1_100) {
                db.execSQL("""INSERT INTO artist_artwork_auto_outbox
                    (libraryId, creditKey, associationId, albumKey, sourceId, state, updatedAtMs)
                    VALUES('local', 'artist', 'a', ?, 'root', 'PENDING', 1)""".trimIndent(),
                    arrayOf("orphan-$n"))
            }
            db.execSQL("""INSERT INTO artist_artwork_auto_outbox
                (libraryId, creditKey, associationId, albumKey, sourceId, state, updatedAtMs)
                VALUES('local', 'artist', 'a', 'verified', 'root', 'PENDING', 2)""".trimIndent())
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        val journal = database.artistArtworkCatalogJournalDao()
        assertEquals(listOf("verified"), journal.pendingForRoot("local", "artist", "a", "root", 20)
            .map { it.albumKey })
        assertEquals(1_100, journal.finishOrphanedPendingForRoot("local", "artist", "a", "root", 30))
        assertEquals(listOf("verified"), journal.pendingForRoot("local", "artist", "a", "root", 40)
            .map { it.albumKey })
    }

    @Test fun disabledOrFinishedOutboxCannotKeepDeferredWakeAlive() = runBlocking {
        val journal = database.artistArtworkCatalogJournalDao()
        journal.putOutbox(ArtistArtworkAutoOutboxRecord(
            libraryId = "local", creditKey = "artist", associationId = "a", albumKey = "future",
            sourceId = "root", updatedAtMs = 450,
        ))
        assertEquals(450L, journal.nextDeferredDueAtForRoot("local", "root", 400L))
        assertEquals(1, journal.finishMissingAssociation("local", "artist", "a", "root", 410L))
        assertEquals(null, journal.nextDeferredDueAtForRoot("local", "root", 400L))
    }
}
