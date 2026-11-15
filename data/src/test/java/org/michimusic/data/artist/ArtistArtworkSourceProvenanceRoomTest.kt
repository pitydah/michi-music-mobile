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

/** K33 Android/Room contract tests: RUN ONLY AFTER actual KSP v11 is generated. */
@RunWith(RobolectricTestRunner::class)
class ArtistArtworkSourceProvenanceRoomTest {
    private lateinit var db: ArtistEnrichmentCacheDatabase

    @Before fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, ArtistEnrichmentCacheDatabase::class.java).build()
    }

    @After fun close() = db.close()

    private fun bind(album: String, credit: String, assoc: String, atMs: Long) {
        // The existing domain DB owns the binding; this raw fixture does not exercise
        // application confirmation. Source proof is tested separately from that authority.
        db.openHelper.writableDatabase.execSQL("""INSERT OR IGNORE INTO artist_enrichment
            (libraryId,creditKey,albumGroupKey,confirmedMbid,profileJson,savedAtMs,staleAfterMs,source)
            VALUES('lib',?,'identity','05072650-4b05-4e8b-83d0-dba2576e306a',
                '{}',1,1000,'musicbrainz-manual-selection')""".trimIndent(), arrayOf(credit))
        db.openHelper.writableDatabase.execSQL("""INSERT OR IGNORE INTO durable_artist_association
            (libraryId,creditKey,associationId,identityAssociationId,createdAtMs,updatedAtMs)
            VALUES('lib',? ,? ,'identity',1,1)""".trimIndent(),arrayOf(credit,assoc))
        db.openHelper.writableDatabase.execSQL("""INSERT INTO artist_album_credit_binding
            (libraryId,albumKey,creditKey,associationId,evidenceType,confirmedAtMs)
            VALUES('lib',?,?,?,'USER_EXPLICIT_BINDING',?)""".trimIndent(),
            arrayOf(album, credit, assoc, atMs))
    }

    @Test fun sameAlbumInTwoSourcesCannotUseAnotherSourcesConfirmedBindingProof() = runBlocking {
        val dao = db.artistArtworkCatalogJournalDao()
        dao.insertSourceCatalogCredits(listOf(
            ArtistArtworkSourceCatalogCreditRecord("lib", "root-A", "album", "artist", 100),
        ))
        bind("album", "artist", "assoc", 120)
        dao.captureBindingSourceProofs("lib", "artist", "assoc", "root-A", 100, 130)
        assertEquals(1, dao.countVerifiedSourceBinding("lib", "root-A", "album", "artist", "assoc"))
        dao.insertSourceCatalogCredits(listOf(
            ArtistArtworkSourceCatalogCreditRecord("lib", "root-B", "album", "artist", 140),
        ))
        assertEquals(0, dao.countVerifiedSourceBinding("lib", "root-B", "album", "artist", "assoc"))
        dao.putOutbox(ArtistArtworkAutoOutboxRecord("lib", "artist", "assoc", "album", "root-A", updatedAtMs = 150))
        dao.putOutbox(ArtistArtworkAutoOutboxRecord("lib", "artist", "assoc", "album", "root-B", updatedAtMs = 150))
        assertEquals(listOf("assoc"), dao.provenPendingDispatchForRoot("lib", "root-A", 10, 200)
            .map { it.associationId })
        assertTrue(dao.provenPendingDispatchForRoot("lib", "root-B", 10, 200).isEmpty())
        assertEquals(1, dao.holdUnprovenEventsForRoot("lib", "root-B", 201))
    }

    @Test fun historicBindingNeedsExplicitNewSourceEvidence() = runBlocking {
        val dao = db.artistArtworkCatalogJournalDao()
        bind("album", "artist", "assoc", 10)
        dao.insertSourceCatalogCredits(listOf(
            ArtistArtworkSourceCatalogCreditRecord("lib", "root", "album", "artist", 100),
        ))
        dao.captureBindingSourceProofs("lib", "artist", "assoc", "root", 100, 120)
        assertEquals(0, dao.countVerifiedSourceBinding("lib", "root", "album", "artist", "assoc"))
        dao.putOutbox(ArtistArtworkAutoOutboxRecord("lib", "artist", "assoc", "album", "root", updatedAtMs = 105))
        assertTrue(dao.provenPendingDispatchForRoot("lib", "root", 10, 110).isEmpty())
        assertEquals(1, dao.holdUnprovenEventsForRoot("lib", "root", 110))
        assertEquals(0, dao.reviveProvenSourceEvents("lib", "root", "artist", "assoc", 112))
    }

    @Test fun changedBindingConfirmationInvalidatesOldProofAndBackfill() = runBlocking {
        val dao = db.artistArtworkCatalogJournalDao()
        dao.insertSourceCatalogCredits(listOf(
            ArtistArtworkSourceCatalogCreditRecord("lib", "root", "album", "artist", 100),
        ))
        bind("album", "artist", "assoc", 120)
        dao.captureBindingSourceProofs("lib", "artist", "assoc", "root", 100, 130)
        assertEquals(1, dao.provenBindingsAfter("lib", "root", "", "", 64).size)
        db.openHelper.writableDatabase.execSQL("""UPDATE artist_album_credit_binding
            SET confirmedAtMs=200 WHERE libraryId='lib' AND albumKey='album' AND creditKey='artist'""")
        assertEquals(0, dao.countVerifiedSourceBinding("lib", "root", "album", "artist", "assoc"))
        assertTrue(dao.provenBindingsAfter("lib", "root", "", "", 64).isEmpty())
    }
}
