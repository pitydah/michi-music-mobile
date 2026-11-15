package org.michimusic.mobile.artist

import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.michimusic.mobile.artist.artwork.ArtistArtworkCandidate
import org.michimusic.mobile.artist.artwork.ArtistArtworkStore
import org.michimusic.mobile.artist.artwork.ArtistIdentityAnchor
import org.michimusic.mobile.artist.artwork.ArtworkEvidence
import org.michimusic.mobile.artist.artwork.ArtworkFocus
import org.michimusic.mobile.artist.artwork.ArtworkRights
import org.michimusic.mobile.artist.artwork.ArtworkSlot
import org.michimusic.mobile.artist.artwork.ArtworkSource
import org.michimusic.mobile.artist.artwork.DownloadedArtwork

/**
 * K48-S1: a portrait response belongs to the identity and action that requested it.
 * A late answer from another artist, library or generation is dropped, never published
 * and never persisted.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ArtistEnrichmentControllerPortraitTest {
    private val mbid = "05072650-4b05-4e8b-83d0-dba2576e306a"
    private val otherMbid = "aaa436da-b331-4e58-a0fd-3d64d090c120"

    private fun profile(id: String = mbid, name: String = "'Til Tuesday") = ArtistExternalProfile(
        mbid = id,
        name = name,
        biography = "Banda.",
        biographySourceUrl = null,
        wikidataQid = "Q48284",
    )

    private fun candidate(asset: String = "portrait") = ArtistArtworkCandidate(
        source = ArtworkSource.OPENVERSE,
        stableAssetId = "sha256:${asset.repeat(64).take(64)}",
        confirmedMbid = mbid,
        verifiedQid = "Q48284",
        canonicalSourcePage = "https://example.test/$asset",
        imageUrl = "https://example.test/$asset.jpg",
        originalWidth = 800,
        originalHeight = 800,
        mimeType = "image/jpeg",
        licenseId = "cc0",
        licenseUrl = "https://example.test/license",
        author = "Autor",
        attribution = "Autor / CC0",
        evidence = ArtworkEvidence.VERIFIED_QID_REFERENCE,
        rights = ArtworkRights.VERIFIED_ALLOWED,
    )

    private fun store(): ArtistArtworkStore = object : ArtistArtworkStore {
        override suspend fun load(anchor: ArtistIdentityAnchor) = emptyList<org.michimusic.mobile.artist.artwork.ArtistArtworkSelection>()
        override suspend fun loadAsset(assetId: String) = null
        override suspend fun save(
            anchor: ArtistIdentityAnchor,
            candidate: ArtistArtworkCandidate,
            slot: ArtworkSlot,
            downloaded: DownloadedArtwork,
            focus: ArtworkFocus,
            manuallyChosen: Boolean,
        ) = error("not used")
        override suspend fun updateFocus(anchor: ArtistIdentityAnchor, slot: ArtworkSlot, focus: ArtworkFocus) = true
        override suspend fun clear(anchor: ArtistIdentityAnchor) = Unit
        override suspend fun counts() = 0 to 0
        override fun observeLibraryRevision(libraryId: String) = flowOf(0L)
    }

    private fun profileStore(persisted: PersistedArtistProfile?) = object : ArtistEnrichmentSelectionStore {
        override suspend fun load(libraryId: String, credit: String, albumIds: List<String>) = persisted
        override suspend fun save(
            libraryId: String,
            credit: String,
            albumIds: List<String>,
            profile: ArtistExternalProfile,
        ) = Unit
        override suspend fun clear(libraryId: String, credit: String, albumIds: List<String>) = Unit
    }

    private fun controller(
        scope: kotlinx.coroutines.CoroutineScope,
        orchestrator: ArtistEnrichmentOrchestrator,
        persisted: PersistedArtistProfile? = PersistedArtistProfile(profile(), stale = false),
    ) = ArtistEnrichmentController(
        orchestrator = orchestrator,
        profileStore = profileStore(persisted),
        artworkStore = store(),
        verifySelection = { it },
        scope = scope,
    )

    @Test
    fun latePortraitAnswerAfterIdentityChangeIsDiscarded() = runTest {
        val gate = CompletableDeferred<List<ArtistArtworkCandidate>>()
        val orchestrator = mockk<ArtistEnrichmentOrchestrator>(relaxed = true)
        coEvery { orchestrator.searchManualPortraits(any(), any(), any(), any(), any(), any()) } coAnswers {
            gate.await()
        }
        val controller = controller(this, orchestrator)
        controller.bind("lib-a", "'Til Tuesday", listOf("album-1"))
        advanceUntilIdle()

        controller.searchPortraits("'Til Tuesday", "lib-a", listOf("album-1"))
        runCurrent()

        // The reader switches artist while Openverse is still answering.
        controller.bind("lib-b", "A-ha", listOf("album-2"))
        advanceUntilIdle()
        gate.complete(listOf(candidate()))
        advanceUntilIdle()

        assertTrue(controller.state.value.pendingPortraits.isEmpty())
        assertFalse(controller.state.value.searchingPortraits)
    }

    @Test
    fun secondSearchPublishesOnlyItsOwnAnswer() = runTest {
        val first = CompletableDeferred<List<ArtistArtworkCandidate>>()
        val second = CompletableDeferred<List<ArtistArtworkCandidate>>()
        var calls = 0
        val orchestrator = mockk<ArtistEnrichmentOrchestrator>(relaxed = true)
        coEvery { orchestrator.searchManualPortraits(any(), any(), any(), any(), any(), any()) } coAnswers {
            if (calls++ == 0) first.await() else second.await()
        }
        val controller = controller(this, orchestrator)
        controller.bind("lib-a", "'Til Tuesday", listOf("album-1"))
        advanceUntilIdle()

        controller.searchPortraits("'Til Tuesday", "lib-a", listOf("album-1"))
        runCurrent()
        controller.invalidatePortraitSearch()
        controller.searchPortraits("'Til Tuesday", "lib-a", listOf("album-1"), query = "'Til Tuesday portraits")
        runCurrent()

        first.complete(listOf(candidate("stale")))
        advanceUntilIdle()
        assertTrue(controller.state.value.pendingPortraits.isEmpty())

        second.complete(listOf(candidate("current")))
        advanceUntilIdle()
        assertEquals(1, controller.state.value.pendingPortraits.size)
        assertEquals(
            candidate("current").stableAssetId,
            controller.state.value.pendingPortraits.single().stableAssetId,
        )
        assertFalse(controller.state.value.searchingPortraits)
    }

    @Test
    fun changingLibraryDiscardsThePendingAnswer() = runTest {
        val gate = CompletableDeferred<List<ArtistArtworkCandidate>>()
        val orchestrator = mockk<ArtistEnrichmentOrchestrator>(relaxed = true)
        coEvery { orchestrator.searchManualPortraits(any(), any(), any(), any(), any(), any()) } coAnswers {
            gate.await()
        }
        val controller = controller(this, orchestrator)
        controller.bind("lib-a", "'Til Tuesday", listOf("album-1"))
        advanceUntilIdle()
        controller.searchPortraits("'Til Tuesday", "lib-a", listOf("album-1"))
        runCurrent()

        controller.bind("lib-b", "'Til Tuesday", listOf("album-1"))
        advanceUntilIdle()
        gate.complete(listOf(candidate()))
        advanceUntilIdle()

        assertTrue(controller.state.value.pendingPortraits.isEmpty())
    }

    @Test
    fun clearingTheEnrichmentCancelsThePortraitSearch() = runTest {
        val gate = CompletableDeferred<List<ArtistArtworkCandidate>>()
        val orchestrator = mockk<ArtistEnrichmentOrchestrator>(relaxed = true)
        coEvery { orchestrator.searchManualPortraits(any(), any(), any(), any(), any(), any()) } coAnswers {
            gate.await()
        }
        val controller = controller(this, orchestrator)
        controller.bind("lib-a", "'Til Tuesday", listOf("album-1"))
        advanceUntilIdle()
        controller.searchPortraits("'Til Tuesday", "lib-a", listOf("album-1"))
        runCurrent()

        controller.clear("'Til Tuesday", "lib-a", listOf("album-1"))
        advanceUntilIdle()
        gate.complete(listOf(candidate()))
        advanceUntilIdle()

        assertTrue(controller.state.value.pendingPortraits.isEmpty())
        assertFalse(controller.state.value.searchingPortraits)
    }

    @Test
    fun releasingTheOwnerDropsTheAnswerAndKeepsNoJob() = runTest {
        val gate = CompletableDeferred<List<ArtistArtworkCandidate>>()
        val orchestrator = mockk<ArtistEnrichmentOrchestrator>(relaxed = true)
        coEvery { orchestrator.searchManualPortraits(any(), any(), any(), any(), any(), any()) } coAnswers {
            gate.await()
        }
        val controller = controller(this, orchestrator)
        controller.bind("lib-a", "'Til Tuesday", listOf("album-1"))
        advanceUntilIdle()
        controller.searchPortraits("'Til Tuesday", "lib-a", listOf("album-1"))
        runCurrent()

        controller.release()
        advanceUntilIdle()
        gate.complete(listOf(candidate()))
        advanceUntilIdle()

        assertTrue(controller.state.value.pendingPortraits.isEmpty())
        assertFalse(controller.state.value.searchingPortraits)
    }

    @Test
    fun providerFailureIsReportedWithoutErasingVerifiedPortraits() = runTest {
        val orchestrator = mockk<ArtistEnrichmentOrchestrator>(relaxed = true)
        coEvery { orchestrator.searchManualPortraits(any(), any(), any(), any(), any(), any()) } throws
            IllegalStateException("network down")
        val controller = controller(this, orchestrator)
        controller.bind("lib-a", "'Til Tuesday", listOf("album-1"))
        advanceUntilIdle()

        controller.searchPortraits("'Til Tuesday", "lib-a", listOf("album-1"))
        advanceUntilIdle()

        assertFalse(controller.state.value.searchingPortraits)
        assertTrue(controller.state.value.message.orEmpty().contains("No fue posible consultar Openverse"))
    }

    @Test
    fun unverifiedIdentityNeverReachesTheProvider() = runTest {
        val orchestrator = mockk<ArtistEnrichmentOrchestrator>(relaxed = true)
        val controller = controller(
            this,
            orchestrator,
            persisted = PersistedArtistProfile(profile(id = ""), stale = false),
        )
        controller.bind("lib-a", "'Til Tuesday", listOf("album-1"))
        advanceUntilIdle()

        controller.searchPortraits("'Til Tuesday", "lib-a", listOf("album-1"))
        advanceUntilIdle()

        io.mockk.coVerify(exactly = 0) {
            orchestrator.searchManualPortraits(any(), any(), any(), any(), any(), any())
        }
        assertTrue(controller.state.value.pendingPortraits.isEmpty())
    }

    @Test
    fun receiptUsesCanonicalAlbumOrderAndLowercaseMbid() {
        val receipt = ArtistPortraitSearchReceipt.of(
            libraryId = "lib-a",
            artistCredit = "A-ha",
            albumIds = listOf("b", "a", "b"),
            associationGroupKey = "group",
            mbid = "AAA436DA-B331-4E58-A0FD-3D64D090C120",
            requestSequence = 7,
        )
        assertEquals(listOf("a", "b"), receipt.albumIds)
        assertEquals(otherMbid.lowercase(), receipt.mbid)
        assertEquals(7L, receipt.requestSequence)
    }
}
