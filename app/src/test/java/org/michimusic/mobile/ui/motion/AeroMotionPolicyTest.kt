package org.michimusic.mobile.ui.motion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.michimusic.mobile.ui.theme.MichiMotion

class AeroMotionPolicyTest {
    @Test fun pressedEnabledScalesOnlyWhenMotionAllowed() {
        assertEquals(MichiMotion.feedbackPressedScale, aeroPressScale(true, true, false), 0f)
        assertEquals(MichiMotion.feedbackRestScale, aeroPressScale(true, false, false), 0f)
    }

    @Test fun disabledAndReducedMotionRemainStatic() {
        assertEquals(MichiMotion.feedbackRestScale, aeroPressScale(false, true, false), 0f)
        assertEquals(MichiMotion.feedbackRestScale, aeroPressScale(true, true, true), 0f)
        assertEquals(MichiMotion.feedbackRestScale, aeroPressScale(false, false, true), 0f)
    }

    @Test fun artistPressOnlyScalesWhenAllowed() {
        assertEquals(0.985f, aeroTilePressScale(true, false), 0f)
        assertEquals(1f, aeroTilePressScale(false, false), 0f)
        assertEquals(1f, aeroTilePressScale(true, true), 0f)
    }

    @Test fun reducedMotionDisablesTimeBasedFeedback() {
        assertEquals(150, aeroMotionDurationMillis(false, 150))
        assertEquals(0, aeroMotionDurationMillis(true, 150))
        assertThrows(IllegalArgumentException::class.java) {
            aeroMotionDurationMillis(false, -1)
        }
    }
}
