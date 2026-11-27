package org.michimusic.mobile.artist

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.michimusic.mobile.artist.artwork.ArtistArtworkCandidate
import org.michimusic.mobile.artist.artwork.ArtistArtworkSelection
import org.michimusic.mobile.artist.artwork.ArtistArtworkStore
import org.michimusic.mobile.artist.artwork.ArtistIdentityAnchor
import org.michimusic.mobile.artist.artwork.ArtworkFocus
import org.michimusic.mobile.artist.artwork.ArtworkSlot
import org.michimusic.mobile.artist.artwork.DownloadedArtwork

/**
 * K48-R05: a destructive enrichment operation must name an exact receipt, must delete
 * nothing before the user confirms, must never publish a late result into a newer artist,
 * and must report a partial deletion as partial instead of success.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ArtistEnrichmentControllerDeleteTest {
    private val mbid = "05072650-4b05-4e8b-83d0-dba2576e306a"
    private val albums = listOf("album-a", "album-b")

    private fun profile(name: String = "Midnight Oil") = ArtistExternalProfile(
        mbid = mbid,
        name = name,
        biography = "Banda australiana.",
        biographySourceUrl = "https://es.wikipedia.org/wiki/Midnight_Oil",
        wikidataQid = "Q48284",
    )

    private class Stores {
        var profileClears = 0
        var artworkClears = 0
        var profileGate: CompletableDeferred<Unit>? = null
        var profileFails = false
        var artworkFails = false

        fun selection(profile: ArtistExternalProfile) = object : ArtistEnrichmentSelectionStore {
            override suspend fun load(
                libraryId: String,
                credit: String,
                albumIds: List<String>,
            ) = PersistedArtistProfile(profile, stale = false)

            override suspend fun save(
                libraryId: String,
                credit: String,
                albumIds: List<String>,
                profile: ArtistExternalProfile,
            ) = Unit

            override suspend fun clear(libraryId: String, credit: String, albumIds: List<String>) {
                profileGate?.await()
                if (profileFails) error("association store unavailable")
                profileClears++
            }
        }

        fun artwork() = object : ArtistArtworkStore {
            override suspend fun load(anchor: ArtistIdentityAnchor) =
                emptyList<ArtistArtworkSelection>()

            override suspend fun loadAsset(assetId: String) = null

            override suspend fun save(
                anchor: ArtistIdentityAnchor,
                candidate: ArtistArtworkCandidate,
                slot: ArtworkSlot,
                downloaded: DownloadedArtwork,
                focus: ArtworkFocus,
                manuallyChosen: Boolean,
            ): ArtistArtworkSelection = error("not used in this test")

            override suspend fun updateFocus(
                anchor: ArtistIdentityAnchor,
                slot: ArtworkSlot,
                focus: ArtworkFocus,
            ) = true

            override suspend fun clear(anchor: ArtistIdentityAnchor) {
                if (artworkFails) error("artwork files unavailable")
                artworkClears++
            }

            override suspend fun counts() = 0 to 0

            override fun observeLibraryRevision(libraryId: String) = flowOf(0L)
        }
    }

    private fun controller(stores: Stores, scope: kotlinx.coroutines.CoroutineScope) =
        ArtistEnrichmentController(
            orchestrator = io.mockk.mockk(relaxed = true),
            profileStore = stores.selection(profile()),
            artworkStore = stores.artwork(),
            verifySelection = { it },
            scope = scope,
        )

    @Test
    fun theReceiptNamesTheExactTargetBeforeAnythingIsDeleted() = runTest {
        val stores = Stores()
        val controller = controller(stores, this)
        controller.bind("library-a", "Midnight Oil", albums)
        advanceUntilIdle()
        val mbidBeforeDelete = controller.state.value.profile?.mbid
        assertEquals(mbid, mbidBeforeDelete)

        controller.requestDelete("Midnight Oil", "library-a", albums)

        val delete = controller.state.value.delete
        assertTrue("Expected Confirming, got $delete", delete is EnrichmentDeleteState.Confirming)
        val receipt = (delete as EnrichmentDeleteState.Confirming).receipt
        assertEquals("library-a", receipt.libraryId)
        assertEquals("Midnight Oil", receipt.artistCredit)
        assertEquals(albums, receipt.albumIds)
        assertEquals(mbid, receipt.mbid)
        assertTrue("Every request carries its own sequence", receipt.sequence > 0L)
        assertEquals("Nothing may be deleted before confirmation", 0, stores.profileClears)
        assertEquals(0, stores.artworkClears)

        // A second request after cancelling gets a newer sequence: receipts never repeat.
        controller.cancelDelete()
        controller.requestDelete("Midnight Oil", "library-a", albums)
        val second = (controller.state.value.delete as EnrichmentDeleteState.Confirming).receipt
        assertTrue(
            "A later receipt must be newer (${second.sequence} > ${receipt.sequence})",
            second.sequence > receipt.sequence,
        )
        controller.release()
    }

    @Test
    fun cancellingConfirmationDeletesNothing() = runTest {
        val stores = Stores()
        val controller = controller(stores, this)
        controller.bind("library-a", "Midnight Oil", albums)
        advanceUntilIdle()

        controller.requestDelete("Midnight Oil", "library-a", albums)
        controller.cancelDelete()
        advanceUntilIdle()

        assertEquals(EnrichmentDeleteState.Idle, controller.state.value.delete)
        assertEquals(0, stores.profileClears)
        assertEquals(0, stores.artworkClears)
        controller.release()
    }

    @Test
    fun confirmingWithoutRequestingDeletesNothing() = runTest {
        val stores = Stores()
        val controller = controller(stores, this)
        controller.bind("library-a", "Midnight Oil", albums)
        advanceUntilIdle()

        controller.confirmDelete("Midnight Oil", "library-a", albums)
        advanceUntilIdle()

        assertEquals(EnrichmentDeleteState.Idle, controller.state.value.delete)
        assertEquals(0, stores.profileClears)
        controller.release()
    }

    @Test
    fun aConfirmationForAnotherArtistIsRefused() = runTest {
        val stores = Stores()
        val controller = controller(stores, this)
        controller.bind("library-a", "Midnight Oil", albums)
        advanceUntilIdle()

        controller.requestDelete("Midnight Oil", "library-a", albums)
        controller.confirmDelete("Midnight Oil", "library-b", albums)
        advanceUntilIdle()

        assertTrue(
            "A receipt must not be re-targeted to another library",
            controller.state.value.delete is EnrichmentDeleteState.Confirming,
        )
        assertEquals(0, stores.profileClears)
        controller.release()
    }

    @Test
    fun aLateDeleteResultNeverPublishesIntoANewArtist() = runTest {
        val stores = Stores()
        val gate = CompletableDeferred<Unit>()
        stores.profileGate = gate
        val controller = controller(stores, this)
        controller.bind("library-a", "Midnight Oil", albums)
        advanceUntilIdle()

        controller.requestDelete("Midnight Oil", "library-a", albums)
        controller.confirmDelete("Midnight Oil", "library-a", albums)
        runCurrent()
        assertTrue(
            "The delete must be in flight, got ${controller.state.value.delete}",
            controller.state.value.delete is EnrichmentDeleteState.Deleting,
        )

        // A re-bind while the store is still working invalidates the whole operation.
        controller.bind("library-b", "Another Artist", listOf("album-z"))
        advanceUntilIdle()
        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(EnrichmentDeleteState.Idle, controller.state.value.delete)
        assertTrue(
            "A late delete must not claim success for the new identity",
            "Datos enriquecidos borrados." != controller.state.value.message,
        )
        assertNull(
            "A late delete must not keep the deleted receipt",
            (controller.state.value.delete as? EnrichmentDeleteState.Completed)?.receipt,
        )
        controller.release()
        // The re-bind cancelled the in-flight store call: the deletion never completed.
        assertEquals(0, stores.profileClears)
    }

    @Test
    fun aPartialDeletionIsNeverReportedAsSuccess() = runTest {
        val stores = Stores()
        stores.artworkFails = true
        val controller = controller(stores, this)
        controller.bind("library-a", "Midnight Oil", albums)
        advanceUntilIdle()

        // Legacy API must never delete without a separate explicit confirmation.
        @Suppress("DEPRECATION")
        controller.clear("Midnight Oil", "library-a", albums)
        advanceUntilIdle()
        assertTrue(controller.state.value.delete is EnrichmentDeleteState.Confirming)
        assertEquals(0, stores.profileClears)
        assertEquals(0, stores.artworkClears)
        controller.confirmDelete("Midnight Oil", "library-a", albums)
        advanceUntilIdle()

        val delete = controller.state.value.delete
        assertTrue("Expected PartiallyCompleted, got $delete", delete is EnrichmentDeleteState.PartiallyCompleted)
        assertEquals(
            EnrichmentDeleteFailure.ARTWORK_FILES_REMAIN,
            (delete as EnrichmentDeleteState.PartiallyCompleted).failure,
        )
        assertEquals(
            "Se borró la asociación, pero no todos los archivos de imagen.",
            controller.state.value.message,
        )
        controller.release()
    }

    @Test
    fun successfulDeleteClearsPreviouslyVerifiedStateAndArtworkSelections() = runTest {
        val stores = Stores()
        val controller = controller(stores, this)
        controller.bind("library-a", "Midnight Oil", albums)
        advanceUntilIdle()
        assertEquals(mbid, controller.state.value.profile?.mbid)

        controller.requestDelete("Midnight Oil", "library-a", albums)
        controller.confirmDelete("Midnight Oil", "library-a", albums)
        advanceUntilIdle()

        val current = controller.state.value
        assertTrue(current.delete is EnrichmentDeleteState.Completed)
        assertEquals(1, stores.profileClears)
        assertEquals(1, stores.artworkClears)
        assertEquals(ArtistEnrichmentStatus.IDLE, current.status)
        assertNull(current.profile)
        assertNull(current.persistedMbid)
        assertNull(current.associationGroupKey)
        assertTrue(current.candidates.isEmpty())
        assertTrue(current.pendingPortraits.isEmpty())
        assertTrue(current.selections.isEmpty())
        assertTrue(!current.persisted)
        assertTrue(!current.profilePersisted)
        controller.release()
    }

    @Test
    fun atomicRemovalUsesOneStoreAndDoesNotCallLegacyArtworkClear() = runTest {
        val stores = Stores()
        var atomicClears = 0
        val atomic = object : ArtistEnrichmentSelectionStore by stores.selection(profile()),
            ArtistAtomicEnrichmentRemovalStore {
            override suspend fun clearWithArtwork(
                libraryId: String, credit: String, albumIds: List<String>,
                associationGroupKey: String?, expectedMbid: String,
            ): ArtistDeletionCleanupStatus {
                assertEquals("library-a", libraryId)
                assertEquals("Midnight Oil", credit)
                assertEquals(albums, albumIds)
                assertEquals(mbid, expectedMbid)
                atomicClears++
                return ArtistDeletionCleanupStatus.COMPLETE
            }
        }
        val controller = ArtistEnrichmentController(
            orchestrator = io.mockk.mockk(relaxed = true),
            profileStore = atomic,
            artworkStore = stores.artwork(),
            verifySelection = { it },
            scope = this,
        )
        controller.bind("library-a", "Midnight Oil", albums)
        advanceUntilIdle()
        controller.requestDelete("Midnight Oil", "library-a", albums)
        controller.confirmDelete("Midnight Oil", "library-a", albums)
        advanceUntilIdle()
        assertTrue(controller.state.value.delete is EnrichmentDeleteState.Completed)
        assertNull(controller.state.value.profile)
        assertEquals(1, atomicClears)
        assertEquals(0, stores.profileClears)
        assertEquals(0, stores.artworkClears)
        controller.release()
    }

    @Test
    fun durableCleanupPendingIsNotMisreportedAsFullyDeleted() = runTest {
        val stores = Stores()
        val atomic = object : ArtistEnrichmentSelectionStore by stores.selection(profile()),
            ArtistAtomicEnrichmentRemovalStore {
            override suspend fun clearWithArtwork(
                libraryId: String, credit: String, albumIds: List<String>,
                associationGroupKey: String?, expectedMbid: String,
            ): ArtistDeletionCleanupStatus = ArtistDeletionCleanupStatus.CLEANUP_PENDING
        }
        val controller = ArtistEnrichmentController(
            orchestrator = io.mockk.mockk(relaxed = true),
            profileStore = atomic,
            artworkStore = stores.artwork(),
            verifySelection = { it },
            scope = this,
        )
        controller.bind("library-a", "Midnight Oil", albums)
        advanceUntilIdle()
        controller.requestDelete("Midnight Oil", "library-a", albums)
        controller.confirmDelete("Midnight Oil", "library-a", albums)
        advanceUntilIdle()
        assertTrue(controller.state.value.delete is EnrichmentDeleteState.PartiallyCompleted)
        assertNull(controller.state.value.profile)
        assertEquals(0, stores.artworkClears)
        controller.release()
    }

    @Test
    fun failedAtomicRemovalKeepsIdentityAndNeverInvokesLegacyStores() = runTest {
        val stores = Stores()
        val atomic = object : ArtistEnrichmentSelectionStore by stores.selection(profile()),
            ArtistAtomicEnrichmentRemovalStore {
            override suspend fun clearWithArtwork(
                libraryId: String, credit: String, albumIds: List<String>,
                associationGroupKey: String?, expectedMbid: String,
            ): ArtistDeletionCleanupStatus {
                error("Room transaction rolled back")
            }
        }
        val controller = ArtistEnrichmentController(
            orchestrator = io.mockk.mockk(relaxed = true),
            profileStore = atomic,
            artworkStore = stores.artwork(),
            verifySelection = { it },
            scope = this,
        )
        controller.bind("library-a", "Midnight Oil", albums)
        advanceUntilIdle()
        controller.requestDelete("Midnight Oil", "library-a", albums)
        controller.confirmDelete("Midnight Oil", "library-a", albums)
        advanceUntilIdle()
        assertTrue(controller.state.value.delete is EnrichmentDeleteState.Failed)
        assertEquals(mbid, controller.state.value.profile?.mbid)
        assertEquals(0, stores.profileClears)
        assertEquals(0, stores.artworkClears)
        controller.release()
    }

    @Test
    fun aFailedAssociationDeletionKeepsTheVerifiedProfile() = runTest {
        val stores = Stores()
        stores.profileFails = true
        val controller = controller(stores, this)
        controller.bind("library-a", "Midnight Oil", albums)
        advanceUntilIdle()

        controller.requestDelete("Midnight Oil", "library-a", albums)
        controller.confirmDelete("Midnight Oil", "library-a", albums)
        advanceUntilIdle()

        val delete = controller.state.value.delete
        assertTrue("Expected Failed, got $delete", delete is EnrichmentDeleteState.Failed)
        assertEquals(
            EnrichmentDeleteFailure.ASSOCIATION_NOT_REMOVED,
            (delete as EnrichmentDeleteState.Failed).failure,
        )
        assertEquals("No fue posible borrar todos los datos guardados.", controller.state.value.message)
        assertEquals(
            "A failed deletion cannot silently drop the verified identity",
            mbid,
            controller.state.value.profile?.mbid,
        )
        assertEquals(
            "Failed identity removal must not retire artwork references",
            0,
            stores.artworkClears,
        )
        controller.release()
    }
}
