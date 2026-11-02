package org.michimusic.mobile.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The studio panels must render Spanish numbers. `Float.toString()` produced
 * "48.0 kHz" and a default-locale "%.1f" produced "-0.0 dB"; both read as
 * unfinished copy next to the rest of the audio UI.
 */
class AudioStudioFormatTest {

    @Test
    fun wholeKilohertzDropTheDecimalEntirely() {
        assertEquals("48 kHz", audioRateLabel(48_000))
        assertEquals("96 kHz", audioRateLabel(96_000))
    }

    @Test
    fun fractionalKilohertzKeepOneDecimalWithComma() {
        assertEquals("44,1 kHz", audioRateLabel(44_100))
        assertEquals("88,2 kHz", audioRateLabel(88_200))
    }

    @Test
    fun invalidRatesFallBackToHertzInsteadOfInventingKilohertz() {
        assertEquals("0 Hz", audioRateLabel(0))
        assertEquals("-5 Hz", audioRateLabel(-5))
    }

    @Test
    fun zeroDecibelsCarryNoSign() {
        assertEquals("0,0 dB", studioDbLabel(0f))
        assertEquals("6,0 dB", studioDbLabel(6f))
    }

    @Test
    fun signedDecibelsUseTheTypographyMinusAndPlus() {
        assertEquals("−3,5 dB", studioDbLabel(-3.5f, signed = true))
        assertEquals("+3,5 dB", studioDbLabel(3.5f, signed = true))
        assertEquals("0,0 dB", studioDbLabel(0f, signed = true))
    }

    @Test
    fun nonFiniteValuesNeverReachTheUi() {
        assertEquals("0,0 dB", studioDbLabel(Float.NaN))
        assertEquals("0,0 dB", studioDbLabel(Float.NEGATIVE_INFINITY))
    }

    @Test
    fun ratiosUseTwoDecimalsWithComma() {
        assertEquals("0,80", studioRatioLabel(0.8f))
        assertEquals("1,00", studioRatioLabel(1f))
        assertEquals("0,00", studioRatioLabel(Float.POSITIVE_INFINITY))
    }
}
