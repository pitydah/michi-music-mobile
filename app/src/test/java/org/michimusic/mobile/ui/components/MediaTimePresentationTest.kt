package org.michimusic.mobile.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

class MediaTimePresentationTest {
    @Test fun durationUnknownAndLongTracks() {
        assertEquals("—", mediaDurationLabel(0))
        assertEquals("—", mediaDurationLabel(-1))
        assertEquals("0:00", mediaDurationLabel(1))
        assertEquals("0:59", mediaDurationLabel(59_999))
        assertEquals("1:00", mediaDurationLabel(60_000))
        assertEquals("1:02:03", mediaDurationLabel(3_723_000))
        assertEquals("596523:14:07", mediaDurationLabel(2_147_483_647_000L))
    }
    @Test fun elapsedUsesValidZeroButRejectsCorruptValues() {
        assertEquals("0:00", mediaElapsedLabel(0f))
        assertEquals("1:02:03", mediaElapsedLabel(3723f))
        assertEquals("—:—", mediaElapsedLabel(Float.NaN))
        assertEquals("—:—", mediaElapsedLabel(Float.POSITIVE_INFINITY))
        assertEquals("—:—", mediaElapsedLabel(-1f))
    }
    @Test fun remainingNeverInventsASeekableTimeline() {
        assertEquals("—:—", mediaRemainingLabel(0f, 0f))
        assertEquals("—:—", mediaRemainingLabel(Float.NaN, 40f))
        assertEquals("—:—", mediaRemainingLabel(-1f, 40f))
        assertEquals("—:—", mediaRemainingLabel(1f, Float.POSITIVE_INFINITY))
        assertEquals("-0:00", mediaRemainingLabel(80f, 40f))
        assertEquals("-1:01", mediaRemainingLabel(1f, 62f))
        assertEquals("-1:02:03", mediaRemainingLabel(0f, 3723f))
    }
}
