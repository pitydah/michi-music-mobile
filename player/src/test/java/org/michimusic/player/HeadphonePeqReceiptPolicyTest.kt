package org.michimusic.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test

class HeadphonePeqReceiptPolicyTest {
    private fun verify(
        requested: Long = 2, rendered: Long = 2, frames: Long = 512,
        clipped: Long = 0, headroom: Float = 3f, observedAt: Long = 170000L,
        enabled: Boolean = true, applied: Boolean = true, allowed: Boolean = true,
    ) = HeadphonePeqReceiptPolicy.current(requested, rendered, frames, clipped,
        headroom, observedAt, enabled, applied, allowed)

    @Test fun sameRevisionAfterPcmIsObserved() {
        val receipt = verify()!!
        assertEquals(2L, receipt.revision)
        assertEquals(512L, receipt.processedFrames)
        assertEquals(3f, receipt.headroomDb, 0.001f)
        assertEquals(170000L, receipt.observedAtMs)
    }
    @Test fun previousPresetReceiptNeverProvesNewRevision() {
        assertNull(verify(requested = 3, rendered = 2))
    }
    @Test fun changingRouteImmediatelyRevokesEvidence() {
        assertNull(verify(allowed = false))
    }
    @Test fun userDisabledProfileCannotReuseOldFrames() {
        assertNull(verify(enabled = false))
    }
    @Test fun processorNotYetAppliedCannotBePromoted() {
        assertNull(verify(applied = false))
    }
    @Test fun zeroFramesAreNotObserved() {
        assertNull(verify(frames = 0))
    }
    @Test fun negativeAndNonFiniteNumbersFailClosed() {
        assertNull(verify(clipped = -1))
        assertNull(verify(headroom = Float.NaN))
        assertNull(verify(headroom = Float.POSITIVE_INFINITY))
    }
    @Test fun missingTimestampCannotPretendObservation() {
        assertNull(verify(observedAt = 0))
    }
    @Test fun flushedRevisionIsUnknown() {
        assertNull(verify(rendered = -1))
    }
    @Test fun positiveClipCountIsLocalPcmEvidenceOnly() {
        val receipt = verify(clipped = 3)
        assertNotNull(receipt)
        assertEquals(3L, receipt?.clippedSamples)
    }
}
