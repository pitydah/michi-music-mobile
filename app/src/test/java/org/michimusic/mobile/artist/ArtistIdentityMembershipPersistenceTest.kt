package org.michimusic.mobile.artist

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.michimusic.data.artist.ArtistEnrichmentCacheDatabase
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = android.app.Application::class)
class ArtistIdentityMembershipPersistenceTest {
    private lateinit var database: ArtistEnrichmentCacheDatabase
    private lateinit var store: RoomArtistEnrichmentSelectionStore
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val credit = "Midnight Oil"
    private val mbidX = "05072650-4b05-4e8b-83d0-dba2576e306a"
    private val mbidY = "bd6d4f63-5cd0-4d78-a292-167997794c21"

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(context, ArtistEnrichmentCacheDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        store = RoomArtistEnrichmentSelectionStore(database) { 100L }
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun explicitlyBoundAlbumKeepsIdentityAfterOriginalAlbumDisappears() = runBlocking {
        val confirmed = store.confirmNewIdentity(
            libraryId = "lib-a",
            credit = credit,
            albumIds = listOf("album-a"),
            mbid = mbidX,
        )
        val associationId = requireNotNull(confirmed.associationGroupKey)

        val expanded = requireNotNull(store.load("lib-a", credit, listOf("album-a", "album-b")))
        assertTrue("B is visible but not owned yet", expanded.needsConfirmation)

        store.bindAlbumsToAssociation(
            libraryId = "lib-a",
            credit = credit,
            albumIds = listOf("album-b"),
            associationId = associationId,
            expectedMbid = mbidX,
            evidenceType = ArtistMembershipEvidenceType.USER_EXPLICIT_BINDING,
        )

        val onlyB = requireNotNull(store.load("lib-a", credit, listOf("album-b")))
        assertEquals(mbidX, onlyB.profile.mbid)
        assertEquals(associationId, onlyB.associationGroupKey)
        assertFalse(onlyB.needsConfirmation)
    }

    @Test
    fun unboundAlbumNeverInheritsIdentityFromNameOrPriorContext() = runBlocking {
        store.confirmNewIdentity("lib-a", credit, listOf("album-a"), mbidX)

        assertNull(store.load("lib-a", credit, listOf("album-b")))
        assertEquals(mbidX, store.load("lib-a", credit, listOf("album-a"))?.profile?.mbid)
    }

    @Test
    fun homonymBindingsOwnSubsetsAndCombinedResolutionNeverTransfersOwnership() = runBlocking {
        val associationX = requireNotNull(
            store.confirmNewIdentity("lib-a", credit, listOf("album-a"), mbidX).associationGroupKey,
        )
        val associationY = requireNotNull(
            store.confirmNewIdentity("lib-a", credit, listOf("album-b"), mbidY).associationGroupKey,
        )
        assertNotEquals(associationX, associationY)

        assertEquals(mbidX, store.load("lib-a", credit, listOf("album-a"))?.profile?.mbid)
        assertEquals(mbidY, store.load("lib-a", credit, listOf("album-b"))?.profile?.mbid)
        assertTrue(
            runCatching { store.load("lib-a", credit, listOf("album-a", "album-b")) }
                .exceptionOrNull() is ArtistAssociationAmbiguousException,
        )

        store.resolveAssociation(
            "lib-a", credit, listOf("album-a", "album-b"), associationX, mbidX,
        )

        assertEquals(mbidX, store.load("lib-a", credit, listOf("album-a", "album-b"))?.profile?.mbid)
        assertEquals(mbidX, store.load("lib-a", credit, listOf("album-a"))?.profile?.mbid)
        assertEquals(mbidY, store.load("lib-a", credit, listOf("album-b"))?.profile?.mbid)
        assertNull(store.load("lib-a", credit, listOf("album-c")))
    }

    @Test
    fun profileRefreshCannotCreateMembershipForANewAlbum() = runBlocking {
        val confirmed = store.confirmNewIdentity("lib-a", credit, listOf("album-a"), mbidX)
        val associationId = requireNotNull(confirmed.associationGroupKey)

        store.refreshProfile(
            libraryId = "lib-a",
            credit = credit,
            associationId = associationId,
            profile = ArtistExternalProfile(
                mbid = mbidX,
                name = credit,
                biography = "Banda australiana de rock.",
                biographySourceUrl = "https://es.wikipedia.org/wiki/Midnight_Oil",
                biographyOutcome = ArtistMetadataOutcome.SUCCESS,
            ),
        )

        assertNull(store.load("lib-a", credit, listOf("album-b")))
        assertEquals(
            "Banda australiana de rock.",
            store.load("lib-a", credit, listOf("album-a"))?.profile?.biography,
        )
    }

    @Test
    fun sameMbidCanOwnTwoIndependentAssociations() = runBlocking {
        val first = requireNotNull(
            store.confirmNewIdentity("lib-a", credit, listOf("album-a"), mbidX).associationGroupKey,
        )
        val second = requireNotNull(
            store.confirmNewIdentity("lib-a", credit, listOf("album-b"), mbidX).associationGroupKey,
        )

        assertNotEquals(first, second)
        assertTrue(
            runCatching { store.load("lib-a", credit, listOf("album-a", "album-b")) }
                .exceptionOrNull() is ArtistAssociationAmbiguousException,
        )
    }

    @Test
    fun changedLocalAlbumIdNeedsExplicitEvidenceBeforeItRecoversIdentity() = runBlocking {
        val confirmed = store.confirmNewIdentity("lib-a", credit, listOf("media-store-41"), mbidX)
        val associationId = requireNotNull(confirmed.associationGroupKey)

        assertNull(store.load("lib-a", credit, listOf("media-store-99")))

        store.bindAlbumsToAssociation(
            libraryId = "lib-a",
            credit = credit,
            albumIds = listOf("media-store-99"),
            associationId = associationId,
            expectedMbid = mbidX,
            evidenceType = ArtistMembershipEvidenceType.STABLE_TRACK_IDENTITY,
        )

        val reimported = requireNotNull(store.load("lib-a", credit, listOf("media-store-99")))
        assertEquals(mbidX, reimported.profile.mbid)
        assertEquals(associationId, reimported.associationGroupKey)
        assertFalse(reimported.needsConfirmation)
    }

    @Test
    fun oneAlbumCanBindMultipleArtistCreditsWithoutGlobalAlbumOwnership() = runBlocking {
        val sharedAlbum = listOf("compilation-7")
        store.confirmNewIdentity("lib-a", "Artist One", sharedAlbum, mbidX)
        store.confirmNewIdentity("lib-a", "Artist Two feat. Guest", sharedAlbum, mbidY)

        assertEquals(
            mbidX,
            store.load("lib-a", "Artist One", sharedAlbum)?.profile?.mbid,
        )
        assertEquals(
            mbidY,
            store.load("lib-a", "Artist Two feat. Guest", sharedAlbum)?.profile?.mbid,
        )
        assertEquals(
            1,
            database.artistEnrichmentDao()
                .getAlbumCreditBindings("lib-a", "artist one", sharedAlbum).size,
        )
        assertEquals(
            1,
            database.artistEnrichmentDao()
                .getAlbumCreditBindings("lib-a", "artist two feat. guest", sharedAlbum).size,
        )
    }

    @Test
    fun replacingAWrongBindingIsExplicitAndInvalidatesTheAffectedContextDecision() = runBlocking {
        val associationX = requireNotNull(
            store.confirmNewIdentity("lib-a", credit, listOf("album-a"), mbidX).associationGroupKey,
        )
        val associationY = requireNotNull(
            store.confirmNewIdentity("lib-a", credit, listOf("album-b"), mbidY).associationGroupKey,
        )
        store.resolveAssociation(
            "lib-a", credit, listOf("album-a", "album-b"), associationY, mbidY,
        )

        store.replaceAlbumBindings(
            libraryId = "lib-a",
            credit = credit,
            albumIds = listOf("album-b"),
            expectedCurrentAssociationId = associationY,
            replacementAssociationId = associationX,
            replacementMbid = mbidX,
        )

        assertEquals(mbidX, store.load("lib-a", credit, listOf("album-b"))?.profile?.mbid)
        assertEquals(
            mbidX,
            store.load("lib-a", credit, listOf("album-a", "album-b"))?.profile?.mbid,
        )
        assertEquals(
            null,
            database.artistEnrichmentDao().getAssociationResolution(
                "lib-a",
                credit.lowercase(),
                requireNotNull(scopedArtistCreditKey("lib-a", credit, listOf("album-a", "album-b"))).second,
            ),
        )
        assertEquals(
            mbidY,
            database.artistEnrichmentDao().get("lib-a", credit.lowercase(), associationY)?.confirmedMbid,
        )
        val evidence = database.artistEnrichmentDao()
            .getMembershipEvidence("lib-a", "album-b", credit.lowercase())
        assertEquals(
            setOf(associationX),
            evidence.filter { it.status == "CONFIRMED" }.map { it.associationId }.toSet(),
        )
        assertEquals(
            setOf(associationY),
            evidence.filter { it.status == "SUPERSEDED" }.map { it.associationId }.toSet(),
        )
    }
}
