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
        assertEquals(30f, visual.rotationY, 0.001f)
        assertTrue(visual.scale > 0.8f)
        assertTrue(visual.opacity >= 0.68f)
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

    @Test fun pageWidthFollowsRealViewportWithoutOverflow() {
        assertEquals(0f, coverFlowPageWidthDp(0f), 0.001f)
        assertEquals(0f, coverFlowPageWidthDp(Float.NaN), 0.001f)
        assertEquals(100f, coverFlowPageWidthDp(100f), 0.001f)
        assertEquals(217.6f, coverFlowPageWidthDp(320f), 0.01f)
        assertEquals(244.8f, coverFlowPageWidthDp(360f), 0.01f)
        assertEquals(272f, coverFlowPageWidthDp(412f), 0.01f)
        assertEquals(272f, coverFlowPageWidthDp(900f), 0.01f)
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

    @Test fun stageSizeAlwaysContainsArtworkWithoutHardcodedHeight() {
        assertEquals(0f, coverFlowStageHeightDp(Float.NaN), 0.001f)
        assertEquals(0f, coverFlowStageHeightDp(0f), 0.001f)
        assertEquals(245.6f, coverFlowStageHeightDp(320f), 0.01f)
        assertEquals(272.8f, coverFlowStageHeightDp(360f), 0.01f)
        assertEquals(300f, coverFlowStageHeightDp(412f), 0.01f)
    }

    @Test fun progressAndPagerKeysHaveUnambiguousSemantics() {
        assertEquals(null, coverFlowPositionLabel(0, 1))
        assertEquals(null, coverFlowPositionLabel(-1, 3))
        assertEquals("2 de 6", coverFlowPositionLabel(1, 6))
        assertTrue(coverFlowPageKey("same", 0) != coverFlowPageKey("same", 1))
    }

    @Test fun stableKeysSurviveReorderAndInsertionWithoutRelabellingEveryCover() {
        val initial = coverFlowStablePageKeys(listOf("album_a", "album_b", "album_c"))
        val reordered = coverFlowStablePageKeys(listOf("album_c", "album_a", "album_b"))
        assertEquals(listOf("album_a", "album_b", "album_c"), initial)
        assertEquals(listOf("album_c", "album_a", "album_b"), reordered)
        assertEquals("album_b", coverFlowStablePageKeys(listOf("new", "album_b"))[1])
    }

    @Test fun repeatedIdsRemainUniqueAndDoNotPolluteUniqueNeighbors() {
        val ids = coverFlowStablePageKeys(listOf("same", "distinct", "same", "other"))
        assertEquals(4, ids.toSet().size)
        assertEquals("distinct", ids[1])
        assertEquals("other", ids[3])
        assertTrue(ids[0] != ids[2])
    }
}

