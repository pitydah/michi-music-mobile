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
}
