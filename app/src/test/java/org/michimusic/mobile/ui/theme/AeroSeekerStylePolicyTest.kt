package org.michimusic.mobile.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AeroSeekerStylePolicyTest {
    @Test fun exactlyFiveStylesKeepAeroOriginalAsDefault() {
        assertEquals(5, AeroSeekerStyle.entries.size)
        assertEquals(AeroSeekerStyle.ORIGINAL, savedAeroSeekerStyle(null))
        assertEquals(AeroSeekerStyle.ORIGINAL, savedAeroSeekerStyle("obsolete"))
    }
    @Test fun eachPersistedStyleRoundTripsByStableName() {
        AeroSeekerStyle.entries.forEach { assertEquals(it, savedAeroSeekerStyle(it.name)) }
    }
    @Test fun waveformsNeverInventMissingOrCorruptAmplitudes() {
        for (requested in listOf(AeroSeekerStyle.WAVEFORM_BARS, AeroSeekerStyle.WAVEFORM_CONTINUOUS)) {
            assertEquals(AeroSeekerStyle.ORIGINAL, resolvedAeroSeekerStyle(requested, null))
            assertEquals(AeroSeekerStyle.ORIGINAL, resolvedAeroSeekerStyle(requested, List(32) { Float.NaN }))
            assertEquals(AeroSeekerStyle.ORIGINAL, resolvedAeroSeekerStyle(requested, List(3) { 0.5f }))
            assertEquals(requested, resolvedAeroSeekerStyle(requested, List(64) { 0.5f }))
        }
    }
    @Test fun waveformFallbackIsAnnouncedWithoutInventingWaveform() {
        assertEquals("Forma de onda no disponible; barra de progreso original",
            aeroSeekerFallbackDescription(AeroSeekerStyle.WAVEFORM_BARS, AeroSeekerStyle.ORIGINAL, false))
        assertEquals("Control de progreso original por contraste alto",
            aeroSeekerFallbackDescription(AeroSeekerStyle.WAVY, AeroSeekerStyle.ORIGINAL, true))
        assertEquals(null,
            aeroSeekerFallbackDescription(AeroSeekerStyle.WAVY, AeroSeekerStyle.WAVY, false))
    }
    @Test fun highContrastKeepsTheLegacyTrackAndDisablesDecorativeMotion() {
        assertEquals(AeroSeekerStyle.ORIGINAL,
            resolvedAeroSeekerStyle(AeroSeekerStyle.LIQUID_GLASS, null, highContrast = true))
        assertFalse(aeroWavyMotionAllowed(AeroSeekerStyle.WAVY, true, true, false))
        assertFalse(aeroWavyMotionAllowed(AeroSeekerStyle.WAVY, true, false, true))
        assertFalse(aeroWavyMotionAllowed(AeroSeekerStyle.WAVY, false, false, false))
        assertTrue(aeroWavyMotionAllowed(AeroSeekerStyle.WAVY, true, false, false))
    }
}
