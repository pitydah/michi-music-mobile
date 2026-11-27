package org.michimusic.mobile.artist

import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.michimusic.mobile.artist.artwork.ArtistArtworkCandidate
import org.michimusic.mobile.artist.artwork.ArtistArtworkSelection
import org.michimusic.mobile.artist.artwork.ArtistArtworkStore
import org.michimusic.mobile.artist.artwork.ArtistIdentityAnchor
import org.michimusic.mobile.artist.artwork.ArtworkFocus
import org.michimusic.mobile.artist.artwork.ArtworkSlot
import org.michimusic.mobile.artist.artwork.DownloadedArtwork

/**
 * K48-09: the panel must never read "datos enriquecidos borrados" before both
 * stores confirmed the deletion. The pending message is a different, honest one.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ArtistEnrichmentControllerClearTest {
    private val mbid = "05072650-4b05-4e8b-83d0-dba2576e306a"

    private fun profile() = ArtistExternalProfile(
        mbid = mbid,
        name = "Midnight Oil",
        biography = "Banda australiana.",
        biographySourceUrl = "https://es.wikipedia.org/wiki/Midnight_Oil",
        wikidataQid = "Q48284",
    )

    private var artworkClears = 0

    private fun artworkStore() = object : ArtistArtworkStore {
        override suspend fun load(anchor: ArtistIdentityAnchor) = emptyList<ArtistArtworkSelection>()
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
            artworkClears++
        }
        override suspend fun counts() = 0 to 0
        override fun observeLibraryRevision(libraryId: String) = flowOf(0L)
    }

    @Test
    fun clearAnnouncesSuccessOnlyAfterBothStoresConfirm() = runTest {
        val gate = CompletableDeferred<Unit>()
        var profileClears = 0
        val store = object : ArtistEnrichmentSelectionStore {
            override suspend fun load(
                libraryId: String,
                credit: String,
                albumIds: List<String>,
            ) = PersistedArtistProfile(profile(), stale = false)

            override suspend fun save(
                libraryId: String,
                credit: String,
                albumIds: List<String>,
                profile: ArtistExternalProfile,
            ) = Unit

            override suspend fun clear(libraryId: String, credit: String, albumIds: List<String>) {
                gate.await()
                profileClears++
            }
        }
        val controller = ArtistEnrichmentController(
            orchestrator = mockk(relaxed = true),
            profileStore = store,
            artworkStore = artworkStore(),
            verifySelection = { it },
            scope = this,
        )
        val albums = listOf("album-a")
        controller.bind("library-a", "Midnight Oil", albums)
        advanceUntilIdle()

        controller.requestDelete("Midnight Oil", "library-a", albums)
        controller.confirmDelete("Midnight Oil", "library-a", albums)
        runCurrent()

        // The delete is suspended in the store: nothing may claim success yet.
        assertEquals(0, profileClears)
        assertEquals(0, artworkClears)
        assertEquals("Borrando datos enriquecidos…", controller.state.value.message)

        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(1, profileClears)
        assertEquals(1, artworkClears)
        assertEquals("Datos enriquecidos borrados.", controller.state.value.message)
        controller.release()
    }
}
