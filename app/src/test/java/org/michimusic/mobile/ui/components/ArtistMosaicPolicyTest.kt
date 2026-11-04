package org.michimusic.mobile.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

class ArtistMosaicPolicyTest {
    @Test fun tileWidthScalesWithAccessibilityFonts() {
        assertEquals(132f, artistMosaicMinCellWidthDp(1f))
        assertEquals(168f, artistMosaicMinCellWidthDp(1.3f))
        assertEquals(240f, artistMosaicMinCellWidthDp(2f))
    }

    @Test fun sortingAndMonogramsDoNotSplitCollaborations() {
        val list = listOf(
            ArtistMosaicEntry("Patti Austin & James Ingram", listOf("b"), 1, 1),
            ArtistMosaicEntry("Midnight Oil", listOf("a"), 1, 1),
        )
        assertEquals(listOf("Midnight Oil", "Patti Austin & James Ingram"),
            orderedArtistMosaicEntries(list).map { it.credit })
        assertEquals("PA", artistMosaicMonogram("Patti Austin & James Ingram"))
        assertEquals("MO", artistMosaicMonogram("Midnight Oil"))
        assertEquals("♫", artistMosaicMonogram("  "))
    }
}
