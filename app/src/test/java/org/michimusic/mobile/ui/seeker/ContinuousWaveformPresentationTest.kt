package org.michimusic.mobile.ui.seeker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContinuousWaveformPresentationTest {
    @Test fun envelopeIsUnipolarClampedAndFinite() {
        val result = ContinuousWaveformPresentation.envelope(
            listOf(-1.4f, -0.5f, 0f, 0.25f, Float.NaN, Float.POSITIVE_INFINITY, 2f))
        assertEquals(listOf(1f, 0.5f, 0f, 0.25f, 0f, 0f, 1f), result)
    }

    @Test fun singleSilhouetteAnchorsToTheActualThumbCentre() {
        val g = ContinuousWaveformPresentation.geometry(44f, 2f, 1.25f)
        assertEquals(22f, g.baselinePx, 0.0001f)
        assertEquals(20.75f, ContinuousWaveformPresentation.contourY(0f, g), 0.0001f)
        assertEquals(2f, ContinuousWaveformPresentation.contourY(1f, g), 0.0001f)
        assertTrue(ContinuousWaveformPresentation.contourY(.3f, g) < g.baselinePx)
        assertTrue(ContinuousWaveformPresentation.contourY(.3f, g) > 0f)
    }

    @Test fun invalidAndZeroSizeGeometryNeverEscapesBounds() {
        val invalid = ContinuousWaveformPresentation.geometry(Float.NaN, -10f, Float.NaN)
        assertEquals(0f, invalid.baselinePx, 0.0f)
        assertEquals(0f, invalid.peakRisePx, 0.0f)
        val compact = ContinuousWaveformPresentation.geometry(8f, 20f, 3f)
        assertEquals(4f, compact.baselinePx, 0.0f)
        assertEquals(4f, ContinuousWaveformPresentation.contourY(Float.NaN, compact), 0.0f)
    }
}
