package org.michimusic.mobile.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

class MichiVinylCratePolicyTest {
    @Test fun verticalSwipeMovesBySignedDistance() {
        assertEquals(1, vinylCrateSwipeStep(-81f, -200f, 400f))
        assertEquals(-1, vinylCrateSwipeStep(81f, 200f, 400f))
        assertEquals(0, vinylCrateSwipeStep(-79f, -200f, 400f))
    }

    @Test fun fastSwipeRequiresMatchingDirectionAndNonzeroTravel() {
        assertEquals(1, vinylCrateSwipeStep(-30f, -1900f, 400f))
        assertEquals(-1, vinylCrateSwipeStep(30f, 1900f, 400f))
        assertEquals(0, vinylCrateSwipeStep(-30f, 1900f, 400f))
        assertEquals(0, vinylCrateSwipeStep(0f, 1900f, 400f))
    }

    @Test fun invalidGeometryAlwaysCancels() {
        assertEquals(0, vinylCrateSwipeStep(-100f, -1800f, 0f))
        assertEquals(0, vinylCrateSwipeStep(Float.NaN, 100f, 200f))
        assertEquals(0, vinylCrateSwipeStep(-100f, Float.POSITIVE_INFINITY, 200f))
    }

    @Test fun cyclingDoesNotReorderInputOrLoseAlbumIdentity() {
        val ids = listOf("alpha", "beta", "gamma")
        assertEquals("beta", michiCardStepKey(ids, "alpha", { it }, 1))
        assertEquals("gamma", michiCardStepKey(ids, "alpha", { it }, -1))
        assertEquals("alpha", michiCardStepKey(ids, "gamma", { it }, 1))
        assertEquals(listOf("alpha", "beta", "gamma"), ids)
    }
}
