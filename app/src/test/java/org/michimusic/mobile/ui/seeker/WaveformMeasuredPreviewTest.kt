package org.michimusic.mobile.ui.seeker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WaveformMeasuredPreviewTest {
    @Test fun onlyClosedTimeWindowsHaveBars() {
        val preview = WaveformMeasuredPreview(
            levels = List(16) { if (it < 7) (it + 1) / 16f else 0f },
            completedBins = 7, totalBins = 16,
        )
        val bars = preview.barsForWidth(8)
        assertEquals(8, bars.size)
        assertEquals(0.125f, bars[0]!!, .0001f)
        assertEquals(0.375f, bars[2]!!, .0001f)
        assertNull(bars[3]) // its 2-bin window is NOT wholly decoded
        assertTrue(bars.drop(3).all { it == null })
    }

    @Test fun measuredSilenceIsZeroButUnknownIsNull() {
        val preview = WaveformMeasuredPreview(List(16) { 0f }, 4, 16)
        val bars = preview.barsForWidth(8)
        assertEquals(0f, bars[0]!!, .0001f)
        assertEquals(0f, bars[1]!!, .0001f)
        assertNull(bars[2])
    }

    @Test fun malformedSnapshotsFailClosed() {
        assertEquals(emptyList<Float?>(), WaveformMeasuredPreview(
            List(16) { Float.NaN }, 8, 16).barsForWidth(8))
        assertEquals(emptyList<Float?>(), WaveformMeasuredPreview(
            List(16) { .5f }, 17, 16).barsForWidth(8))
    }
}
