package org.michimusic.link

import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

class AirCatMetadataBudgetTest {
    @Test fun exactLimitAccepts() {
        val b = AirCatMetadataBudget(4)
        assertTrue(b.accept(2));assertTrue(b.accept(2));assertEquals(4,b.consumedBytes)
    }
    @Test fun exceedsLimitBeforeAppending() {
        val b=AirCatMetadataBudget(4)
        assertTrue(b.accept(3));assertFalse(b.accept(2));assertEquals(3,b.consumedBytes)
    }
    @Test fun hugeChunkDoesNotOverflowCounter() {
        val b=AirCatMetadataBudget(64)
        assertFalse(b.accept(Int.MAX_VALUE));assertEquals(0,b.consumedBytes)
    }
    @Test fun negativeChunkRejected() = assertFalse(AirCatMetadataBudget(4).accept(-1))
    @Test fun zeroByteProgressSafe() = assertTrue(AirCatMetadataBudget(4).accept(0))
    @Test fun missingLengthMustUseStreamBudget() = assertTrue(AirCatMetadataBudget.contentLengthAllowed(null,100))
    @Test fun negativeAdvertisedLengthRejected() = assertFalse(AirCatMetadataBudget.contentLengthAllowed("-1",100))
    @Test fun overlongAdvertisedLengthRejected() = assertFalse(AirCatMetadataBudget.contentLengthAllowed("101",100))
    @Test fun malformedAdvertisedLengthRejected() = assertFalse(AirCatMetadataBudget.contentLengthAllowed("1, 100",100))
    @Test fun exactAdvertisedLimitAccepted() = assertTrue(AirCatMetadataBudget.contentLengthAllowed("100",100))
    @Test fun maxManifestLimitBounded() = assertEquals(8*1024*1024,AirCatMetadataBudget.MANIFEST_MAX_BYTES)
}
