package org.michimusic.core.intelligence

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MichiSuggestionFeedbackPolicyTest {
    @Test fun noInterestExpiresAfterThirtyDaysWithoutPolling() {
        val p = MichiSuggestionFeedbackPolicy
        assertTrue(p.suppressed(100, 100))
        assertTrue(p.suppressed(100, 129))
        assertFalse(p.suppressed(100, 130))
        assertFalse(p.suppressed(100, 500))
    }
    @Test fun futureInvalidAndMissingDatesNeverCreatePermanentBans() {
        val p = MichiSuggestionFeedbackPolicy
        assertFalse(p.suppressed(null, 100))
        assertFalse(p.suppressed(101, 100))
        assertFalse(p.suppressed(-1, 100))
        assertFalse(p.suppressed(100, -1))
    }
    @Test fun feedbackIsOnlyACompactDayNotAHistoryLog() {
        val p = MichiSuggestionFeedbackPolicy
        assertEquals("000000020000", p.normalizedDay(20000))
        assertNull(p.normalizedDay(-1))
        assertNull(p.normalizedDay(p.MAX_EPOCH_DAY + 1))
    }
}
