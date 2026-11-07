package org.michimusic.mobile.artist

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Before
import org.junit.runner.RunWith
import org.michimusic.data.artist.ArtistEnrichmentCacheDatabase
import org.michimusic.data.artist.ArtistEnrichmentRecord
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// Robolectric 4.12.2 only ships up to SDK 34, while the app targets 35. Every
// other Robolectric suite in this repository pins SDK 33 for the same reason.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = android.app.Application::class)
class ArtistEnrichmentPersistenceTest {
    private lateinit var database: ArtistEnrichmentCacheDatabase
    private var now = 1_000_000L
    private lateinit var store: RoomArtistEnrichmentSelectionStore
    private val mbid = "bd6d4f63-5cd0-4d78-a292-167997794c21"
    private val albums = listOf("album-b", "album-a")
    private val credit = "Patti Austin & James Ingram"

    @Before fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(), ArtistEnrichmentCacheDatabase::class.java
        ).allowMainThreadQueries().build()
        store = RoomArtistEnrichmentSelectionStore(database) { now }
    }

    @After fun tearDown() { database.close() }

    private fun profile() = ArtistExternalProfile(mbid, credit, "", "Person", "US")

    private suspend fun putHistorical(
        albumGroupKey: String,
        membershipFingerprint: String,
        identity: String,
        albumIdsJson: String,
    ) {
        val creditKey = requireNotNull(scopedArtistCreditKey("lib-a", credit, albums)).first
        val saved = profile().copy(mbid = identity, name = "Historical $identity")
        database.artistEnrichmentDao().put(
            ArtistEnrichmentRecord(
                libraryId = "lib-a",
                creditKey = creditKey,
                albumGroupKey = albumGroupKey,
                confirmedMbid = identity,
                profileJson = Json.encodeToString(saved),
                savedAtMs = 1L,
                staleAfterMs = Long.MAX_VALUE,
                source = "historical-test",
                biographySourceUrl = null,
                portraitSourceUrl = null,
                portraitLicense = null,
                portraitLicenseUrl = null,
                portraitAttribution = null,
                albumIdsJson = albumIdsJson,
                membershipFingerprint = membershipFingerprint,
            ),
        )
    }

    @Test fun oneLibraryNeverAliasesAnother() = runBlocking {
        store.save("lib-a", credit, albums, profile())
        assertEquals(mbid, store.load("lib-a", credit, albums)?.profile?.mbid)
        assertNull(store.load("lib-b", credit, albums))
        val recovered = store.load("lib-a", credit, listOf("album-a"))
        assertEquals(mbid, recovered?.profile?.mbid)
        assertTrue(recovered?.needsConfirmation == true)
        assertNull(store.load("lib-a", credit, listOf("album-z")))
        assertEquals(mbid, store.load("lib-a", credit, albums.reversed())?.profile?.mbid)
    }

    @Test fun ambiguousHistoricalAlbumOverlapIsReportedWithoutDeletingRows() = runBlocking {
        val otherMbid = "05072650-4b05-4e8b-83d0-dba2576e306a"
        store.save("lib-a", credit, listOf("album-a"), profile())
        store.save(
            "lib-a", credit, listOf("album-b"),
            profile().copy(mbid = otherMbid, name = "Patti Austin"),
        )
        val failure = runCatching {
            store.load("lib-a", credit, listOf("album-a", "album-b"))
        }.exceptionOrNull()
        assertTrue(failure is ArtistAssociationAmbiguousException)
        assertEquals(
            2,
            database.artistEnrichmentDao().getAllForCredit(
                "lib-a",
                requireNotNull(scopedArtistCreditKey("lib-a", credit, albums)).first,
            ).size,
        )
    }

    @Test fun duplicateMembershipWithDifferentMbidsFailsClosedAndPreservesBothRows() = runBlocking {
        val otherMbid = "05072650-4b05-4e8b-83d0-dba2576e306a"
        val current = requireNotNull(scopedArtistCreditKey("lib-a", credit, albums)).second
        putHistorical("historical-a", current, mbid, "[\"album-a\"]")
        putHistorical("historical-b", current, otherMbid, "[\"album-b\"]")

        val failure = runCatching { store.load("lib-a", credit, albums) }.exceptionOrNull()

        assertNotNull("Competing membership identities must never use LIMIT 1", failure)
        assertTrue(failure?.message.orEmpty().contains("ambigua", ignoreCase = true))
        assertEquals(
            2,
            database.artistEnrichmentDao()
                .getAllForCredit("lib-a", requireNotNull(scopedArtistCreditKey("lib-a", credit, albums)).first)
                .size,
        )
    }

    @Test fun exactAssociationCannotOverrideAConflictingMembershipIdentity() = runBlocking {
        val otherMbid = "05072650-4b05-4e8b-83d0-dba2576e306a"
        val current = requireNotNull(scopedArtistCreditKey("lib-a", credit, albums)).second
        putHistorical(current, "legacy-membership", mbid, "[\"album-a\"]")
        putHistorical("historical-other", current, otherMbid, "[\"album-b\"]")

        val failure = runCatching { store.load("lib-a", credit, albums) }.exceptionOrNull()

        assertNotNull("Exact and membership identities conflict; neither may win", failure)
        assertTrue(failure?.message.orEmpty().contains("ambigua", ignoreCase = true))
    }

    @Test fun aDifferentMbidCannotSilentlyReplaceAnExistingAssociation() = runBlocking {
        val otherMbid = "05072650-4b05-4e8b-83d0-dba2576e306a"
        store.save("lib-a", credit, albums, profile())

        val failure = runCatching {
            store.save(
                "lib-a",
                credit,
                albums,
                profile().copy(mbid = otherMbid, name = "Another Artist"),
            )
        }.exceptionOrNull()

        assertNotNull("Replacing a confirmed MBID requires explicit unlink/clear", failure)
        assertEquals(mbid, store.load("lib-a", credit, albums)?.profile?.mbid)
    }

    @Test fun expiryMarksFactsStaleButPreservesOfflineDetails() = runBlocking {
        store.save("lib-a", credit, albums, profile())
        assertFalse(requireNotNull(store.load("lib-a", credit, albums)).stale)
        now += 8L * 24 * 60 * 60 * 1000
        val cached = store.load("lib-a", credit, albums)
        assertNotNull(cached)
        assertTrue(requireNotNull(cached).stale)
        assertEquals(mbid, cached.profile.mbid)
    }

    @Test fun clearIsScopedAndDoesNotRemoveOtherLibrary() = runBlocking {
        store.save("lib-a", credit, albums, profile())
        store.save("lib-b", credit, albums, profile())
        store.clear("lib-a", credit, albums)
        assertNull(store.load("lib-a", credit, albums))
        assertNotNull(store.load("lib-b", credit, albums))
    }

    @Test fun corruptJsonIsReportedAsACacheFailureInsteadOfRenderedAsConfirmed() = runBlocking {
        val keys = requireNotNull(scopedArtistCreditKey("lib-a", credit, albums))
        database.artistEnrichmentDao().put(org.michimusic.data.artist.ArtistEnrichmentRecord(
            "lib-a", keys.first, keys.second, mbid, "{not-json", 1, 2, "test", null, null, null, null, null
        ))
        val failure = try {
            store.load("lib-a", credit, albums)
            null
        } catch (e: IllegalStateException) {
            e
        }
        assertNotNull(failure)
        assertTrue(requireNotNull(failure).message.orEmpty().contains("corrupto"))
    }

    @Test fun optionalProviderOutcomesSurviveTheRoundTrip() = runBlocking {
        val saved = profile().copy(
            biography = "Banda australiana de rock.",
            biographySourceUrl = "https://es.wikipedia.org/wiki/Midnight_Oil",
            biographyOutcome = ArtistMetadataOutcome.SUCCESS,
            portraitOutcome = ArtistMetadataOutcome.NO_ALLOWED_LICENSE,
            wikidataQid = "Q123",
        )
        store.save("lib-a", credit, albums, saved)
        val loaded = requireNotNull(store.load("lib-a", credit, albums)).profile
        assertEquals(saved, loaded)
        assertEquals(ArtistMetadataOutcome.SUCCESS, loaded.biographyOutcome)
        assertEquals(ArtistMetadataOutcome.NO_ALLOWED_LICENSE, loaded.portraitOutcome)
        assertEquals("Q123", loaded.wikidataQid)
    }

    @Test fun noBindingInAllScopeAndCreditNeverSplitsOnAmpersand() {
        assertNull(scopedArtistCreditKey(null, credit, albums))
        assertNull(scopedArtistCreditKey("SCOPE_ALL", credit, albums))
        assertEquals("patti austin & james ingram", scopedArtistCreditKey("lib-a", credit, albums)?.first)
        assertNull(scopedArtistCreditKey("lib-a", credit, emptyList()))
    }
}
