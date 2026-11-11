package org.michimusic.mobile.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.michimusic.mobile.ui.theme.AeroSeekerStyle

class SettingsSeekerPreviewPolicyTest {
    @Test fun allFiveCanonicalSeekerStylesRemainAvailable() = assertEquals(
        listOf("Aero Original", "Aero Wavy", "Waveform · Barras",
            "Waveform · Continuo", "Aero Capsule"),
        AeroSeekerStyle.entries.map { it.label })

    @Test fun onlyWaveformsUseDemoAmplitudes() {
        assertEquals(2, AeroSeekerStyle.entries.count(::seekerPreviewUsesExample))
        assertFalse(seekerPreviewUsesExample(AeroSeekerStyle.ORIGINAL))
        assertFalse(seekerPreviewUsesExample(AeroSeekerStyle.LIQUID_GLASS))
    }

    @Test fun previewDataIsBoundedFiniteAndNonRandom() {
        val samples = seekerPreviewSamples()
        assertEquals(32, samples.size)
        assertTrue(samples.all { it.isFinite() && it in 0f..1f })
        assertTrue(samples === seekerPreviewSamples())
    }
}
