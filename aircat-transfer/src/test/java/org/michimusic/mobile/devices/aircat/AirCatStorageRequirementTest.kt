package org.michimusic.mobile.devices.aircat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AirCatStorageRequirementTest {
    @Test fun safetyMarginMatchesTransferPreflight() {
        assertEquals(100L * 1024 * 1024, AirCatStorageRequirement.SAFETY_MARGIN_BYTES)
        assertEquals(100L * 1024 * 1024 + 512L,
            AirCatStorageRequirement.requiredBytes(512L))
    }
    @Test fun signedOverflowFailsClosed() {
        assertNull(AirCatStorageRequirement.requiredBytes(Long.MAX_VALUE))
        assertNull(AirCatStorageRequirement.requiredBytes(-1L))
        assertEquals(AirCatStorageRequirement.SAFETY_MARGIN_BYTES,
            AirCatStorageRequirement.requiredBytes(0L))
    }
}
