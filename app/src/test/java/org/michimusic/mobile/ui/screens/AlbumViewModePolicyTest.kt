package org.michimusic.mobile.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class AlbumViewModePolicyTest {
    @Test fun cycleVisitsMosaicCoverFlowAndListInThatOrder() {
        val start = LibraryViewMode.MOSAIC
        val coverFlow = nextAlbumViewMode(start)
        val list = nextAlbumViewMode(coverFlow)
        val end = nextAlbumViewMode(list)
        assertEquals(LibraryViewMode.COVERFLOW, coverFlow)
        assertEquals(LibraryViewMode.LIST, list)
        assertEquals(start, end)
        assertEquals(3, LibraryViewMode.entries.size)
    }

    @Test fun allLabelsIdentifyARealPresentation() {
        assertEquals("lista", albumViewModeLabel(LibraryViewMode.LIST))
        assertEquals("mosaico", albumViewModeLabel(LibraryViewMode.MOSAIC))
        assertEquals("CoverFlow", albumViewModeLabel(LibraryViewMode.COVERFLOW))
        assertNotEquals(albumViewModeLabel(LibraryViewMode.MOSAIC), albumViewModeLabel(LibraryViewMode.LIST))
    }
}
