package org.michimusic.core.intelligence

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

class MichiPlaybackIntentFenceTest {
    @Test fun intentBlocksIntelligenceImmediatelyBeforeSessionStateEmits() {
        val prior = MichiPlaybackIntentFence.snapshot()
        val ticket = MichiPlaybackIntentFence.beginTransfer()
        try {
            assertFalse(MichiPlaybackIntentFence.permits())
            assertFalse(MichiPlaybackIntentFence.permits(prior.generation))
            assertFalse(MichiLocalPlaybackGate.permits("local_phone", true, false, true))
        } finally { MichiPlaybackIntentFence.completeTransfer(ticket, true) }
        assertTrue(MichiLocalPlaybackGate.permits("local_phone", true, false, true))
    }

    @Test fun staleCompletionCannotRestoreLocalAuthorityAfterNewerIntent() {
        val first = MichiPlaybackIntentFence.beginTransfer()
        val second = MichiPlaybackIntentFence.beginTransfer()
        try {
            assertFalse(MichiPlaybackIntentFence.completeTransfer(first, true))
            assertFalse(MichiPlaybackIntentFence.permits())
            assertTrue(MichiPlaybackIntentFence.completeTransfer(second, false))
            assertFalse(MichiPlaybackIntentFence.permits())
        } finally { MichiPlaybackIntentFence.completeTransfer(second, true) }
    }

    @Test fun oldSongEvidenceNeverSurvivesFailedHandoffThatReturnsLocal() {
        val initial = MichiPlaybackIntentFence.snapshot()
        val t = MichiPlaybackEvidenceAccumulator()
        t.begin(MichiPlaybackEvidenceAccumulator.LocalTrack("", "x", "Artist", "Rock", 10_000),
            0, 7, true, handoffGeneration = initial.generation)
        val ticket = MichiPlaybackIntentFence.beginTransfer()
        assertFalse(MichiPlaybackIntentFence.permits(initial.generation))
        MichiPlaybackIntentFence.completeTransfer(ticket, true)
        assertEquals(null, t.finish(MichiPlaybackEvidenceAccumulator.Transition.AUTO,
            9_000, 7, MichiPlaybackIntentFence.snapshot().generation))
    }

    @Test fun duplicateCallbackCannotChangeAlreadyCompletedTransferDecision() {
        val ticket = MichiPlaybackIntentFence.beginTransfer()
        try {
            assertTrue(MichiPlaybackIntentFence.completeTransfer(ticket, false))
            assertFalse(MichiPlaybackIntentFence.completeTransfer(ticket, true))
            assertFalse(MichiPlaybackIntentFence.permits())
        } finally { MichiPlaybackIntentFence.confirmedLocalEndpoint() }
    }

    @Test fun explicitReturnToPhoneRecoversAfterRemoteSuccess() {
        val remote = MichiPlaybackIntentFence.beginTransfer()
        assertTrue(MichiPlaybackIntentFence.completeTransfer(remote, false))
        assertFalse(MichiPlaybackIntentFence.permits())
        val localRevision = MichiPlaybackIntentFence.confirmedLocalEndpoint()
        assertTrue(MichiPlaybackIntentFence.permits(localRevision))
        assertFalse(MichiPlaybackIntentFence.completeTransfer(remote, true))
        assertTrue(MichiPlaybackIntentFence.permits())
    }

    @Test fun captureFromNewLocalSessionCanProceedAfterConfirmedCompletion() {
        val ticket = MichiPlaybackIntentFence.beginTransfer()
        assertTrue(MichiPlaybackIntentFence.completeTransfer(ticket, true))
        val stamp = MichiPlaybackIntentFence.snapshot()
        assertTrue(MichiPlaybackIntentFence.permits(stamp.generation))
    }
}
