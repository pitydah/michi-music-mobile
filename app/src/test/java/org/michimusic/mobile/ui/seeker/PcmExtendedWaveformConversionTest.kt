package org.michimusic.mobile.ui.seeker

import org.junit.Assert.*
import org.junit.Test

class PcmExtendedWaveformConversionTest {
    @Test fun unsignedPcm8HasTrueSilenceAndExactNegativePeak() {
        assertEquals(-1f, normalizePcm8(0.toByte()), 0f)
        assertEquals(0f, normalizePcm8(128.toByte()), 0f)
        assertEquals(127f / 128f, normalizePcm8(255.toByte()), 0f)
    }
    @Test fun pcm32IsSignedWithoutOverflowOrFabricatedPeak() {
        assertEquals(-1f, normalizePcm32(Int.MIN_VALUE), 0f)
        assertEquals(0f, normalizePcm32(0), 0f)
        assertTrue(normalizePcm32(Int.MAX_VALUE) <= 1f)
        assertEquals(.5f, normalizePcm32(1_073_741_824), 0f)
    }
    @Test fun outputBufferBoundsMustBeCompleteInterleavedFrames() {
        assertTrue(validPcmOutputRange(5, 12, 20, 6))
        assertFalse(validPcmOutputRange(-1, 12, 20, 6))
        assertFalse(validPcmOutputRange(5, 17, 20, 1))
        assertFalse(validPcmOutputRange(5, 11, 20, 6))
        assertFalse(validPcmOutputRange(Int.MAX_VALUE, 1, Int.MAX_VALUE, 1))
        assertFalse(validPcmOutputRange(0, 12, 20, 0))
    }
    @Test fun cacheRejectsMutatingAndUnverifiableSources() {
        assertTrue(stableWaveformAsset(10, 2, 10, 2))
        assertFalse(stableWaveformAsset(10, 2, 11, 2))
        assertFalse(stableWaveformAsset(10, 2, 10, 3))
        assertFalse(stableWaveformAsset(0, 2, 0, 2))
    }
}
