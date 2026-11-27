package org.michimusic.data.artist

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import android.content.Context
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** D36: written for OpenCode/Gradle; not executed in the Drive preparation environment. */
@RunWith(RobolectricTestRunner::class)
class ArtistExactOccurrenceIdentityRoomTest {
    private lateinit var db: ArtistEnrichmentCacheDatabase
    private val a = "11111111-1111-4111-8111-111111111111"
    private val b = "22222222-2222-4222-8222-222222222222"

    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            ArtistEnrichmentCacheDatabase::class.java,
        ).build()
    }
    @After fun cleanup() { db.close() }

    private suspend fun bind(library: String, album: String, association: String, mbid: String) {
        val dao = db.artistEnrichmentDao()
        dao.put(ArtistEnrichmentRecord(
            libraryId = library, creditKey = "alex", albumGroupKey = association,
            confirmedMbid = mbid, profileJson = "{}", savedAtMs = 1L,
            staleAfterMs = Long.MAX_VALUE, source = "musicbrainz-manual-selection-pending",
            biographySourceUrl = null, portraitSourceUrl = null, portraitLicense = null,
            portraitLicenseUrl = null, portraitAttribution = null,
        ))
        dao.putDurableAssociation(DurableArtistAssociationRecord(
            libraryId = library, creditKey = "alex", associationId = association,
            identityAssociationId = association, createdAtMs = 1L, updatedAtMs = 1L,
        ))
        dao.putAlbumCreditBinding(ArtistAlbumCreditBindingRecord(
            libraryId = library, albumKey = album, creditKey = "alex", associationId = association,
            evidenceType = "USER_EXPLICIT_BINDING", confirmedAtMs = 1L,
        ))
    }

    @Test fun sameLiteralCreditMayHaveDifferentConfirmedMbidsByAlbum() = runBlocking {
        bind("lib", "album-1", "association-1", a)
        bind("lib", "album-2", "association-2", b)
        val rows = db.artistEnrichmentDao().getConfirmedAlbumIdentityRows(
            "lib", listOf("album-1", "album-2"), listOf("alex"),
        )
        assertEquals(listOf(a, b), rows.map { it.confirmedMbid })
        assertEquals(listOf("album-1", "album-2"), rows.map { it.albumKey })
    }

    @Test fun noForeignLibraryIdentityLeaksIntoExactRead() = runBlocking {
        bind("one", "album", "association-one", a)
        bind("two", "album", "association-two", b)
        assertEquals(listOf(a), db.artistEnrichmentDao().getConfirmedAlbumIdentityRows(
            "one", listOf("album"), listOf("alex"),
        ).map { it.confirmedMbid })
    }

    @Test fun staleOrDeletedAssociationCannotAuthorizeIdentity() = runBlocking {
        bind("lib", "album", "association", a)
        db.artistEnrichmentDao().deleteDurableAssociation("lib", "alex", "association")
        assertTrue(db.artistEnrichmentDao().getConfirmedAlbumIdentityRows(
            "lib", listOf("album"), listOf("alex"),
        ).isEmpty())
    }

    @Test fun rawTagsRemainDiagnosticAndDoNotCreateConfirmedBinding() = runBlocking {
        db.artistEnrichmentDao().insertTrackEvidence(listOf(
            ArtistTrackEvidenceRecord("lib", "local_1", "ARTIST_MBID", 0, a, 5L),
            ArtistTrackEvidenceRecord("lib", "local_1", "ALBUM_ARTIST_MBID", 0, b, 5L),
        ))
        val diagnostic = db.artistEnrichmentDao().getUntrustedArtistTagMbidsForTracks(
            "lib", listOf("local_1"),
        )
        assertEquals(listOf("ARTIST_MBID"), diagnostic.map { it.field })
        assertTrue(db.artistEnrichmentDao().getConfirmedAlbumIdentityRows(
            "lib", listOf("album"), listOf("alex"),
        ).isEmpty())
    }
    @Test fun boundedBindingReadPreservesSentinelForOverflow() = runBlocking {
        bind("lib", "a1", "association-1", a)
        bind("lib", "a2", "association-2", b)
        val dao = db.artistEnrichmentDao()
        assertEquals(1, dao.getConfirmedAlbumIdentityRows("lib", listOf("a1", "a2"), listOf("alex"), 1).size)
        assertEquals(2, dao.getConfirmedAlbumIdentityRows("lib", listOf("a1", "a2"), listOf("alex"), 2).size)
    }
    @Test fun boundedTagReadDoesNotMixLibrariesOrFields() = runBlocking {
        val dao = db.artistEnrichmentDao()
        dao.insertTrackEvidence(listOf(
            ArtistTrackEvidenceRecord("lib", "local_1", "ARTIST_MBID", 0, a, 5L),
            ArtistTrackEvidenceRecord("lib", "local_1", "ARTIST_MBID", 1, b, 5L),
            ArtistTrackEvidenceRecord("other", "local_1", "ARTIST_MBID", 0, b, 5L),
            ArtistTrackEvidenceRecord("lib", "local_1", "ALBUM_ARTIST_MBID", 0, b, 5L),
        ))
        assertEquals(1, dao.getUntrustedArtistTagMbidsForTracks("lib", listOf("local_1"), 1).size)
        assertEquals(listOf(a,b), dao.getUntrustedArtistTagMbidsForTracks("lib", listOf("local_1"), 3).map { it.value })
    }
}
