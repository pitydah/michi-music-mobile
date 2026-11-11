package org.michimusic.mobile.ui.seeker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.michimusic.mobile.ui.theme.AeroSeekerStyle

class WaveformSeekerVisualPolicyTest {
    @Test fun sameDecodeMayBeShownInBarsButNeverUnderContinuo() {
        assertTrue(waveformBarsPreviewVisible(AeroSeekerStyle.WAVEFORM_BARS, true, false))
        assertFalse(waveformBarsPreviewVisible(AeroSeekerStyle.WAVEFORM_CONTINUOUS, true, false))
        assertFalse(waveformBarsPreviewVisible(AeroSeekerStyle.WAVEFORM_BARS, false, false))
        assertFalse(waveformBarsPreviewVisible(AeroSeekerStyle.WAVEFORM_BARS, true, true))
        assertFalse(waveformBarsPreviewVisible(AeroSeekerStyle.ORIGINAL, true, false))
    }

    @Test fun measuredAndUnknownRemainDistinguishable() {
        assertEquals("Preparando forma de onda", waveformLoadingDescription(false))
        assertEquals("Analizando forma de onda", waveformLoadingDescription(true))
    }
}
