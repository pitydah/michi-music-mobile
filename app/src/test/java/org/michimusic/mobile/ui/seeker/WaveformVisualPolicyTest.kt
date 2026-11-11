package org.michimusic.mobile.ui.seeker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WaveformVisualPolicyTest {
    @Test fun narrowWidthGroupsActualPeaksWithoutInventingValues() {
        assertEquals(listOf(0.8f,0.6f),
            waveformBarsForWidth(listOf(0.2f,0.8f,0.1f,0.6f),2))
    }
    @Test fun emptyAndInvalidDimensionsAreHandled() {
        assertTrue(waveformBarsForWidth(emptyList(), 30).isEmpty())
        assertTrue(waveformBarsForWidth(listOf(0.3f),0).isEmpty())
    }
    @Test fun nonFiniteBinsDoNotPropagateToCanvas() {
        val bins = waveformBarsForWidth(listOf(Float.NaN,Float.POSITIVE_INFINITY,-2f,4f),2)
        assertEquals(listOf(0f,1f),bins)
    }
    @Test fun resizingNeverCreatesMoreBarsThanInput() {
        assertEquals(3, waveformBarsForWidth(listOf(0.1f,0.2f,0.3f),100).size)
    }

    @Test fun compressedMasterRegainsReadableRelativeDynamics() {
        val compressed = listOf(0.60f, 0.62f, 0.58f, 0.64f, 0.61f, 0.59f, 0.63f)
        val levels = waveformRenderLevels(compressed, compressed.size)

        assertEquals(compressed.size, levels.size)
        assertTrue(levels.all { it.isFinite() && it in 0f..1f })
        assertTrue(levels.max() - levels.min() > 0.20f)
    }

    @Test fun silenceNeverBecomesSound() {
        val levels = waveformRenderLevels(listOf(0f, 0f, 0f, 0f), 4)

        assertEquals(listOf(0f, 0f, 0f, 0f), levels)
    }

    @Test fun isolatedTransientSurvivesContinuousSmoothing() {
        val levels = waveformContinuousForWidth(listOf(0.1f, 0.1f, 0.9f, 0.1f, 0.1f), 5)

        assertTrue(levels[2] >= 0.70f)
        assertTrue(levels.all { it in 0f..1f })
    }

    @Test fun barsAndContinuousUseDifferentGeometryForTheSameInput() {
        val input = listOf(0.2f, 0.9f, 0.2f, 0.9f, 0.2f)
        val bars = waveformRenderLevels(input, 5)
        val continuous = waveformContinuousForWidth(input, 5)

        assertTrue(bars != continuous)
        assertTrue(bars[1] > continuous[1])
    }

    @Test fun nonFiniteSamplesCannotProduceInvalidLevels() {
        val levels = waveformRenderLevels(
            listOf(Float.NaN, Float.POSITIVE_INFINITY, -2f, 4f, 0.5f),
            5,
        )

        assertTrue(levels.all { it.isFinite() && it in 0f..1f })
    }
}
