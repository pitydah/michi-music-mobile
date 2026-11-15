package org.michimusic.mobile.artist.artwork

import org.junit.Assert.assertEquals
import org.junit.Test

class CommonsCategoryContinuationPolicyTest {
    @Test fun secondPageAllowedOnlyWithVerifiedContinuationAndBudget() {
        assertEquals("file|Offset", CommonsCategoryContinuationPolicy.nextToken("file|Offset", 0, 2))
        assertEquals("file|Offset", CommonsCategoryContinuationPolicy.nextToken("file|Offset", 0, 0))
        assertEquals(null, CommonsCategoryContinuationPolicy.nextToken("file|Offset", 0, 3))
    }
    @Test fun noThirdPageOrInvalidToken() {
        assertEquals(null, CommonsCategoryContinuationPolicy.nextToken("file|Offset", 1, 0))
        assertEquals(null, CommonsCategoryContinuationPolicy.nextToken("", 0, 0))
        assertEquals(null, CommonsCategoryContinuationPolicy.nextToken("bad\u0000token", 0, 0))
        assertEquals(null, CommonsCategoryContinuationPolicy.nextToken("x".repeat(513), 0, 0))
    }
}
