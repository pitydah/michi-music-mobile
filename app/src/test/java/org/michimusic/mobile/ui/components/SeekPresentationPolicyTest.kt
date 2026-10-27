package org.michimusic.mobile.ui.components

import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull

class SeekPresentationPolicyTest {
    @Test fun unavailableDurationIsIndeterminate() {
        assertNull(seekProgressFraction(2f, 0f))
        assertNull(seekProgressFraction(2f, -1f))
        assertNull(seekProgressFraction(2f, Float.NaN))
        assertNull(seekProgressFraction(2f, Float.POSITIVE_INFINITY))
    }
    @Test fun invalidPositionCannotLeak() {
        assertNull(seekProgressFraction(Float.NaN, 300f))
        assertNull(seekProgressFraction(Float.NEGATIVE_INFINITY, 300f))
    }
    @Test fun measuredProgressIsClamped() {
        assertEquals(0.5f, seekProgressFraction(50f, 100f))
        assertEquals(0f, seekProgressFraction(-50f, 100f))
        assertEquals(1f, seekProgressFraction(500f, 100f))
    }
    @Test fun unknownDurationNeverGeneratesSeek() {
        assertNull(seekPositionForFraction(0.5f, 0f))
        assertNull(seekPositionForFraction(0.5f, Float.NaN))
        assertNull(seekPositionForFraction(Float.NaN, 100f))
    }
    @Test fun validSeekIsClamped() {
        assertEquals(0f, seekPositionForFraction(-4f, 100f))
        assertEquals(25f, seekPositionForFraction(0.25f, 100f))
        assertEquals(100f, seekPositionForFraction(5f, 100f))
    }
}
