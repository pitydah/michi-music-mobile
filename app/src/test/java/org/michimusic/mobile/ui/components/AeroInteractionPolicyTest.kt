package org.michimusic.mobile.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AeroInteractionPolicyTest {
    @Test fun dockScalesWithoutAssumingNormalFonts() {
        assertEquals(64, aeroDockHeightDp(1f))
        assertEquals(64, aeroDockHeightDp(1.2f))
        assertEquals(76, aeroDockHeightDp(1.21f))
        assertEquals(88, aeroDockHeightDp(1.6f))
        assertEquals(72, aeroDockHeightDp(Float.NaN))
    }
    @Test fun sliderBoundsAndRejectsInvalidValues() {
        assertEquals(0f, aeroSafeFraction(-10f), 0f)
        assertEquals(0.75f, aeroSafeFraction(0.75f), 0f)
        assertEquals(1f, aeroSafeFraction(4f), 0f)
        assertEquals(0f, aeroSafeFraction(Float.NaN), 0f)
        assertEquals(0f, aeroSafeFraction(Float.POSITIVE_INFINITY), 0f)
    }
    @Test fun backdropDisabledForAccessibilityAndAbsentArtwork() {
        assertFalse(aeroArtworkBackdropAllowed(false,false,false))
        assertTrue(aeroArtworkBackdropAllowed(true,false,false))
        assertFalse(aeroArtworkBackdropAllowed(true,true,false))
        assertFalse(aeroArtworkBackdropAllowed(true,false,true))
    }
    @Test fun coverflowTapNeverAnimatesWithReducedMotion() {
        assertFalse(aeroCoverFlowShouldAnimate(true))
        assertTrue(aeroCoverFlowShouldAnimate(false))
    }
}
