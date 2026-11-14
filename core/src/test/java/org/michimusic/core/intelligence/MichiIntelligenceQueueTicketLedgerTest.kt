package org.michimusic.core.intelligence

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNotEquals
import org.junit.Test

class MichiIntelligenceQueueTicketLedgerTest {
    @Test fun aSingleTicketCanOnlyIssueOneQueueCommand() {
        val ledger = MichiIntelligenceQueueTicketLedger()
        val ticket = ledger.issue()
        assertTrue(ledger.consume(ticket))
        assertFalse(ledger.consume(ticket))
    }

    @Test fun lateEarlierClickCannotOverrideLaterChoice() {
        val ledger = MichiIntelligenceQueueTicketLedger()
        val first = ledger.issue()
        val second = ledger.issue()
        assertNotEquals(first, second)
        assertFalse(ledger.consume(first))
        assertTrue(ledger.consume(second))
    }

    @Test fun ordinaryQueueActionInvalidatesPendingRecommendation() {
        val ledger = MichiIntelligenceQueueTicketLedger()
        val pending = ledger.issue()
        ledger.cancel()
        assertFalse(ledger.consume(pending))
        val fresh = ledger.issue()
        assertTrue(ledger.consume(fresh))
    }

    @Test fun cancellationAndInvalidTicketCannotManufactureAuthorization() {
        val ledger = MichiIntelligenceQueueTicketLedger()
        assertFalse(ledger.consume(0L))
        assertFalse(ledger.consume(-1L))
        val first = ledger.issue()
        ledger.cancel()
        ledger.cancel()
        assertFalse(ledger.consume(first))
    }
}
