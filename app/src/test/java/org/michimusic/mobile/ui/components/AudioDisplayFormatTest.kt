package org.michimusic.mobile.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AudioDisplayFormatTest {
    @Test fun ratesKeepFractionalPrecision() {
        assertEquals("16 bit · 44,1 kHz", decodedPcmLabel(44_100, 16))
        assertEquals("24 bit · 48 kHz", decodedPcmLabel(48_000, 24))
        assertEquals("24 bit · 96 kHz", decodedPcmLabel(96_000, 24))
        assertEquals("16 bit · 11,025 kHz", decodedPcmLabel(11_025, 16))
    }

    @Test fun unknownPcmParametersNeverBecomeBadges() {
        assertNull(decodedPcmLabel(0, 16))
        assertNull(decodedPcmLabel(44_100, 0))
        assertNull(decodedPcmLabel(Int.MAX_VALUE, 24))
        assertNull(decodedPcmLabel(48_000, -1))
    }

    @Test fun unknownTotalDoesNotPretendTransferIsZeroPercent() {
        assertNull(transferProgressPercent(250L, 0L))
        assertNull(transferProgressPercent(250L, -1L))
    }

    @Test fun transferProgressNeverOverflowsOrLeavesBounds() {
        assertEquals(0, transferProgressPercent(-10L, 100L))
        assertEquals(50, transferProgressPercent(50L, 100L))
        assertEquals(100, transferProgressPercent(999L, 100L))
        assertEquals(100, transferProgressPercent(Long.MAX_VALUE, Long.MAX_VALUE))
    }

    @Test fun linkSummaryClampsStaleCounts() {
        assertEquals("4 equipos detectados · 2 vinculados · 1 conectados", linkOverviewSummary(4, 2, 1))
        assertEquals("0 equipos detectados · 3 vinculados · 2 conectados", linkOverviewSummary(-1, 3, 2))
    }

    @Test fun equalizerUsesMillibelsWhere100mBIs1dB() {
        assertEquals("+4,0 dB", eqGainMilliDbLabel(400))
        assertEquals("+3,5 dB", eqGainMilliDbLabel(350))
        assertEquals("-2,0 dB", eqGainMilliDbLabel(-200))
        assertEquals("0,0 dB", eqGainMilliDbLabel(0))
        assertEquals("+15,0 dB", eqGainMilliDbLabel(1500))
        assertEquals("-15,0 dB", eqGainMilliDbLabel(-1500))
        assertEquals("-12,0 dB", eqGainMilliDbLabel(-1200))
        assertEquals("+12,0 dB", eqGainMilliDbLabel(1200))
    }

    @Test fun bandCentersPreserveFractionalFrequencies() {
        assertEquals("500 Hz", eqCenterFrequencyLabel(500))
        assertEquals("1,5 kHz", eqCenterFrequencyLabel(1500))
        assertEquals("10 kHz", eqCenterFrequencyLabel(10000))
        assertEquals("—", eqCenterFrequencyLabel(-1))
    }
}
