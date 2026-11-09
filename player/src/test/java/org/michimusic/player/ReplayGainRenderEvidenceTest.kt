package org.michimusic.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReplayGainRenderEvidenceTest {
    @Test fun startsUnknownUntilPcmActuallyRendered() {
        val e = ReplayGainRenderEvidence()
        assertNull(e.snapshot())
        e.confirm(e.captureRevision(), 0, 0, 0.5f)
        assertNull(e.snapshot())
    }
    @Test fun confirmsCurrentBufferAndAccumulatesCurrentRevisionOnly() {
        val e = ReplayGainRenderEvidence()
        val rev = e.captureRevision()
        e.confirm(rev, 480, 120, 0.6f)
        assertEquals(480L, e.snapshot()!!.processedFrames)
        assertEquals(120, e.snapshot()!!.rampFramesRemaining)
        e.confirm(rev, 240, 0, 0.4f)
        assertEquals(720L, e.snapshot()!!.processedFrames)
        assertEquals(0.4f, e.snapshot()!!.lastFrameGain, 0.00001f)
    }
    @Test fun configurationChangeImmediatelyRevokesOldReceipt() {
        val e = ReplayGainRenderEvidence()
        val old = e.captureRevision()
        e.confirm(old, 100, 0, 0.6f)
        e.invalidate()
        assertNull(e.snapshot())
        e.confirm(old, 50, 0, 0.6f)
        assertNull(e.snapshot())
        e.confirm(e.captureRevision(), 48, 0, 1f)
        assertEquals(48L, e.snapshot()!!.processedFrames)
    }
    @Test fun media3FlushRevokesEvenWhenRequestedGainDoesNotChange() {
        val e = ReplayGainRenderEvidence()
        e.confirm(e.captureRevision(), 10, 0, 0.5f)
        e.invalidate() // onFlush
        assertNull(e.snapshot())
        e.confirm(e.captureRevision(), 10, 0, 0.5f)
        assertEquals(10L, e.snapshot()!!.processedFrames)
    }
    @Test fun invalidFrameOrGainCannotBecomeObserved() {
        val e = ReplayGainRenderEvidence()
        val rev = e.captureRevision()
        e.confirm(rev, -1, 0, 1f)
        e.confirm(rev, 100, -1, 1f)
        e.confirm(rev, 100, 0, Float.NaN)
        e.confirm(rev, 100, 0, Float.POSITIVE_INFINITY)
        e.confirm(rev, 100, 0, -2f)
        assertNull(e.snapshot())
    }
    @Test fun stableBufferDoesNotAllocateAnotherReceipt() {
        val e = ReplayGainRenderEvidence()
        val revision = e.captureRevision()
        e.confirm(revision, 480, 0, 0.8f)
        val first = e.snapshot()
        e.confirm(revision, 480, 0, 0.8f)
        assertTrue(first === e.snapshot())
    }
    @Test fun concurrentControlsIncrementDistinctRevisions() {
        val e = ReplayGainRenderEvidence()
        val before = e.captureRevision()
        e.invalidate()
        e.invalidate()
        assertEquals(before + 2L, e.captureRevision())
    }
    @Test fun oneBufferReceiptDoesNotProvePhysicalOutput() {
        val e = ReplayGainRenderEvidence()
        e.confirm(e.captureRevision(), 1, 1, 0.75f)
        val proof = e.snapshot()!!
        assertTrue(proof.processedFrames > 0L)
        assertTrue(proof.rampFramesRemaining > 0)
        assertEquals(0.75f, proof.lastFrameGain, 0.00001f)
    }
}
