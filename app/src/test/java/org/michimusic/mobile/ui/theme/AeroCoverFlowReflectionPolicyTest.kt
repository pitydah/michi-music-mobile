package org.michimusic.mobile.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AeroCoverFlowReflectionPolicyTest {
    @Test fun normalPreservesTheExistingFrameAndThreeDpLift() {
        val normal = aeroCoverFlowFrame(
            AeroCoverFlowReflection.NORMAL, raised = true,
            reducedMotion = false, highContrast = false,
        )
        assertEquals(36f / 255f, normal.borderAlpha, 0.0001f)
        assertEquals(0.65f, normal.borderWidthDp, 0f)
        assertEquals(3f, normal.shadowElevationDp, 0f)
    }

    @Test fun intensityProfilesAreOrderedAndBounded() {
        val discreet = aeroCoverFlowFrame(
            AeroCoverFlowReflection.DISCRETO, true, false, false,
        )
        val intense = aeroCoverFlowFrame(
            AeroCoverFlowReflection.INTENSO, true, false, false,
        )
        assertTrue(discreet.borderAlpha < 36f / 255f)
        assertTrue(intense.borderAlpha > 36f / 255f)
        assertTrue(discreet.borderWidthDp < 0.65f)
        assertTrue(intense.borderWidthDp > 0.65f)
        assertTrue(discreet.shadowElevationDp < 3f)
        assertTrue(intense.shadowElevationDp > 3f)
        assertTrue(intense.borderAlpha <= 1f)
    }

    @Test fun reducedMotionKeepsTheStaticFrameButNoLift() {
        for (mode in AeroCoverFlowReflection.entries) {
            val frame = aeroCoverFlowFrame(mode, true, reducedMotion = true, highContrast = false)
            assertTrue(frame.borderAlpha > 0f)
            assertEquals(0f, frame.shadowElevationDp, 0f)
        }
    }

    @Test fun highContrastUsesAnOpaqueFrameWithoutDecorativeLift() {
        val frame = aeroCoverFlowFrame(
            AeroCoverFlowReflection.INTENSO, true, false, highContrast = true,
        )
        assertEquals(0.80f, frame.borderAlpha, 0f)
        assertEquals(1f, frame.borderWidthDp, 0f)
        assertEquals(0f, frame.shadowElevationDp, 0f)
    }

    @Test fun sideCoversNeverGainAReflectionTheyDidNotHave() {
        for (mode in AeroCoverFlowReflection.entries) {
            val frame = aeroCoverFlowFrame(mode, raised = false, reducedMotion = false, highContrast = false)
            assertEquals(0f, frame.borderAlpha, 0f)
            assertEquals(0f, frame.shadowElevationDp, 0f)
        }
    }

    @Test fun unknownStoredValueFallsBackToNormal() {
        assertEquals(AeroCoverFlowReflection.NORMAL, savedAeroCoverFlowReflection(null))
        assertEquals(AeroCoverFlowReflection.NORMAL, savedAeroCoverFlowReflection("FUTURE_MODE"))
        assertEquals(AeroCoverFlowReflection.INTENSO, savedAeroCoverFlowReflection("INTENSO"))
    }
}
