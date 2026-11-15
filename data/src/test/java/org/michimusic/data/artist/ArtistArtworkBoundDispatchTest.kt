package org.michimusic.data.artist

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

/** Contract tests to be run by :data:testDebugUnitTest after Room KSP v11 generation. */
@RunWith(RobolectricTestRunner::class)
class ArtistArtworkBoundDispatchTest {
    private lateinit var database: ArtistEnrichmentCacheDatabase

    @Before fun setup() {
        val context: Context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, ArtistEnrichmentCacheDatabase::class.java).build()
    }

    @After fun tearDown() { database.close() }

    @Test fun orphanAssociationsDoNotHideOneBoundArtist() = runBlocking {
        val sql = database.openHelper.writableDatabase
        sql.execSQL("""INSERT INTO artist_enrichment
            (libraryId, creditKey, albumGroupKey, confirmedMbid, profileJson, savedAtMs, staleAfterMs, source)
            VALUES('lib', 'artist', 'identity', '05072650-4b05-4e8b-83d0-dba2576e306a',
                '{}', 1, 1000, 'musicbrainz-manual-selection')""".trimIndent())
        sql.execSQL("""INSERT INTO durable_artist_association
            (libraryId, creditKey, associationId, identityAssociationId, createdAtMs, updatedAtMs)
            VALUES('lib', 'artist', 'real', 'identity', 1, 1)""".trimIndent())
        sql.execSQL("""INSERT INTO artist_album_credit_binding
            (libraryId, albumKey, creditKey, associationId, evidenceType, confirmedAtMs)
            VALUES('lib', 'verified', 'artist', 'real', 'USER_CONFIRMED', 1)""".trimIndent())
        sql.beginTransaction()
        try {
            for (n in 0 until 1_200) {
                sql.execSQL("""INSERT INTO artist_artwork_auto_outbox
                    (libraryId, creditKey, associationId, albumKey, sourceId, state, updatedAtMs)
                    VALUES('lib', ?, ?, ?, 'root', 'PENDING', 1)""".trimIndent(),
                    arrayOf("orphan-$n", "old-$n", "missing-$n"))
            }
            sql.execSQL("""INSERT INTO artist_artwork_auto_outbox
                (libraryId, creditKey, associationId, albumKey, sourceId, state, updatedAtMs)
                VALUES('lib', 'artist', 'real', 'verified', 'root', 'PENDING', 2)""".trimIndent())
            sql.setTransactionSuccessful()
        } finally { sql.endTransaction() }
        val dao = database.artistArtworkCatalogJournalDao()
        val due = dao.boundPendingDispatchForRoot("lib", "root", 128, 10)
        assertEquals(listOf("real"), due.map { it.associationId })
        assertEquals(listOf("real"), dao.boundManualRetryCandidates("lib", "root", 16)
            .map { it.associationId })
        assertEquals(null, dao.nextBoundDeferredDueAtForRoot("lib", "root", 10))
        assertTrue(dao.boundPendingDispatchForRoot("lib", "former", 128, 10).isEmpty())
    }

    @Test fun futureUnboundRowsDoNotScheduleNetworkWake() = runBlocking {
        val dao = database.artistArtworkCatalogJournalDao()
        dao.putOutbox(ArtistArtworkAutoOutboxRecord(
            "lib", "artist", "missing", "album", "root", updatedAtMs = 500,
        ))
        assertEquals(null, dao.nextBoundDeferredDueAtForRoot("lib", "root", 100))
        assertTrue(dao.boundPendingDispatchForRoot("lib", "root", 128, 1000).isEmpty())
    }
}
