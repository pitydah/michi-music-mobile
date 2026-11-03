package org.michimusic.mobile.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.michimusic.player.PeqBand
import kotlin.math.abs

class PeqResponseModelTest {
    @Test fun neutralCurveIsZeroAtEveryFrequency() {
        val points = calculatedPeqResponse(listOf(PeqBand(1000f, 0f, 1f)))
        assertEquals(97, points.size)
        assertTrue(points.all { abs(it.gainDb) < .002f })
    }

    @Test fun peakAtCenterMatchesGainAndTwoFiltersSumInDecibels() {
        val one = calculatedPeqResponse(listOf(PeqBand(1000f, 6f, 1f)))
        val two = calculatedPeqResponse(listOf(PeqBand(1000f, 6f, 1f), PeqBand(1000f, 6f, 1f)))
        val index = one.indices.minBy { abs(one[it].frequencyHz - 1000f) }
        assertTrue(abs(one[index].gainDb - 6f) < 0.15f)
        assertTrue(abs(two[index].gainDb - 12f) < 0.3f)
    }

    @Test fun cutProducesNegativeGainWithoutNaN() {
        val curve = calculatedPeqResponse(listOf(PeqBand(80f, -9f, .9f)))
        val closest = curve.minBy { abs(it.frequencyHz - 80f) }
        assertTrue(closest.gainDb < -8.5f)
        assertTrue(curve.all { it.gainDb.isFinite() })
    }

    @Test fun sampleRateControlsUpperLimitWithoutClaimingItWasObserved() {
        val at16 = calculatedPeqResponse(emptyList(), 16_000)
        val at96 = calculatedPeqResponse(emptyList(), 96_000)
        assertTrue(at16.last().frequencyHz < 8_000)
        assertTrue(abs(at96.last().frequencyHz - 20_000f) < 1f)
        assertTrue(calculatedPeqResponse(emptyList(), 0).isEmpty())
    }

    @Test fun invalidPointCountsDoNotGenerateUnboundedAllocations() {
        assertTrue(calculatedPeqResponse(emptyList(), 48_000, pointCount = 10000).isEmpty())
    }
}
