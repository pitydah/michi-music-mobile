package org.michimusic.mobile.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Guards numeric UI8 design decisions while screenshot tests judge their appearance. */
class UI8VisualSpecTest {
    @Test fun cornersAndTouchTargetUseAeroGeometry() {
        assertEquals(18f, UI8VisualSpec.popupCorner.value, 0f)
        assertEquals(20f, UI8VisualSpec.playlistHeroCorner.value, 0f)
        assertEquals(12f, UI8VisualSpec.captionCorner.value, 0f)
        assertEquals(10f, UI8VisualSpec.disclosureCorner.value, 0f)
        assertTrue(UI8VisualSpec.minTouchTarget.value >= 48f)
    }

    @Test fun captionStaysLocalAndMotionIsBounded() {
        assertEquals(0.72f, UI8VisualSpec.captionMatteAlpha, 0f)
        assertTrue(UI8VisualSpec.playlistDeckHeightFor(600) <= UI8VisualSpec.playlistDeckHeight)
        assertTrue(UI8VisualSpec.playlistDeckHeightFor(450) >= UI8VisualSpec.playlistDeckMinHeight)
        assertTrue(UI8VisualSpec.disclosureExpandMillis in 100..240)
        assertTrue(UI8VisualSpec.deckExitMillis in 120..240)
        assertTrue(UI8VisualSpec.searchDetailOpenMillis <= 240)
    }
}
