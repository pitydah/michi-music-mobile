package org.michimusic.mobile.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EcosystemPresentationPolicyTest {
    @Test fun pauseIsOnlyForActiveTransfer() {
        assertTrue(airCatUiPresentation("TRANSFERRING").showPause)
        assertFalse(airCatUiPresentation("NEGOTIATING").showPause)
        assertFalse(airCatUiPresentation("VERIFYING").showPause)
    }
    @Test fun resumeIsOnlyForPausedTransfer() {
        assertTrue(airCatUiPresentation("PAUSED").showResume)
        assertFalse(airCatUiPresentation("TRANSFERRING").showResume)
    }
    @Test fun terminalTransferHasNoControls() {
        for (state in listOf("FAILED", "CANCELLED", "COMPLETED")) {
            val ui = airCatUiPresentation(state)
            assertTrue(ui.isTerminal)
            assertFalse(ui.showPause)
            assertFalse(ui.showResume)
            assertFalse(ui.showCancel)
        }
    }
    @Test fun unknownTransferNeverExposesActions() {
        val ui = airCatUiPresentation("FUTURE_STATE")
        assertFalse(ui.showCancel)
        assertFalse(ui.showPause)
        assertFalse(ui.showResume)
    }
    @Test fun errorIsExplicit() { assertTrue(airCatUiPresentation("FAILED").isError) }
    @Test fun indeterminateTotalIsNotZeroPercent() {
        assertNull(syncProgressFraction(7,0))
        assertNull(syncProgressFraction(7,-1))
    }
    @Test fun determinateTotalsAreClamped() {
        assertEquals(1.0f,syncProgressFraction(99,5)!!,0.0001f)
        assertEquals(0.0f,syncProgressFraction(-4,5)!!,0.0001f)
    }
    @Test fun unknownTotalIsReportedAsUnknownNotZero() {
        val counters = syncProgressCounters(3, 0)
        assertFalse(counters.hasKnownTotal)
        assertEquals(3, counters.completed)
    }
    @Test fun knownTotalNeverGoesBeyondRange() {
        val counters = syncProgressCounters(11, 8)
        assertTrue(counters.hasKnownTotal)
        assertEquals(8, counters.completed)
        assertEquals(8, counters.total)
    }
}
