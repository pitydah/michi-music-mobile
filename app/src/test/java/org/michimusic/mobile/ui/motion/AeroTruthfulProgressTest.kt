package org.michimusic.mobile.ui.motion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AeroTruthfulProgressTest {
    @Test fun accessibilityModesKeepProgressStaticButTruthful() {
        assertTrue(aeroProgressMotionEnabled(reducedMotion = false, highContrast = false))
        assertFalse(aeroProgressMotionEnabled(reducedMotion = true, highContrast = false))
        assertFalse(aeroProgressMotionEnabled(reducedMotion = false, highContrast = true))
        assertFalse(aeroProgressMotionEnabled(reducedMotion = true, highContrast = true))
        // The measured value stays authoritative even though no interpolation runs.
        assertEquals(0.4f, aeroMeasuredFraction(4, 10)!!, 0f)
    }

    @Test fun unknownDenominatorIsNotZeroPercent() {
        assertNull(aeroMeasuredFraction(0L, 0L))
        assertNull(aeroMeasuredFraction(30L, -1L))
        assertNull(aeroMeasuredFraction(-1L, 200L))
        assertNull(aeroReportedFraction(null))
        assertNull(aeroReportedFraction(Float.NaN))
        assertNull(aeroReportedFraction(Float.POSITIVE_INFINITY))
        assertNull(aeroReportedFraction(-0.1f))
    }

    @Test fun measuredFractionsCannotExceedTheirRange() {
        assertEquals(0f, aeroMeasuredFraction(0L, 100L)!!, 0.0001f)
        assertEquals(0.5f, aeroMeasuredFraction(50L, 100L)!!, 0.0001f)
        assertEquals(1f, aeroMeasuredFraction(120L, 100L)!!, 0.0001f)
        assertEquals(1f, aeroReportedFraction(2.5f)!!, 0.0001f)
    }

    @Test fun hugeByteCountsDoNotOverflow() {
        assertEquals(1f, aeroMeasuredFraction(Long.MAX_VALUE, Long.MAX_VALUE)!!, 0f)
        assertEquals(0.5f, aeroMeasuredFraction(Long.MAX_VALUE / 2L, Long.MAX_VALUE)!!, 0.001f)
    }
}
