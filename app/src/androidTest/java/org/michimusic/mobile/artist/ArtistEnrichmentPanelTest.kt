package org.michimusic.mobile.artist

import androidx.compose.material3.Text
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
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.michimusic.mobile.artist.artwork.ArtistArtworkSelection
import org.michimusic.mobile.artist.artwork.ArtistArtworkStore
import org.michimusic.mobile.artist.artwork.ArtworkFocus
import org.michimusic.mobile.artist.artwork.ArtworkSlot

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
        assertTrue(
            "A mismatched provider MBID must not be accepted",
            compose.onAllNodesWithText("MBID confirmado por selección", substring = true)
                .fetchSemanticsNodes().isEmpty(),
        )
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
