package org.michimusic.mobile.ui.theme

import org.junit.Assert.*
import org.junit.Test

class WaveformEnvelopeTest {
    @Test fun refusesNoAudio() {
        assertNull(WaveformEnvelope(expectedFrames = 160, channels = 1, bins = 16).finish())
    }

    @Test fun monoConstantSignalProducesRealBoundedBins() {
        val reducer = WaveformEnvelope(expectedFrames = 160, channels = 1, bins = 16)
        reducer.acceptInterleaved(FloatArray(160) { 0.5f })
        val envelope = requireNotNull(reducer.finish())
        assertEquals(16, envelope.size)
        assertTrue(envelope.all { it in 0f..1f && kotlin.math.abs(it - 0.5f) < 0.0001f })
    }

    @Test fun chunkBoundariesDoNotChangeResults() {
        val pcm = FloatArray(1024) { (it % 71) / 35.0f - 1f }
        val whole = WaveformEnvelope(512, 2, 32).apply { acceptInterleaved(pcm) }.finish()
        val chunked = WaveformEnvelope(512, 2, 32).apply {
            acceptInterleaved(pcm, 0, 160)
            acceptInterleaved(pcm, 160, 864)
        }.finish()
        assertEquals(whole, chunked)
    }

    @Test fun corruptPcmDoesNotBecomeSyntheticWaveform() {
        val reducer = WaveformEnvelope(160, 1, 16)
        reducer.acceptInterleaved(FloatArray(160) { if (it == 27) Float.NaN else 0.2f })
        assertNull(reducer.finish())
    }

    @Test fun shortDecodeDoesNotPadUnobservedBins() {
        val reducer = WaveformEnvelope(1600, 1, 32)
        reducer.acceptInterleaved(FloatArray(20) { 0.3f })
        assertNull(reducer.finish())
    }

    @Test fun mostOfTrackMissingIsRejected() {
        val reducer = WaveformEnvelope(1600, 1, 16)
        reducer.acceptInterleaved(FloatArray(800) { 0.3f })
        assertNull(reducer.finish())
    }

    @Test fun massivelyIncorrectDurationDoesNotCompressExtraAudioIntoLastBin() {
        val reducer = WaveformEnvelope(160, 1, 16)
        reducer.acceptInterleaved(FloatArray(230) { .5f })
        assertNull(reducer.finish())
    }

    @Test fun minorEncoderPaddingIsAllowed() {
        val reducer = WaveformEnvelope(160, 1, 16)
        reducer.acceptInterleaved(FloatArray(168) { .5f })
        assertNotNull(reducer.finish())
    }

    @Test fun incompleteStereoFrameRejected() {
        val reducer = WaveformEnvelope(160, 2, 16)
        try {
            reducer.acceptInterleaved(floatArrayOf(0.1f, 0.2f, 0.3f))
            fail("Expected invalid incomplete PCM frame")
        } catch (_: IllegalArgumentException) { }
    }
}
