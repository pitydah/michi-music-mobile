package org.michimusic.mobile.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Test
import org.michimusic.mobile.ui.theme.UI8VisualSpec

/** Timing contract, not an executed Android rendering claim. */
class UI8SearchDetailMotionPolicyTest {
    @Test fun normalMotionUsesFiniteAeroTransition() {
        assertEquals(UI8VisualSpec.searchDetailOpenMillis, searchDialogMotion(false).enterMs)
        assertEquals(UI8VisualSpec.searchDetailCloseMillis, searchDialogMotion(false).exitMs)
    }

    @Test fun reducedMotionHasNoEnterOrExitAnimation() {
        assertEquals(UI8SearchDialogMotion(0, 0), searchDialogMotion(true))
    }
}
