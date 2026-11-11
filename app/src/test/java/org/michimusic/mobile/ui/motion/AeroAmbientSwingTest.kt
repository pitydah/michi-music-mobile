package org.michimusic.mobile.ui.motion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AeroAmbientSwingTest {
    @Test fun amplitudeIsBoundedAndHandlesNonFiniteState() {
        assertEquals(0f, aeroAmbientSwing(Float.NaN), 0.0001f)
        assertEquals(0f, aeroAmbientSwing(1f, Float.POSITIVE_INFINITY), 0.0001f)
        assertTrue(kotlin.math.abs(aeroAmbientSwing(1.57f)) > 0.06f)
        assertTrue(kotlin.math.abs(aeroAmbientSwing(1.57f, 3f)) <= 0.0801f)
    }
}
