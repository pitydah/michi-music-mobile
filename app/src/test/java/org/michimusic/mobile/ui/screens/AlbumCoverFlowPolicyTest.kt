package org.michimusic.mobile.ui.screens

import org.junit.Assert.assertEquals
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
}
