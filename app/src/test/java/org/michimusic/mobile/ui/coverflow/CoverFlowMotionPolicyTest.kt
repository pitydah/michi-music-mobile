package org.michimusic.mobile.ui.coverflow

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.michimusic.mobile.ui.components.aeroCoverFlowShouldAnimate

/** Interaction contract: accessibility preference controls the pager itself, not just artwork. */
class CoverFlowMotionPolicyTest {
    @Test fun reducedMotionDisablesPagerAnimation() {
        assertFalse(aeroCoverFlowShouldAnimate(reducedMotion = true))
    }

    @Test fun defaultAllowsPagerAnimation() {
        assertTrue(aeroCoverFlowShouldAnimate(reducedMotion = false))
    }
}
