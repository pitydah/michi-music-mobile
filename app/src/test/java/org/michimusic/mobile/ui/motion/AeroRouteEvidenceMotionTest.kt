package org.michimusic.mobile.ui.motion

import org.junit.Assert.assertEquals
import org.junit.Test
import org.michimusic.player.AudioRouteStatus

class AeroRouteEvidenceMotionTest {
    @Test fun noDeviceIsNeverReportedAsObserved() {
        val level = aeroRouteCertainty(AudioRouteStatus.NoDevice)
        assertEquals(AeroRouteCertainty.UNKNOWN, level)
        assertEquals("Ruta actual desconocida", aeroRouteCertaintyLabel(level))
    }

    @Test fun effectsSnapForReducedMotionAndContrast() {
        assertEquals(0, aeroRouteMotionDurationMs(true, false))
        assertEquals(0, aeroRouteMotionDurationMs(false, true))
        assertEquals(250, aeroRouteMotionDurationMs(false, false))
    }
}
