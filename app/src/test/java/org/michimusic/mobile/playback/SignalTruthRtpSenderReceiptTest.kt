package org.michimusic.mobile.playback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SignalTruthRtpSenderReceiptTest {
    @Test fun noSessionOrNoPacketsDoesNotClaimObservation() {
        assertFalse(SignalTruthRtpSenderReceipt.isValid(SignalDestination.MICHI_STREAM, null, 12L, true))
        assertFalse(SignalTruthRtpSenderReceipt.isValid(SignalDestination.MICHI_STREAM, "", 12L, true))
        assertFalse(SignalTruthRtpSenderReceipt.isValid(SignalDestination.MICHI_STREAM, "a", 0L, true))
        assertFalse(SignalTruthRtpSenderReceipt.isValid(SignalDestination.MICHI_STREAM, "a", -1L, true))
    }

    @Test fun senderDoesNotBorrowPacketsForHostOrPhone() {
        assertFalse(SignalTruthRtpSenderReceipt.isValid(SignalDestination.REMOTE_HOST, "session", 12L, true))
        assertFalse(SignalTruthRtpSenderReceipt.isValid(SignalDestination.LOCAL, "session", 12L, true))
    }

    @Test fun inactiveSenderCannotBorrowOldPackets() {
        assertFalse(SignalTruthRtpSenderReceipt.isValid(SignalDestination.MICHI_STREAM, "old", 150L, false))
    }

    @Test fun onlyLiveStreamSessionCanShowSenderPacketCount() {
        assertTrue(SignalTruthRtpSenderReceipt.isValid(SignalDestination.MICHI_STREAM, "session-a", 12L, true))
    }
}
