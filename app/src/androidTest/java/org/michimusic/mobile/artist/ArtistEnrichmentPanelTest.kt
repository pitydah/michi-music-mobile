package org.michimusic.mobile.artist

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.michimusic.mobile.artist.artwork.ArtistArtworkSelection
import org.michimusic.mobile.artist.artwork.ArtistArtworkStore
import org.michimusic.mobile.artist.artwork.ArtworkFocus
import org.michimusic.mobile.artist.artwork.ArtworkSlot
import org.michimusic.data.artist.ArtistEnrichmentCacheDatabase
import org.michimusic.data.artist.ArtistEnrichmentRecord

@RunWith(AndroidJUnit4::class)
class ArtistEnrichmentPanelTest {
    @get:Rule
    val compose = createComposeRule()

    private val mbid = "bd6d4f63-5cd0-4d78-a292-167997794c21"

    @Test
    fun singleSameNameCandidateIsNotConfirmedWithoutExplicitSelection() {
        val gateway = FakeGateway(
            candidates = listOf(
                ArtistLookupCandidate(
                    mbid = mbid,
                    name = "Midnight Oil",
                    disambiguation = "Australian rock band",
                    type = "Group",
                    area = "Australia",
                ),
            ),
            profile = profile(),
        )
        val store = FakeProfileStore()
        val artworkStore = FakeArtworkStore()

        compose.setContent {
            ArtistEnrichmentPanel(
                artistCredit = "Midnight Oil",
                gateway = gateway,
                libraryId = "library-a",
                albumIds = listOf("album-a"),
                selectionStore = store,
                artworkStore = artworkStore,
            )
        }

        compose.waitUntil(5_000) { gateway.searches.get() == 1 }
        compose.onNodeWithTag("artist_candidate_$mbid").assertExists()
        assertEquals("No name-based confirmation is allowed", 0, gateway.refreshes.get())
        assertTrue(compose.onAllNodesWithText("MBID confirmado por selección", substring = true).fetchSemanticsNodes().isEmpty())

        compose.onNodeWithTag("artist_candidate_$mbid").performClick()

        compose.waitUntil(5_000) { gateway.refreshes.get() == 1 }
        compose.onNodeWithTag("artist_candidate_$mbid").assertDoesNotExist()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("MBID confirmado por selección", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun cachedProfileStaysVisibleWhileRefreshRuns() {
        val refreshGate = CompletableDeferred<ArtistExternalProfile>()
        val gateway = FakeGateway(
            candidates = emptyList(),
            profile = profile().copy(biography = null, biographySourceUrl = null),
            refreshGate = refreshGate,
        )
        val store = FakeProfileStore(
            cached = PersistedArtistProfile(
                profile = profile().copy(biography = null, biographySourceUrl = null),
                stale = false,
            ),
        )
        val artworkStore = FakeArtworkStore()

        compose.setContent {
            ArtistEnrichmentPanel(
                artistCredit = "Midnight Oil",
                gateway = gateway,
                libraryId = "library-a",
                albumIds = listOf("album-a"),
                selectionStore = store,
                artworkStore = artworkStore,
            )
        }

        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("MBID confirmado por selección", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("artist_enrichment_refresh").performScrollTo().performClick()
        compose.waitUntil(5_000) { gateway.refreshes.get() == 1 }
        compose.onNodeWithText("MBID confirmado por selección", substring = true).assertExists()
        compose.onNodeWithTag("artist_enrichment_clear").assertExists()
        // The previous verified profile is still rendered while the network refresh runs.
        compose.onNodeWithText("Midnight Oil").assertExists()

        refreshGate.complete(profile())
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("Ficha actualizada sin fotografías verificables.")
                .fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun clearingCancelsRefreshAndSuppressesAutomaticRediscovery() {
        val refreshGate = CompletableDeferred<ArtistExternalProfile>()
        val gateway = FakeGateway(
            candidates = listOf(
                ArtistLookupCandidate(mbid, "Midnight Oil", "", "Group", "Australia"),
            ),
            profile = profile().copy(biography = null, biographySourceUrl = null),
            refreshGate = refreshGate,
        )
        val store = FakeProfileStore(
            cached = PersistedArtistProfile(
                profile = profile().copy(biography = null, biographySourceUrl = null),
                stale = true,
            ),
        )
        val artworkStore = FakeArtworkStore()

        compose.setContent {
            ArtistEnrichmentPanel(
                artistCredit = "Midnight Oil",
                gateway = gateway,
                libraryId = "library-a",
                albumIds = listOf("album-a"),
                selectionStore = store,
                artworkStore = artworkStore,
            )
        }

        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("MBID confirmado por selección", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("artist_enrichment_refresh").performScrollTo().performClick()
        compose.waitUntil(5_000) { gateway.refreshes.get() == 1 }
        compose.onNodeWithTag("artist_enrichment_clear").performScrollTo().performClick()

        compose.waitUntil(5_000) { store.clears.get() == 1 }
        compose.onNodeWithText("Enriquecimiento borrado.").assertExists()
        assertEquals("Late provider responses must not start a new search", 0, gateway.searches.get())
        assertEquals("No automatic re-confirmation after clear", 1, gateway.refreshes.get())
    }

    @Test
    fun failedLookupShowsOneGlobalStateWithoutRetryButton() {
        val gateway = FakeGateway(candidates = emptyList(), profile = profile(), failSearch = true)
        val store = FakeProfileStore()
        val artworkStore = FakeArtworkStore()

        compose.setContent {
            ArtistEnrichmentPanel(
                artistCredit = "Midnight Oil",
                gateway = gateway,
                libraryId = "library-a",
                albumIds = listOf("album-a"),
                selectionStore = store,
                artworkStore = artworkStore,
            )
        }

        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("No fue posible consultar MusicBrainz.")
                .fetchSemanticsNodes().isNotEmpty()
        }
        assertTrue(compose.onAllNodesWithTag("artist_enrichment_search").fetchSemanticsNodes().isEmpty())
        assertTrue(compose.onAllNodesWithText("Buscar en MusicBrainz").fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun recompositionNeverRepeatsTheAutomaticLookup() {
        val gateway = FakeGateway(
            candidates = listOf(
                ArtistLookupCandidate(mbid, "Midnight Oil", "", "Group", "Australia"),
                ArtistLookupCandidate(
                    "aaa436da-b331-4e58-a0fd-3d64d090c120",
                    "Midnight Oil Tribute",
                    "",
                    "Group",
                    "Australia",
                ),
            ),
            profile = profile(),
        )
        var tick by mutableStateOf(0)
        val store = FakeProfileStore()
        val artworkStore = FakeArtworkStore()

        compose.setContent {
            tick.hashCode()
            ArtistEnrichmentPanel(
                artistCredit = "Midnight Oil",
                gateway = gateway,
                libraryId = "library-a",
                albumIds = listOf("album-a"),
                selectionStore = store,
                artworkStore = artworkStore,
            )
        }

        compose.waitUntil(5_000) { gateway.searches.get() == 1 }
        compose.runOnIdle { tick++ }
        compose.waitForIdle()
        assertEquals(1, gateway.searches.get())
        assertEquals(0, gateway.refreshes.get())
    }

    @Test
    fun aProviderProfileWithAnotherMbidIsNeverAcceptedAsIdentity() {
        val otherMbid = "aaa436da-b331-4e58-a0fd-3d64d090c120"
        val gateway = FakeGateway(
            candidates = listOf(
                ArtistLookupCandidate(mbid, "Midnight Oil", "", "Group", "Australia"),
            ),
            profile = profile().copy(mbid = otherMbid),
        )
        val store = FakeProfileStore()
        val artworkStore = FakeArtworkStore()

        compose.setContent {
            ArtistEnrichmentPanel(
                artistCredit = "Midnight Oil",
                gateway = gateway,
                libraryId = "library-a",
                albumIds = listOf("album-a"),
                selectionStore = store,
                artworkStore = artworkStore,
            )
        }

        compose.waitUntil(5_000) { gateway.searches.get() == 1 }
        compose.onNodeWithTag("artist_candidate_$mbid").performClick()
        compose.waitUntil(5_000) { gateway.refreshes.get() == 1 }
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("El proveedor devolvió otra identidad")
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("MBID confirmado por selección: $mbid").assertExists()
        compose.onNodeWithText("MBID confirmado por selección: $otherMbid").assertDoesNotExist()
    }

    @Test
    fun identityOnlyPersistenceIsAnnouncedSeparatelyFromTheFailedProfileUpdate() {
        val gateway = FakeGateway(
            candidates = listOf(
                ArtistLookupCandidate(mbid, "Midnight Oil", "", "Group", "Australia"),
            ),
            profile = profile(),
        )
        val store = IdentityOnlyStore()

        compose.setContent {
            ArtistEnrichmentPanel(
                artistCredit = "Midnight Oil",
                gateway = gateway,
                libraryId = "library-a",
                albumIds = listOf("album-a"),
                selectionStore = store,
                artworkStore = FakeArtworkStore(),
            )
        }

        compose.waitUntil(5_000) { gateway.searches.get() == 1 }
        compose.onNodeWithTag("artist_candidate_$mbid").performClick()
        compose.waitUntil(5_000) { gateway.refreshes.get() == 1 }

        compose.onNodeWithText("Identidad guardada para este crédito y biblioteca.").assertExists()
        compose.onNodeWithText("La actualización de datos no quedó guardada en este dispositivo.")
            .assertExists()
    }

    @Test
    fun ambiguousHistoricalAssociationsExposeRealChoicesAndPersistTheExplicitSelection() {
        val otherMbid = "05072650-4b05-4e8b-83d0-dba2576e306a"
        val albums = listOf("album-a", "album-b")
        val keys = requireNotNull(scopedArtistCreditKey("library-a", "Midnight Oil", albums))
        val database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            ArtistEnrichmentCacheDatabase::class.java,
        ).allowMainThreadQueries().build()
        val store = RoomArtistEnrichmentSelectionStore(database)
        runBlocking {
            listOf(
                Triple("historical-a", mbid, "Midnight Oil"),
                Triple("historical-b", otherMbid, "Midnight Oil (historical credit)"),
            ).forEach { (group, identity, name) ->
                val saved = profile().copy(mbid = identity, name = name)
                database.artistEnrichmentDao().put(
                    ArtistEnrichmentRecord(
                        libraryId = "library-a",
                        creditKey = keys.first,
                        albumGroupKey = group,
                        confirmedMbid = identity,
                        profileJson = Json.encodeToString(saved),
                        savedAtMs = 1L,
                        staleAfterMs = Long.MAX_VALUE,
                        source = "historical-test",
                        biographySourceUrl = saved.biographySourceUrl,
                        portraitSourceUrl = null,
                        portraitLicense = null,
                        portraitLicenseUrl = null,
                        portraitAttribution = null,
                        albumIdsJson = "[\"album-${if (group.endsWith("a")) "a" else "b"}\"]",
                        membershipFingerprint = keys.second,
                    ),
                )
            }
        }
        val gateway = FakeGateway(candidates = emptyList(), profile = profile())

        compose.setContent {
            ArtistEnrichmentPanel(
                artistCredit = "Midnight Oil",
                gateway = gateway,
                libraryId = "library-a",
                albumIds = albums,
                selectionStore = store,
                artworkStore = FakeArtworkStore(),
            )
        }

        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag("artist_association_candidate_historical-b")
                .fetchSemanticsNodes().isNotEmpty()
        }
        assertEquals("Historical resolution must not search by name", 0, gateway.searches.get())
        compose.onNodeWithText("MBID: $mbid", substring = true).assertExists()
        compose.onNodeWithText("MBID: $otherMbid", substring = true).assertExists()
        compose.onNodeWithTag("artist_association_candidate_historical-b").performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("MBID confirmado por selección: $otherMbid")
                .fetchSemanticsNodes().isNotEmpty()
        }
        runBlocking {
            assertEquals(2, database.artistEnrichmentDao().getAllForCredit("library-a", keys.first).size)
        }
        database.close()
    }

    @Test
    fun lateProfileForThePreviousArtistNeverOverwritesTheCurrentArtist() {
        val gate = CompletableDeferred<ArtistExternalProfile>()
        val gateway = FakeGateway(
            candidates = listOf(
                ArtistLookupCandidate(mbid, "Midnight Oil", "", "Group", "Australia"),
            ),
            profile = profile().copy(biography = null, biographySourceUrl = null),
            refreshGate = gate,
        )
        val store = FakeProfileStore(
            cached = PersistedArtistProfile(
                profile = profile().copy(biography = null, biographySourceUrl = null),
                stale = false,
            ),
            cacheCredit = "Midnight Oil",
        )
        val artworkStore = FakeArtworkStore()
        var credit by mutableStateOf("Midnight Oil")

        compose.setContent {
            ArtistEnrichmentPanel(
                artistCredit = credit,
                gateway = gateway,
                libraryId = "library-a",
                albumIds = listOf("album-a"),
                selectionStore = store,
                artworkStore = artworkStore,
            )
        }

        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("MBID confirmado por selección", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("artist_enrichment_refresh").performScrollTo().performClick()
        compose.waitUntil(5_000) { gateway.refreshes.get() == 1 }
        compose.runOnIdle { credit = "Second Artist" }
        compose.waitUntil(5_000) { gateway.searches.get() == 1 }
        compose.runOnIdle {
            gate.complete(profile().copy(name = "Midnight Oil"))
        }
        compose.waitForIdle()
        assertTrue(
            "A late response for the previous artist must not confirm the new one",
            compose.onAllNodesWithText("MBID confirmado por selección", substring = true)
                .fetchSemanticsNodes().isEmpty(),
        )
        compose.onNodeWithTag("artist_candidate_$mbid").assertExists()
    }

    private fun profile() = ArtistExternalProfile(
        mbid = mbid,
        name = "Midnight Oil",
        type = "Group",
        area = "Australia",
        biography = "Banda australiana.",
        biographySourceUrl = "https://es.wikipedia.org/wiki/Midnight_Oil",
        biographyOutcome = ArtistMetadataOutcome.SUCCESS,
        portraitOutcome = ArtistMetadataOutcome.NO_P18,
    )

    private class FakeGateway(
        private val candidates: List<ArtistLookupCandidate>,
        private val profile: ArtistExternalProfile,
        private val failSearch: Boolean = false,
        private val refreshGate: CompletableDeferred<ArtistExternalProfile>? = null,
    ) : ArtistEnrichmentGateway {
        val searches = AtomicInteger()
        val refreshes = AtomicInteger()

        override suspend fun findCandidates(localCredit: String): List<ArtistLookupCandidate> {
            searches.incrementAndGet()
            if (failSearch) error("provider down")
            return candidates
        }

        override suspend fun confirmedProfile(mbid: String): ArtistExternalProfile {
            refreshes.incrementAndGet()
            return refreshGate?.await() ?: profile
        }

        override suspend fun refreshProfile(mbid: String): ArtistExternalProfile {
            refreshes.incrementAndGet()
            // Deliberately ignore cancellation: the controller must discard a late
            // response by identity/request epoch, not merely rely on job cancellation.
            return refreshGate?.let { withContext(NonCancellable) { it.await() } } ?: profile
        }
    }

    private class FakeProfileStore(
        var cached: PersistedArtistProfile? = null,
        private val cacheCredit: String? = null,
    ) : ArtistEnrichmentSelectionStore {
        val clears = AtomicInteger()

        override suspend fun load(
            libraryId: String,
            artistCredit: String,
            albumIds: List<String>,
        ): PersistedArtistProfile? = cached?.takeIf { cacheCredit == null || cacheCredit == artistCredit }

        override suspend fun save(
            libraryId: String,
            artistCredit: String,
            albumIds: List<String>,
            profile: ArtistExternalProfile,
        ) = Unit

        override suspend fun clear(
            libraryId: String,
            artistCredit: String,
            albumIds: List<String>,
        ) {
            clears.incrementAndGet()
            cached = null
        }
    }

    private inner class IdentityOnlyStore : ArtistEnrichmentSelectionStore, ArtistIdentitySelectionStore {
        private var identity: PersistedArtistProfile? = null

        override suspend fun load(
            libraryId: String,
            credit: String,
            albumIds: List<String>,
        ) = identity

        override suspend fun saveIdentity(
            libraryId: String,
            credit: String,
            albumIds: List<String>,
            mbid: String,
            associationGroupKey: String?,
        ): PersistedArtistProfile = PersistedArtistProfile(
            profile = ArtistExternalProfile(mbid = mbid, name = credit),
            stale = false,
            associationGroupKey = associationGroupKey ?: "canonical-group",
        ).also { identity = it }

        override suspend fun save(
            libraryId: String,
            credit: String,
            albumIds: List<String>,
            profile: ArtistExternalProfile,
        ) {
            error("La actualización de datos no quedó guardada en este dispositivo.")
        }

        override suspend fun clear(libraryId: String, credit: String, albumIds: List<String>) = Unit
    }

    private class FakeArtworkStore : ArtistArtworkStore {
        override suspend fun load(anchor: org.michimusic.mobile.artist.artwork.ArtistIdentityAnchor):
            List<ArtistArtworkSelection> = emptyList()

        override suspend fun loadAsset(assetId: String): ArtistArtworkSelection? = null

        override suspend fun save(
            anchor: org.michimusic.mobile.artist.artwork.ArtistIdentityAnchor,
            candidate: org.michimusic.mobile.artist.artwork.ArtistArtworkCandidate,
            slot: ArtworkSlot,
            downloaded: org.michimusic.mobile.artist.artwork.DownloadedArtwork,
            focus: ArtworkFocus,
            manuallyChosen: Boolean,
        ): ArtistArtworkSelection = error("not used by this test")

        override suspend fun updateFocus(
            anchor: org.michimusic.mobile.artist.artwork.ArtistIdentityAnchor,
            slot: ArtworkSlot,
            focus: ArtworkFocus,
        ): Boolean = false

        override suspend fun clear(anchor: org.michimusic.mobile.artist.artwork.ArtistIdentityAnchor) = Unit

        override suspend fun counts(): Pair<Int, Int> = 0 to 0
    }
}
