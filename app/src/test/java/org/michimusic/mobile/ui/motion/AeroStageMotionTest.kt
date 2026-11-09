package org.michimusic.mobile.ui.motion

import org.junit.Assert.assertEquals
import org.junit.Test

class AeroStageMotionTest {
    @Test fun startHasOnlyOneActiveStage() {
        assertEquals(AeroTransferStageVisual.ACTIVE, aeroTransferStageVisual(0, 0, false))
        assertEquals(AeroTransferStageVisual.UPCOMING, aeroTransferStageVisual(1, 0, false))
        assertEquals(AeroTransferStageVisual.UPCOMING, aeroTransferStageVisual(3, 0, false))
    }

    @Test fun verificationDoesNotClaimCompletion() {
        assertEquals(AeroTransferStageVisual.COMPLETED, aeroTransferStageVisual(1, 2, false))
        assertEquals(AeroTransferStageVisual.ACTIVE, aeroTransferStageVisual(2, 2, false))
        assertEquals(AeroTransferStageVisual.UPCOMING, aeroTransferStageVisual(3, 2, false))
    }

    @Test fun transferFailureIsVisibleWithoutInventingSuccess() {
        assertEquals(AeroTransferStageVisual.FAILED, aeroTransferStageVisual(1, 1, true))
        assertEquals(AeroTransferStageVisual.UPCOMING, aeroTransferStageVisual(2, 1, true))
        assertEquals(AeroTransferStageVisual.COMPLETED, aeroTransferStageVisual(0, 1, true))
    }

    @Test fun completedAndOutOfRangeAreDeterministic() {
        assertEquals(AeroTransferStageVisual.COMPLETED, aeroTransferStageVisual(3, 3, false))
        assertEquals(AeroTransferStageVisual.UPCOMING, aeroTransferStageVisual(5, 3, false))
        assertEquals(AeroTransferStageVisual.UPCOMING, aeroTransferStageVisual(0, -1, true))
    }
}
