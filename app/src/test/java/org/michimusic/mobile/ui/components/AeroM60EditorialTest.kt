package org.michimusic.mobile.ui.components

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AeroM60EditorialTest {
    @Test fun compactWidthStacksEditorialActions() {
        assertTrue(aeroM60StackActions(280f, 1f))
        assertTrue(aeroM60StackActions(329.9f, 1f))
        assertFalse(aeroM60StackActions(330f, 1f))
    }

    @Test fun signalFooterStacksOnNarrowViewportsOrLargeFonts() {
        assertTrue(aeroM60StackSignal(320f, 1f))
        assertFalse(aeroM60StackSignal(360f, 1f))
        assertTrue(aeroM60StackSignal(480f, 2f))
    }

    @Test fun qualityBadgeNeverInfersLosslessOrBitperfect() {
        org.junit.Assert.assertEquals("Michi Music", aeroNowPlayingQualityBadge(false))
        org.junit.Assert.assertEquals("PCM decodificado", aeroNowPlayingQualityBadge(true))
    }

    @Test fun largeFontStacksEvenOnWideScreens() {
        assertTrue(aeroM60StackActions(600f, 1.3f))
        assertFalse(aeroM60StackActions(600f, 1.2f))
    }
}
