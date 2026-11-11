package org.michimusic.mobile.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WaveformEnvelopePreviewTest {
    @Test fun publishesOnlyCompletedDecodedFrameWindows() {
        val envelope = WaveformEnvelope(expectedFrames = 160, channels = 1, bins = 16)
        assertNull(envelope.completedPreview())
        envelope.acceptInterleaved(FloatArray(9) { .3f })
        assertNull(envelope.completedPreview())
        envelope.acceptInterleaved(FloatArray(10) { .5f })
        val first = envelope.completedPreview()!!
        assertEquals(1, first.completedBins)
        assertTrue(first.levels[0] > 0f)
        // Zero is a transport placeholder here; the consumer MUST inspect coverage.
        assertEquals(0f, first.levels[1], .0001f)
        envelope.acceptInterleaved(FloatArray(141) { .1f })
        assertEquals(15, envelope.completedPreview()!!.completedBins)
        assertEquals(16, envelope.finish()!!.size)
    }
}
