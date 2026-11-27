package org.michimusic.mobile.ui.components

import org.michimusic.mobile.artist.ArtistProfileScope
import org.junit.Assert.assertEquals
import org.junit.Test

class ArtistBulkPreviewInputsTest {
    @Test fun oneLibraryConvertsLocalVisibleRowsWithoutGuessingIdentity() {
        val entry = ArtistMosaicEntry("Artist", listOf("a", "b"), 2, 14)
        val row = artistBulkCreditsForOneLibrary("lib-1", listOf(entry)).single()
        assertEquals("lib-1", row.libraryId)
        assertEquals(listOf("a", "b"), row.albumIds)
        assertEquals(14, row.trackCount)
    }

    @Test fun allLibraryAggregateUsesProvenOneLibraryAlbumIdsNotUnion() {
        val entry = ArtistMosaicEntry("Artist", listOf("other", "ours"), 2, 10,
            profileScopes = listOf(ArtistProfileScope("lib-1", "Artist", listOf("ours"))))
        val row = artistBulkCreditsForOneLibrary("lib-1", listOf(entry)).single()
        assertEquals(listOf("ours"), row.albumIds)
    }

    @Test fun multipleContributingLibrariesCannotBeCollapsedToOne() {
        val entry = ArtistMosaicEntry("Artist", listOf("a", "b"), 2, 10,
            profileScopes = listOf(ArtistProfileScope("lib-1", "Artist", listOf("a")),
                ArtistProfileScope("lib-2", "Artist", listOf("b"))))
        val row = artistBulkCreditsForOneLibrary("lib-1", listOf(entry)).single()
        assertEquals("", row.libraryId)
        assertEquals(emptyList<String>(), row.albumIds)
    }

    @Test fun contradictoryScopeLibraryMustNotBeRewrittenToSelectedLibrary() {
        val entry = ArtistMosaicEntry("Artist", listOf("a"), 1, 3,
            profileScopes = listOf(ArtistProfileScope("lib-2", "Artist", listOf("a"))))
        val row = artistBulkCreditsForOneLibrary("lib-1", listOf(entry)).single()
        assertEquals("lib-2", row.libraryId)
    }
}
