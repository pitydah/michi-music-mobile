package org.michimusic.mobile.devices.aircat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AirCatProgressLedgerTest {
    private fun event(id: String, state: AirCatTransferState, bytes: Long = 0L) =
        AirCatProgressEvent(id, if (state == AirCatTransferState.COMPLETED) 3 else 1,
            3, bytes, 100L, state,
            checksumVerified = state == AirCatTransferState.COMPLETED,
            libraryCommitted = state == AirCatTransferState.COMPLETED)

    @Test fun twoLanesPreserveIndependentProgress() {
        val book = AirCatProgressLedger()
        book.record(event("a", AirCatTransferState.TRANSFERRING, 20))
        book.record(event("b", AirCatTransferState.TRANSFERRING, 60))
        book.record(event("a", AirCatTransferState.PAUSED, 20))
        assertEquals(20L, book.latest("a")?.bytesTransferred)
        assertEquals(AirCatTransferState.TRANSFERRING, book.latest("b")?.state)
        assertEquals(2, book.snapshots().size)
    }

    @Test fun staleActiveCallbackDoesNotResurrectCancellation() {
        val book = AirCatProgressLedger()
        book.record(event("x", AirCatTransferState.TRANSFERRING, 20))
        book.record(event("x", AirCatTransferState.CANCELLED, 20))
        assertNull(book.record(event("x", AirCatTransferState.TRANSFERRING, 80)))
        assertEquals(AirCatTransferState.CANCELLED, book.latest("x")?.state)
    }

    @Test fun completedReceiptRejectsStaleFailureOrPause() {
        val book = AirCatProgressLedger()
        book.record(event("x", AirCatTransferState.COMPLETED, 100))
        assertNull(book.record(event("x", AirCatTransferState.FAILED, 1)))
        assertNull(book.record(event("x", AirCatTransferState.PAUSED, 1)))
        assertEquals(100L, book.latest("x")?.bytesTransferred)
    }

    @Test fun failedOperationCanRetryExplicitly() {
        val book = AirCatProgressLedger()
        book.record(event("x", AirCatTransferState.FAILED, 12))
        assertTrue(book.authorizeRestart("x"))
        book.record(event("x", AirCatTransferState.TRANSFERRING, 8))
        assertEquals(AirCatTransferState.TRANSFERRING, book.latest("x")?.state)
        assertEquals(8L, book.latest("x")?.bytesTransferred)
    }

    @Test fun lateCallbackCannotResumePausedTransferOrClaimSuccess() {
        val book = AirCatProgressLedger()
        book.record(event("x", AirCatTransferState.TRANSFERRING, 12))
        book.record(event("x", AirCatTransferState.PAUSED, 12))
        assertNull(book.record(event("x", AirCatTransferState.TRANSFERRING, 50)))
        assertNull(book.record(event("x", AirCatTransferState.COMPLETED, 100)))
        assertNull(book.record(event("x", AirCatTransferState.FAILED, 12)))
        assertEquals(AirCatTransferState.PAUSED, book.latest("x")?.state)
        assertTrue(book.authorizeRestart("x"))
        book.record(event("x", AirCatTransferState.TRANSFERRING, 12))
        assertEquals(AirCatTransferState.TRANSFERRING, book.latest("x")?.state)
    }

    @Test fun failedTransferNeedsExplicitRestartAndCannotFalselyComplete() {
        val book = AirCatProgressLedger()
        book.record(event("x", AirCatTransferState.FAILED, 12))
        assertNull(book.record(event("x", AirCatTransferState.TRANSFERRING, 40)))
        assertNull(book.record(event("x", AirCatTransferState.COMPLETED, 100)))
        assertEquals(AirCatTransferState.FAILED, book.latest("x")?.state)
    }

    @Test fun cancelledTransferCannotBeReauthorized() {
        val book = AirCatProgressLedger()
        book.record(event("x", AirCatTransferState.CANCELLED, 10))
        assertTrue(!book.authorizeRestart("x"))
        assertNull(book.record(event("x", AirCatTransferState.TRANSFERRING, 20)))
    }

    @Test fun boundedHistoryEvictsTerminalButPreservesOtherActiveLane() {
        val book = AirCatProgressLedger(maxEntries = 2)
        book.record(event("active", AirCatTransferState.TRANSFERRING))
        book.record(event("old", AirCatTransferState.CANCELLED))
        book.record(event("fresh", AirCatTransferState.TRANSFERRING))
        assertTrue(book.snapshots().containsKey("active"))
        assertTrue(book.snapshots().containsKey("fresh"))
        assertNull(book.latest("old"))
    }

    @Test fun evictedFailureCannotReappearFromDelayedWorker() {
        val book = AirCatProgressLedger(maxEntries = 2)
        book.record(event("old", AirCatTransferState.FAILED, 10))
        book.record(event("busy", AirCatTransferState.TRANSFERRING, 20))
        book.record(event("new", AirCatTransferState.TRANSFERRING, 20))
        assertNull(book.record(event("old", AirCatTransferState.TRANSFERRING, 40)))
        assertNull(book.record(event("old", AirCatTransferState.COMPLETED, 100)))
    }

    @Test fun capacityNeverEvictsActiveLanes() {
        val book = AirCatProgressLedger(maxEntries = 1)
        book.record(event("busy", AirCatTransferState.TRANSFERRING))
        assertNull(book.record(event("intruder", AirCatTransferState.TRANSFERRING)))
        assertEquals(setOf("busy"), book.snapshots().keys)
    }

    @Test fun invalidCountersCannotReplaceValidReceipt() {
        val book = AirCatProgressLedger()
        book.record(event("id", AirCatTransferState.TRANSFERRING, 15))
        assertNull(book.record(event("id", AirCatTransferState.TRANSFERRING, 101)))
        assertNull(book.record(event(" ", AirCatTransferState.TRANSFERRING, -1)))
        assertEquals(15L, book.latest("id")?.bytesTransferred)
    }

    @Test fun clearDoesNotKeepStaleSnapshots() {
        val book = AirCatProgressLedger()
        book.record(event("id", AirCatTransferState.PAUSED))
        book.clear()
        assertTrue(book.snapshots().isEmpty())
    }
    @Test fun dismissOneTerminalLeavesOtherActiveUntouched() {
        val book = AirCatProgressLedger()
        book.record(event("done", AirCatTransferState.COMPLETED, 100))
        book.record(event("active", AirCatTransferState.TRANSFERRING, 30))
        assertEquals(setOf("active"), book.dismissTerminal("done")?.keys)
        assertEquals(30L, book.latest("active")?.bytesTransferred)
    }

    @Test fun activeOrPausedCannotBeDismissedFromUI() {
        val book = AirCatProgressLedger()
        book.record(event("active", AirCatTransferState.TRANSFERRING, 30))
        book.record(event("paused", AirCatTransferState.PAUSED, 10))
        assertNull(book.dismissTerminal("active"))
        assertNull(book.dismissTerminal("paused"))
        assertEquals(2, book.snapshots().size)
    }

    @Test fun terminalDismissalCannotReopenWithLateCallback() {
        val book = AirCatProgressLedger()
        book.record(event("done", AirCatTransferState.COMPLETED, 100))
        book.dismissTerminal("done")
        assertNull(book.record(event("done", AirCatTransferState.TRANSFERRING, 30)))
        assertTrue(book.snapshots().isEmpty())
    }

    @Test fun terminalEvictionDoesNotResurrectWithDelayedWorker() {
        val book = AirCatProgressLedger(maxEntries=2)
        book.record(event("old", AirCatTransferState.CANCELLED, 5))
        book.record(event("live", AirCatTransferState.TRANSFERRING, 10))
        book.record(event("new", AirCatTransferState.TRANSFERRING, 20))
        assertNull(book.record(event("old", AirCatTransferState.TRANSFERRING, 70)))
        assertEquals(setOf("live","new"), book.snapshots().keys)
    }

    @Test fun clearingUiDoesNotEraseTerminalSeal() {
        val book = AirCatProgressLedger()
        book.record(event("done", AirCatTransferState.CANCELLED, 4))
        book.clear()
        assertNull(book.record(event("done", AirCatTransferState.TRANSFERRING, 55)))
        assertTrue(book.snapshots().isEmpty())
    }

    @Test fun incompleteBatchCannotPublishCompleted() {
        val book = AirCatProgressLedger()
        val complete = event("batch", AirCatTransferState.COMPLETED, 100)
        assertNull(book.record(complete.copy(checksumVerified = false)))
        assertNull(book.record(complete.copy(libraryCommitted = false)))
        assertNull(book.record(complete.copy(currentTrackIndex = 2)))
        assertNull(book.record(complete.copy(bytesTransferred = 99L)))
        assertTrue(book.snapshots().isEmpty())
        assertTrue(book.record(complete) != null)
    }

    @Test fun emptySelectionNoOpCannotMasqueradeAsVerifiedFile() {
        val book = AirCatProgressLedger()
        val zero = AirCatProgressEvent("empty", 0, 0, 0L, 0L, AirCatTransferState.COMPLETED)
        assertTrue(book.record(zero) != null)
        assertNull(book.record(zero.copy(checksumVerified = true)))
    }

}
