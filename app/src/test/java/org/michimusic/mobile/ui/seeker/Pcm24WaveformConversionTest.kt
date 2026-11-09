package org.michimusic.mobile.ui.seeker

import java.nio.ByteBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Pcm24WaveformConversionTest {
    @Test fun silenceAndExtremesAreDecodedWithCorrectSign() {
        val b = ByteBuffer.wrap(byteArrayOf(
            0, 0, 0,
            0, 0, -128,
            -1, -1, 127,
        ))
        assertEquals(0f, readPcm24Sample(b), 0.00001f)
        assertEquals(-1f, readPcm24Sample(b), 0.00001f)
        assertTrue(readPcm24Sample(b) > .999f)
    }
    @Test fun halfScaleLittleEndianSignExtensionIsValid() {
        val b = ByteBuffer.wrap(byteArrayOf(0, 0, 64, 0, 0, -64))
        assertEquals(.5f, readPcm24Sample(b), .00001f)
        assertEquals(-.5f, readPcm24Sample(b), .00001f)
    }
}
