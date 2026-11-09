package org.michimusic.mobile.ui.seeker

import org.junit.Assert.*
import org.junit.Test

class WaveformChunkPolicyTest {
    @Test fun boundsLargeDecoderBuffersWithoutTruncatingFrames() {
        assertEquals(2048 * 8, boundedWaveformChunkSamples(900_000, 8))
        assertEquals(7 * 2, boundedWaveformChunkSamples(7, 2))
        var left = 99_001
        var sum = 0
        while (left > 0) {
            val samples = boundedWaveformChunkSamples(left, 3)
            val frames = samples / 3
            assertTrue(frames in 1..2048)
            sum += frames
            left -= frames
        }
        assertEquals(99_001, sum)
    }
    @Test fun rejectsPartialFramesAndImpossibleConfigurations() {
        assertEquals(2048, completePcmFrames(2048 * 3 * 2, 3, 2))
        assertEquals(0, completePcmFrames(0, 4, 8))
        assertNull(completePcmFrames(5, 2, 2))
        assertNull(completePcmFrames(12, 5, 2))
        assertNull(completePcmFrames(-1, 2, 2))
        assertEquals(0, boundedWaveformChunkSamples(Int.MAX_VALUE, 0))
        assertEquals(0, boundedWaveformChunkSamples(Int.MAX_VALUE, 8, 9999))
    }
}
