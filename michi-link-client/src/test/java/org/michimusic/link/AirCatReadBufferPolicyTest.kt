package org.michimusic.link

import org.junit.Assert.assertEquals

import org.junit.Test

class AirCatReadBufferPolicyTest {
    @Test fun rejectsZeroCapacity() = assertEquals("AIRC_BUFFER_TOO_SMALL", AirCatReadBufferPolicy.refusal(0))
    @Test fun rejectsTinyCapacity() = assertEquals("AIRC_BUFFER_TOO_SMALL", AirCatReadBufferPolicy.refusal(16))
    @Test fun permitsBoundedWindow() {
        assertEquals(null, AirCatReadBufferPolicy.refusal(64 * 1024))
        assertEquals(null, AirCatReadBufferPolicy.refusal(AirCatReadBufferPolicy.MAX_BYTES))
    }
    @Test fun rejectsExcessiveAllocation() = assertEquals("AIRC_BUFFER_TOO_LARGE", AirCatReadBufferPolicy.refusal(Int.MAX_VALUE))
}
