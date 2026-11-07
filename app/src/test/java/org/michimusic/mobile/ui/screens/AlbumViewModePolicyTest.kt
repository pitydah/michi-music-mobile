package org.michimusic.mobile.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class AlbumViewModePolicyTest {
    @Test fun cycleVisitsEachViewOnceAndReturnsToMosaic() {
        val start = LibraryViewMode.MOSAIC
        val coverflow = nextAlbumViewMode(start)
        val list = nextAlbumViewMode(coverflow)
        val end = nextAlbumViewMode(list)
        assertEquals(LibraryViewMode.COVERFLOW, coverflow)
        assertEquals(LibraryViewMode.LIST, list)
        assertEquals(start, end)
        assertEquals(3, setOf(start, coverflow, list).size)
    }

    @Test fun allLabelsIdentifyARealPresentation() {
        assertEquals("lista", albumViewModeLabel(LibraryViewMode.LIST))
        assertEquals("mosaico", albumViewModeLabel(LibraryViewMode.MOSAIC))
        assertEquals("CoverFlow", albumViewModeLabel(LibraryViewMode.COVERFLOW))
        assertNotEquals(albumViewModeLabel(LibraryViewMode.MOSAIC), albumViewModeLabel(LibraryViewMode.COVERFLOW))
    }
}
