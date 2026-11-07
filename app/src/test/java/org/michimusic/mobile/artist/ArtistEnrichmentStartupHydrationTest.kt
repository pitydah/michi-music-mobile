package org.michimusic.mobile.artist

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.michimusic.mobile.artist.artwork.ArtistArtworkCandidate
import org.michimusic.mobile.artist.artwork.ArtistArtworkSelection
import org.michimusic.mobile.artist.artwork.ArtistArtworkStore
import org.michimusic.mobile.artist.artwork.ArtistIdentityAnchor
import org.michimusic.mobile.artist.artwork.ArtworkFocus
import org.michimusic.mobile.artist.artwork.ArtworkLoadStatus
import org.michimusic.mobile.artist.artwork.ArtworkSlot
import org.michimusic.mobile.artist.artwork.ArtworkSource
import org.michimusic.mobile.artist.artwork.DownloadedArtwork

@OptIn(ExperimentalCoroutinesApi::class)
class ArtistEnrichmentStartupHydrationTest {
    private val mbid = "05072650-4b05-4e8b-83d0-dba2576e306a"
    private val credit = "Midnight Oil"
    private val albums = listOf("album-diesel-and-dust")

    private fun profile(biography: String? = "Banda australiana.") = ArtistExternalProfile(
        mbid = mbid,
        name = credit,
        biography = biography,
        biographySourceUrl = biography?.let { "https://es.wikipedia.org/wiki/Midnight_Oil" },
        wikidataQid = "Q48284",
    )

    private fun selection(slot: ArtworkSlot) = ArtistArtworkSelection(
        slot = slot,
        assetId = "sha256:${"a".repeat(64)}",
        confirmedMbid = mbid,
        localImagePath = "/tmp/${slot.name}.png",
        focus = ArtworkFocus(0.5f, 0.45f),
        manuallyChosen = false,
        revision = 2L,
        status = ArtworkLoadStatus.READY,
        source = ArtworkSource.COMMONS_P18,
    )

    private fun profileStore(
        cached: PersistedArtistProfile?,
    ) = object : ArtistEnrichmentSelectionStore {
        override suspend fun load(libraryId: String, credit: String, albumIds: List<String>) = cached
        override suspend fun save(
            libraryId: String,
            credit: String,
            albumIds: List<String>,
            profile: ArtistExternalProfile,
        ) = Unit
        override suspend fun clear(libraryId: String, credit: String, albumIds: List<String>) = Unit
    }

    private fun artworkStore(selections: List<ArtistArtworkSelection>) = object : ArtistArtworkStore {
        override suspend fun load(anchor: ArtistIdentityAnchor) = selections
        override suspend fun loadAsset(assetId: String) = selections.firstOrNull { it.assetId == assetId }
        override suspend fun save(
            anchor: ArtistIdentityAnchor,
            candidate: ArtistArtworkCandidate,
            slot: ArtworkSlot,
            downloaded: DownloadedArtwork,
            focus: ArtworkFocus,
            manuallyChosen: Boolean,
        ): ArtistArtworkSelection = error("startup hydration must not save artwork")
        override suspend fun updateFocus(anchor: ArtistIdentityAnchor, slot: ArtworkSlot, focus: ArtworkFocus) = false
        override suspend fun clear(anchor: ArtistIdentityAnchor) = Unit
        override suspend fun counts() = selections.size to selections.size
        override fun observeLibraryRevision(libraryId: String) = flowOf(0L)
    }

    private suspend fun TestScope.bindAndSettle(
        controller: ArtistEnrichmentController,
        libraryId: String? = "lib_device_local",
        albumIds: List<String> = albums,
    ) {
        controller.bind(libraryId, credit, albumIds)
        advanceUntilIdle()
    }

    @Test
    fun startupHydratesSavedIdentityAndBothSlotsWithoutProviderCalls() = runTest {
        val orchestrator = mockk<ArtistEnrichmentOrchestrator>(relaxed = true)
        val controller = ArtistEnrichmentController(
            orchestrator = orchestrator,
            profileStore = profileStore(
                PersistedArtistProfile(
                    profile = profile(),
                    stale = false,
                    associationGroupKey = "historical-group",
                ),
            ),
            artworkStore = artworkStore(
                listOf(selection(ArtworkSlot.ARTIST_THUMB), selection(ArtworkSlot.ARTIST_HERO)),
            ),
            verifySelection = { it },
            scope = this,
        )

        bindAndSettle(controller)

        val state = controller.state.value
        assertEquals(ArtistEnrichmentStatus.VERIFIED, state.status)
        assertEquals(mbid, state.profile?.mbid)
        assertEquals(mbid, state.persistedMbid)
        assertEquals(ArtistPersistenceStatus.PROFILE_PERSISTED, state.persistenceStatus)
        assertTrue(state.persisted)
        assertTrue(state.profilePersisted)
        assertEquals("historical-group", state.associationGroupKey)
        assertEquals(setOf(ArtworkSlot.ARTIST_THUMB, ArtworkSlot.ARTIST_HERO), state.selections.keys)
        coVerify(exactly = 0) { orchestrator.discover(any()) }
        coVerify(exactly = 0) { orchestrator.enrich(any(), any(), any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { orchestrator.resolveArtwork(any(), any(), any(), any(), any()) }
    }

    @Test
    fun staleBiographyStaysVisibleWithoutStartingANetworkRefresh() = runTest {
        val orchestrator = mockk<ArtistEnrichmentOrchestrator>(relaxed = true)
        val controller = ArtistEnrichmentController(
            orchestrator = orchestrator,
            profileStore = profileStore(
                PersistedArtistProfile(
                    profile = profile(),
                    stale = true,
                    associationGroupKey = "historical-group",
                ),
            ),
            artworkStore = artworkStore(
                listOf(selection(ArtworkSlot.ARTIST_THUMB), selection(ArtworkSlot.ARTIST_HERO)),
            ),
            verifySelection = { it },
            scope = this,
        )

        bindAndSettle(controller)

        val state = controller.state.value
        assertEquals(ArtistEnrichmentStatus.VERIFIED, state.status)
        assertTrue(state.stale)
        assertEquals(mbid, state.profile?.mbid)
        coVerify(exactly = 0) { orchestrator.discover(any()) }
        coVerify(exactly = 0) { orchestrator.enrich(any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun failedRefreshKeepsCachedProfileMarkedStale() = runTest {
        val cachedProfile = profile()
        val orchestrator = mockk<ArtistEnrichmentOrchestrator>(relaxed = true)
        coEvery {
            orchestrator.enrich(any(), any(), any(), any(), any(), any(), any())
        } returns ArtistEnrichmentResult(
            phase = ArtistEnrichmentPhase.PARTIAL,
            profile = cachedProfile,
            persisted = true,
            persistenceStatus = ArtistPersistenceStatus.IDENTITY_PERSISTED,
            message = "No fue posible actualizar los datos externos",
        )
        val controller = ArtistEnrichmentController(
            orchestrator = orchestrator,
            profileStore = profileStore(
                PersistedArtistProfile(
                    profile = cachedProfile,
                    stale = true,
                    associationGroupKey = "historical-group",
                ),
            ),
            artworkStore = artworkStore(emptyList()),
            verifySelection = { it },
            scope = this,
        )
        bindAndSettle(controller)

        controller.refresh(credit, "lib_device_local", albums)
        advanceUntilIdle()

        val state = controller.state.value
        assertEquals(mbid, state.profile?.mbid)
        assertTrue(state.stale)
        assertTrue(state.refreshFailed)
        assertTrue(!state.refreshing)
    }

    @Test
    fun recoveredAssociationIsInformationalAndDoesNotQueryProviders() = runTest {
        val orchestrator = mockk<ArtistEnrichmentOrchestrator>(relaxed = true)
        val controller = ArtistEnrichmentController(
            orchestrator = orchestrator,
            profileStore = profileStore(
                PersistedArtistProfile(
                    profile = profile(),
                    stale = false,
                    associationGroupKey = "historical-group",
                    needsConfirmation = true,
                ),
            ),
            artworkStore = artworkStore(emptyList()),
            verifySelection = { it },
            scope = this,
        )

        bindAndSettle(controller)

        val state = controller.state.value
        assertEquals(ArtistEnrichmentStatus.VERIFIED, state.status)
        assertTrue(state.needsConfirmation)
        assertEquals(
            "Asociación recuperada de un grupo anterior; confirma si corresponde.",
            state.message,
        )
        coVerify(exactly = 0) { orchestrator.discover(any()) }
        coVerify(exactly = 0) { orchestrator.enrich(any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun missingIdentityLibraryNeverSearchesByName() = runTest {
        val orchestrator = mockk<ArtistEnrichmentOrchestrator>(relaxed = true)
        val controller = ArtistEnrichmentController(
            orchestrator = orchestrator,
            profileStore = profileStore(null),
            artworkStore = artworkStore(emptyList()),
            verifySelection = { it },
            scope = this,
        )

        bindAndSettle(controller, libraryId = null)

        val state = controller.state.value
        assertEquals(ArtistEnrichmentStatus.IDLE, state.status)
        assertEquals("Selecciona una biblioteca local para enriquecer este artista.", state.message)
        coVerify(exactly = 0) { orchestrator.discover(any()) }
        coVerify(exactly = 0) { orchestrator.enrich(any(), any(), any(), any(), any(), any(), any()) }
    }
}
