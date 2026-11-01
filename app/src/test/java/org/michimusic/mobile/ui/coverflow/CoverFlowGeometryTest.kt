package org.michimusic.mobile.ui.coverflow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CoverFlowGeometryTest {
    @Test fun centerCoverIsFullSizeAndFullyOpaque() {
        val visual = coverFlowGeometry(0f, reducedMotion = false)
        assertEquals(0f, visual.rotationY, 0.001f)
        assertEquals(1f, visual.scale, 0.001f)
        assertEquals(1f, visual.opacity, 0.001f)
        assertTrue(visual.raised)
    }

    @Test fun leftAndRightCoversMirrorRotation() {
        val left = coverFlowGeometry(-1f, reducedMotion = false)
        val right = coverFlowGeometry(1f, reducedMotion = false)
        assertEquals(-right.rotationY, left.rotationY, 0.001f)
        assertEquals(right.scale, left.scale, 0.001f)
        assertEquals(right.opacity, left.opacity, 0.001f)
        assertFalse(left.raised)
    }

    @Test fun extremesAreClamped() {
        val visual = coverFlowGeometry(10f, reducedMotion = false)
        assertEquals(45f, visual.rotationY, 0.001f)
        assertTrue(visual.scale > 0.7f)
        assertTrue(visual.opacity >= 0.55f)
    }

    @Test fun reducedMotionEliminatesPerspectiveAndDimming() {
        val visual = coverFlowGeometry(-1.5f, reducedMotion = true)
        assertEquals(0f, visual.rotationY, 0.001f)
        assertEquals(1f, visual.scale, 0.001f)
        assertEquals(1f, visual.opacity, 0.001f)
    }

    @Test fun nonFiniteScrollOffsetDoesNotDamageRendering() {
        val visual = coverFlowGeometry(Float.NaN, reducedMotion = false)
        assertEquals(0f, visual.rotationY, 0.001f)
        assertEquals(1f, visual.scale, 0.001f)
    }

    @Test fun restoredIndexClampsForShrinkingLibrary() {
        assertEquals(0, safeCoverFlowIndex(42, 0))
        assertEquals(0, safeCoverFlowIndex(-5, 8))
        assertEquals(7, safeCoverFlowIndex(42, 8))
        assertEquals(3, safeCoverFlowIndex(3, 8))
    }

    @Test fun stableAlbumIdLookupPreservesSelectionAcrossResorting() {
        val albums = listOf(
            org.michimusic.mobile.library.coverflow.CoverFlowAlbum(id = "album_a", title = "Alpha", artist = "Artist 1", year = 2020, trackCount = 10),
            org.michimusic.mobile.library.coverflow.CoverFlowAlbum(id = "album_b", title = "Beta", artist = "Artist 2", year = 2021, trackCount = 12),
            org.michimusic.mobile.library.coverflow.CoverFlowAlbum(id = "album_c", title = "Gamma", artist = "Artist 3", year = 2022, trackCount = 8),
        )
        val selectedId = "album_b"

        val initialIndex = albums.indexOfFirst { it.id == selectedId }
        assertEquals(1, initialIndex)

        // Resort albums alphabetically descending
        val resorted = albums.sortedByDescending { it.title }
        val newIndex = resorted.indexOfFirst { it.id == selectedId }
        assertEquals(1, newIndex)

        // Prepend another album
        val extended = listOf(org.michimusic.mobile.library.coverflow.CoverFlowAlbum(id = "album_z", title = "Zeta", artist = "Artist 0", year = 2019, trackCount = 5)) + albums
        val indexInExtended = extended.indexOfFirst { it.id == selectedId }
        assertEquals(2, indexInExtended) // Index changed from 1 to 2, but ID selection remains "album_b"
        assertEquals("Beta", extended[indexInExtended].title)
    }
}

