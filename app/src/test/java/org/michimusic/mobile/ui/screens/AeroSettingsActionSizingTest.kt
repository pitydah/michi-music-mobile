package org.michimusic.mobile.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AeroSettingsActionSizingTest {
    @Test fun compactActionsUseSameFloorForNormalTextSizes() {
        for (scale in listOf(0.85f, 1f, 1.15f, 1.29f)) {
            assertEquals(64, aeroSettingsAudioQuickActionMinHeightDp(scale))
        }
    }

    @Test fun largerTextRetainsMoreRoomAndAccessibility() {
        assertEquals(72, aeroSettingsAudioQuickActionMinHeightDp(1.3f))
        assertEquals(72, aeroSettingsAudioQuickActionMinHeightDp(1.5f))
        assertEquals(88, aeroSettingsAudioQuickActionMinHeightDp(1.7f))
        assertEquals(88, aeroSettingsAudioQuickActionMinHeightDp(2f))
        for (scale in listOf(0.85f, 1f, 1.3f, 1.7f, 2f)) {
            assertTrue(aeroSettingsAudioQuickActionMinHeightDp(scale) >= 48)
        }
    }

    @Test fun invalidInputFallsBackToSafeHeight() {
        for (scale in listOf(0f, -1f, Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            assertEquals(72, aeroSettingsAudioQuickActionMinHeightDp(scale))
        }
    }
}
