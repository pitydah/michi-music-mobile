package org.michimusic.mobile.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AeroCapsuleMetricsTest {
    @Test
    fun capsuleUsesMachinedPillDimensions() {
        assertEquals(16f, AeroCapsuleMetrics.RailHeightDp, 0.001f)
        assertEquals(9f, AeroCapsuleMetrics.ChannelHeightDp, 0.001f)
        assertEquals(3f, AeroCapsuleMetrics.InsetDp, 0.001f)
        assertTrue(AeroCapsuleMetrics.ChannelHeightDp < AeroCapsuleMetrics.RailHeightDp)
        assertEquals(8f, AeroCapsuleMetrics.RailHeightDp / 2f, 0.001f)
    }

    @Test
    fun thumbStaysIntegratedAndTargetRemainsAccessible() {
        assertTrue(AeroCapsuleMetrics.ThumbDp in 14f..16f)
        assertTrue(AeroCapsuleMetrics.ThumbDraggingDp in 14f..16f)
        assertTrue(AeroCapsuleMetrics.ThumbDraggingDp >= AeroCapsuleMetrics.ThumbDp)
        assertEquals(48f, AeroCapsuleMetrics.TouchTargetDp, 0.001f)
    }
}
