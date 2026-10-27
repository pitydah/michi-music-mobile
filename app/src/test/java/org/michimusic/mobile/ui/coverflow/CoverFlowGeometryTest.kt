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
}
