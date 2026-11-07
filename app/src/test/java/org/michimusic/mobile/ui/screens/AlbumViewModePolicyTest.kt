package org.michimusic.mobile.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class AlbumViewModePolicyTest {
    @Test fun cycleAlternatesBetweenMosaicAndList() {
        val start = LibraryViewMode.MOSAIC
        val list = nextAlbumViewMode(start)
        val end = nextAlbumViewMode(list)
        assertEquals(LibraryViewMode.LIST, list)
        assertEquals(start, end)
        assertEquals(2, LibraryViewMode.entries.size)
    }

    @Test fun allLabelsIdentifyARealPresentation() {
        assertEquals("lista", albumViewModeLabel(LibraryViewMode.LIST))
        assertEquals("mosaico", albumViewModeLabel(LibraryViewMode.MOSAIC))
        assertNotEquals(albumViewModeLabel(LibraryViewMode.MOSAIC), albumViewModeLabel(LibraryViewMode.LIST))
    }
}
