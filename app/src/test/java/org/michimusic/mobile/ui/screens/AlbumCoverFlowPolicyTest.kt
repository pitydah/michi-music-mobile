package org.michimusic.mobile.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.michimusic.core.models.Album
import org.michimusic.core.models.LibraryScope
import org.michimusic.core.models.Track
import org.michimusic.data.repository.LocalMediaRepository.LocalAlbum

class AlbumCoverFlowPolicyTest {
    private fun album(id: String, trackIds: List<String>, title: String = id) = LocalAlbum(
        album = Album(id = id, title = title, artist = "Artist"),
        tracks = trackIds.map { Track(id = it, title = it, album = title, artist = "Artist") },
    )

    @Test
    fun presentationMapsEveryUniqueAlbumBackToItsOpaqueNavigationIdentity() {
        val first = album("raw-a", listOf("track-a"), "First")
        val second = album("raw-b", listOf("track-b"), "Second")

        val presentation = albumCoverFlowPresentation(listOf(first, second), LibraryScope.All)

        assertEquals(2, presentation.albums.size)
        assertSame(first, presentation.albumById[presentation.albums[0].id])
        assertSame(second, presentation.albumById[presentation.albums[1].id])
        assertEquals(albumNavigationIdentity(first, LibraryScope.All), presentation.albums[0].id)
    }

    @Test
    fun ambiguousDuplicateIdentitiesAreExcludedInsteadOfBecomingIndices() {
        val duplicate = album("same", listOf("track"), "Same")
        val unique = album("unique", listOf("other"), "Unique")

        val presentation = albumCoverFlowPresentation(
            listOf(duplicate, duplicate.copy(), unique),
            LibraryScope.One("phone"),
        )

        assertEquals(listOf("Unique"), presentation.albums.map { it.title })
        assertEquals(listOf(unique), presentation.albumById.values.toList())
    }

    @Test
    fun transientEmptyRefreshRetainsSelectionAndRebindsChangedSnapshotIdentity() {
        val scope = LibraryScope.One("phone")
        val original = album("stable-album", listOf("track-a"), "Selected")
        val originalPresentation = albumCoverFlowPresentation(listOf(original), scope)
        val originalSelection = coverFlowSelection(
            originalPresentation.albums.single().id,
            originalPresentation,
        )

        val retained = reconcileCoverFlowSelection(
            selection = originalSelection,
            presentation = albumCoverFlowPresentation(emptyList(), scope),
            refreshInProgress = true,
        )
        assertEquals(originalSelection, retained)

        val refreshed = album("stable-album", listOf("track-a", "track-b"), "Selected")
        val refreshedPresentation = albumCoverFlowPresentation(listOf(refreshed), scope)
        val rebound = reconcileCoverFlowSelection(
            selection = retained,
            presentation = refreshedPresentation,
            refreshInProgress = false,
        )

        assertNotEquals(originalSelection?.id, rebound?.id)
        assertEquals(refreshedPresentation.albums.single().id, rebound?.id)
        assertEquals(originalSelection?.continuityKey, rebound?.continuityKey)
    }

    @Test
    fun confirmedDeletionFallsBackAndSettledEmptyClearsSelection() {
        val scope = LibraryScope.All
        val selected = album("selected", listOf("selected-track"), "Selected")
        val fallback = album("fallback", listOf("fallback-track"), "Fallback")
        val initial = albumCoverFlowPresentation(listOf(selected, fallback), scope)
        val selection = coverFlowSelection(initial.albums.first().id, initial)
        val afterDeletion = albumCoverFlowPresentation(listOf(fallback), scope)

        assertEquals(
            afterDeletion.albums.single().id,
            reconcileCoverFlowSelection(selection, afterDeletion, refreshInProgress = false)?.id,
        )
        assertNull(
            reconcileCoverFlowSelection(
                selection,
                albumCoverFlowPresentation(emptyList(), scope),
                refreshInProgress = false,
            ),
        )
    }

    @Test
    fun ambiguousContinuityKeyDoesNotGuessWhichChangedSnapshotWasSelected() {
        val scope = LibraryScope.All
        val old = album("same", listOf("old-track"), "Same")
        val oldPresentation = albumCoverFlowPresentation(listOf(old), scope)
        val selection = coverFlowSelection(oldPresentation.albums.single().id, oldPresentation)
        val first = album("same", listOf("new-a"), "Same")
        val second = album("same", listOf("new-b"), "Same")
        val ambiguous = albumCoverFlowPresentation(listOf(first, second), scope)

        val reconciled = reconcileCoverFlowSelection(selection, ambiguous, refreshInProgress = false)

        assertEquals(ambiguous.albums.first().id, reconciled?.id)
        assertNull(reconciled?.continuityKey)
    }
}
