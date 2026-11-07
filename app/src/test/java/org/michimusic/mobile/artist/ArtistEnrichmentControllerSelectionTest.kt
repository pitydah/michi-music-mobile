package org.michimusic.mobile.artist

import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.michimusic.mobile.artist.artwork.ArtistArtworkCandidate
import org.michimusic.mobile.artist.artwork.ArtistArtworkSelection
import org.michimusic.mobile.artist.artwork.ArtistArtworkStore
import org.michimusic.mobile.artist.artwork.ArtistIdentityAnchor
import org.michimusic.mobile.artist.artwork.ArtworkFocus
import org.michimusic.mobile.artist.artwork.DownloadedArtwork
import org.michimusic.mobile.artist.artwork.ArtworkLoadStatus
import org.michimusic.mobile.artist.artwork.ArtworkSlot
import org.michimusic.mobile.artist.artwork.ArtworkSource

@OptIn(ExperimentalCoroutinesApi::class)
class ArtistEnrichmentControllerSelectionTest {
    private val mbid = "05072650-4b05-4e8b-83d0-dba2576e306a"
    private val other = "aaa436da-b331-4e58-a0fd-3d64d090c120"

    private fun profile() = ArtistExternalProfile(
        mbid = mbid,
        name = "Midnight Oil",
        biography = "Banda australiana.",
        biographySourceUrl = "https://es.wikipedia.org/wiki/Midnight_Oil",
        wikidataQid = "Q48284",
    )

    private fun selection(slot: ArtworkSlot, asset: String, identity: String = mbid) =
        ArtistArtworkSelection(
            slot = slot,
            assetId = "sha256:${asset.repeat(64).take(64)}",
            confirmedMbid = identity,
            localImagePath = "/tmp/$asset.jpg",
            focus = ArtworkFocus(),
            manuallyChosen = true,
            revision = 1L,
            status = ArtworkLoadStatus.READY,
            source = ArtworkSource.COMMONS_P18,
        )

    private fun artworkStore(initial: List<ArtistArtworkSelection>) = object : ArtistArtworkStore {
        override suspend fun load(anchor: ArtistIdentityAnchor) = initial
        override suspend fun loadAsset(assetId: String) = initial.firstOrNull { it.assetId == assetId }
        override suspend fun save(
            anchor: ArtistIdentityAnchor,
            candidate: ArtistArtworkCandidate,
            slot: ArtworkSlot,
            downloaded: DownloadedArtwork,
            focus: ArtworkFocus,
            manuallyChosen: Boolean,
        ): ArtistArtworkSelection = error("not used")
        override suspend fun updateFocus(anchor: ArtistIdentityAnchor, slot: ArtworkSlot, focus: ArtworkFocus) = true
        override suspend fun clear(anchor: ArtistIdentityAnchor) = Unit
        override suspend fun counts() = initial.size to initial.size
        override fun observeLibraryRevision(libraryId: String) = flowOf(0L)
    }

    @Test
    fun manualSelectionIsPublishedToTheSharedControllerStateImmediately() = runTest {
        val controller = ArtistEnrichmentController(
            orchestrator = mockk(relaxed = true),
            profileStore = object : ArtistEnrichmentSelectionStore {
                override suspend fun load(libraryId: String, credit: String, albumIds: List<String>) =
                    PersistedArtistProfile(profile(), stale = false)
                override suspend fun save(
                    libraryId: String,
                    credit: String,
                    albumIds: List<String>,
                    profile: ArtistExternalProfile,
                ) = Unit
                override suspend fun clear(libraryId: String, credit: String, albumIds: List<String>) = Unit
            },
            artworkStore = artworkStore(
                listOf(
                    selection(ArtworkSlot.ARTIST_THUMB, "a"),
                    selection(ArtworkSlot.ARTIST_HERO, "b"),
                ),
            ),
            verifySelection = { it },
            scope = this,
        )
        controller.bind("lib-a", "Midnight Oil", listOf("album-1"))
        advanceUntilIdle()

        val manualHero = selection(ArtworkSlot.ARTIST_HERO, "c").copy(manuallyChosen = true)
        controller.applySelections(
            libraryId = "lib-a",
            artistCredit = "Midnight Oil",
            albumIds = listOf("album-1"),
            mbid = mbid,
            selections = mapOf(
                ArtworkSlot.ARTIST_THUMB to selection(ArtworkSlot.ARTIST_THUMB, "a"),
                ArtworkSlot.ARTIST_HERO to manualHero,
            ),
        )

        assertEquals(manualHero.assetId, controller.state.value.selections[ArtworkSlot.ARTIST_HERO]?.assetId)
        assertTrue(controller.state.value.selections[ArtworkSlot.ARTIST_HERO]?.manuallyChosen == true)
    }

    @Test
    fun manualSelectionFromAnotherIdentityIsNeverPublished() = runTest {
        val controller = ArtistEnrichmentController(
            orchestrator = mockk(relaxed = true),
            profileStore = object : ArtistEnrichmentSelectionStore {
                override suspend fun load(libraryId: String, credit: String, albumIds: List<String>) =
                    PersistedArtistProfile(profile(), stale = false)
                override suspend fun save(
                    libraryId: String,
                    credit: String,
                    albumIds: List<String>,
                    profile: ArtistExternalProfile,
                ) = Unit
                override suspend fun clear(libraryId: String, credit: String, albumIds: List<String>) = Unit
            },
            artworkStore = artworkStore(
                listOf(
                    selection(ArtworkSlot.ARTIST_THUMB, "a"),
                    selection(ArtworkSlot.ARTIST_HERO, "b"),
                ),
            ),
            verifySelection = { it },
            scope = this,
        )
        controller.bind("lib-a", "Midnight Oil", listOf("album-1"))
        advanceUntilIdle()

        controller.applySelections(
            libraryId = "lib-a",
            artistCredit = "Midnight Oil",
            albumIds = listOf("album-1"),
            mbid = mbid,
            selections = mapOf(ArtworkSlot.ARTIST_HERO to selection(ArtworkSlot.ARTIST_HERO, "d", other)),
        )

        assertTrue(controller.state.value.selections.isEmpty())
    }

    @Test
    fun lateManualSelectionFromAnotherLibraryCannotMutateTheCurrentArtist() = runTest {
        val controller = ArtistEnrichmentController(
            orchestrator = mockk(relaxed = true),
            profileStore = object : ArtistEnrichmentSelectionStore {
                override suspend fun load(libraryId: String, credit: String, albumIds: List<String>) =
                    PersistedArtistProfile(profile(), stale = false)
                override suspend fun save(
                    libraryId: String,
                    credit: String,
                    albumIds: List<String>,
                    profile: ArtistExternalProfile,
                ) = Unit
                override suspend fun clear(libraryId: String, credit: String, albumIds: List<String>) = Unit
            },
            artworkStore = artworkStore(
                listOf(
                    selection(ArtworkSlot.ARTIST_THUMB, "a"),
                    selection(ArtworkSlot.ARTIST_HERO, "b"),
                ),
            ),
            verifySelection = { it },
            scope = this,
        )
        controller.bind("lib-a", "Midnight Oil", listOf("album-1"))
        advanceUntilIdle()
        controller.applySelections(
            libraryId = "lib-a",
            artistCredit = "Midnight Oil",
            albumIds = listOf("album-1"),
            mbid = mbid,
            selections = mapOf(
                ArtworkSlot.ARTIST_HERO to selection(ArtworkSlot.ARTIST_HERO, "a"),
            ),
        )
        assertEquals(
            "sha256:${"a".repeat(64)}",
            controller.state.value.selections[ArtworkSlot.ARTIST_HERO]?.assetId,
        )

        controller.bind("lib-b", "Midnight Oil", listOf("album-1"))
        advanceUntilIdle()
        controller.applySelections(
            libraryId = "lib-a",
            artistCredit = "Midnight Oil",
            albumIds = listOf("album-1"),
            mbid = mbid,
            selections = mapOf(
                ArtworkSlot.ARTIST_HERO to selection(ArtworkSlot.ARTIST_HERO, "e"),
            ),
        )
        assertEquals(
            "sha256:${"b".repeat(64)}",
            controller.state.value.selections[ArtworkSlot.ARTIST_HERO]?.assetId,
        )

        controller.applySelections(
            libraryId = "lib-b",
            artistCredit = "Midnight Oil",
            albumIds = listOf("album-1"),
            mbid = mbid,
            selections = mapOf(
                ArtworkSlot.ARTIST_HERO to selection(ArtworkSlot.ARTIST_HERO, "c"),
            ),
        )
        assertEquals(
            "sha256:${"c".repeat(64)}",
            controller.state.value.selections[ArtworkSlot.ARTIST_HERO]?.assetId,
        )
    }

    @Test
    fun lateExplicitResolutionFromPreviousLibraryCannotOverwriteTheNewLibrary() = runTest {
        val resolutionGate = CompletableDeferred<Unit>()
        val resolvedLibraries = mutableListOf<String>()
        val profileB = profile().copy(name = "Midnight Oil B")
        val store = object : ArtistEnrichmentSelectionStore, ArtistAssociationResolutionStore {
            override suspend fun load(libraryId: String, credit: String, albumIds: List<String>) =
                if (libraryId == "lib-a") throw ArtistAssociationAmbiguousException()
                else PersistedArtistProfile(profileB, stale = false, associationGroupKey = "group-b")

            override suspend fun save(
                libraryId: String,
                credit: String,
                albumIds: List<String>,
                profile: ArtistExternalProfile,
            ) = Unit

            override suspend fun clear(libraryId: String, credit: String, albumIds: List<String>) = Unit

            override suspend fun ambiguousAssociations(
                libraryId: String,
                credit: String,
                albumIds: List<String>,
            ) = listOf(
                ArtistAssociationCandidate("group-a", mbid, "A", listOf("album-1"), "USER", 1L),
                ArtistAssociationCandidate("group-a2", other, "A2", listOf("album-1"), "USER", 2L),
            )

            override suspend fun resolveAssociation(
                libraryId: String,
                credit: String,
                albumIds: List<String>,
                chosenAssociationGroupKey: String,
                chosenMbid: String,
            ): PersistedArtistProfile = withContext(NonCancellable) {
                resolutionGate.await()
                resolvedLibraries += libraryId
                PersistedArtistProfile(profile(), stale = false, associationGroupKey = chosenAssociationGroupKey)
            }
        }
        val controller = ArtistEnrichmentController(
            orchestrator = mockk(relaxed = true),
            profileStore = store,
            artworkStore = artworkStore(emptyList()),
            verifySelection = { it },
            scope = this,
        )
        controller.bind("lib-a", "Midnight Oil", listOf("album-1"))
        advanceUntilIdle()
        assertEquals(ArtistEnrichmentStatus.CHOOSING, controller.state.value.status)

        controller.resolveAssociation("Midnight Oil", "lib-a", listOf("album-1"), "group-a", mbid)
        runCurrent()
        controller.bind("lib-b", "Midnight Oil", listOf("album-1"))
        resolutionGate.complete(Unit)
        advanceUntilIdle()

        assertEquals(listOf("lib-a"), resolvedLibraries)
        assertEquals("Midnight Oil B", controller.state.value.profile?.name)
        assertEquals("group-b", controller.state.value.associationGroupKey)
        assertEquals(ArtistEnrichmentStatus.VERIFIED, controller.state.value.status)
    }
}
