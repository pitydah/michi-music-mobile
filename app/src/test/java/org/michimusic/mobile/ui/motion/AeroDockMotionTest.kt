package org.michimusic.mobile.ui.motion

import org.junit.Assert.assertEquals
import org.junit.Test

class AeroDockMotionTest {
    @Test fun dockSelectionCannotOverflowAlpha() {
        assertEquals(0f, aeroDockPanelOpacity(aeroDockEmphasisTarget(false), false), 0f)
        assertEquals(0.1f, aeroDockPanelOpacity(aeroDockEmphasisTarget(true), false), 0f)
        assertEquals(0.22f, aeroDockPanelOpacity(1.2f, true), 0f)
        assertEquals(0.32f, aeroDockBorderOpacity(2f, false), 0f)
        assertEquals(0f, aeroDockBorderOpacity(-1f, true), 0f)
    }
}
