package org.michimusic.mobile.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class AlbumViewModePolicyTest {
    @Test fun cycleVisitsFourLibraryPresentationsInOrder() {
        val start = LibraryViewMode.MOSAIC
        val coverFlow = nextAlbumViewMode(start)
        val cards = nextAlbumViewMode(coverFlow)
        val list = nextAlbumViewMode(cards)
        val end = nextAlbumViewMode(list)
        assertEquals(LibraryViewMode.COVERFLOW, coverFlow)
        assertEquals(LibraryViewMode.CARDS, cards)
        assertEquals(LibraryViewMode.LIST, list)
        assertEquals(start, end)
        assertEquals(4, LibraryViewMode.entries.size)
    }

    @Test fun allLabelsIdentifyARealPresentation() {
        assertEquals("lista", albumViewModeLabel(LibraryViewMode.LIST))
        assertEquals("mosaico", albumViewModeLabel(LibraryViewMode.MOSAIC))
        assertEquals("tarjetas", albumViewModeLabel(LibraryViewMode.CARDS))
        assertEquals("CoverFlow", albumViewModeLabel(LibraryViewMode.COVERFLOW))
        assertNotEquals(albumViewModeLabel(LibraryViewMode.MOSAIC), albumViewModeLabel(LibraryViewMode.LIST))
    }
}
