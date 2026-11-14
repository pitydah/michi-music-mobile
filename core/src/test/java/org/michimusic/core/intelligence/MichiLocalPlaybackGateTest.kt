package org.michimusic.core.intelligence

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MichiLocalPlaybackGateTest {
    @Test fun localPhoneEndpointCanRequestPlayback() {
        assertTrue(MichiLocalPlaybackGate.permits("local_phone", true, false, true))
    }
    @Test fun remoteReceiverIsRejectedEvenWhenSessionIsNotRemoteHost() {
        assertFalse(MichiLocalPlaybackGate.permits("michi_stream", false, false, true))
        assertFalse(MichiLocalPlaybackGate.permits("michi_micro", false, false, true))
    }
    @Test fun preparationAndFailedHandoffsCannotStartLocalRecommendation() {
        assertFalse(MichiLocalPlaybackGate.permits("local_phone", true, false, false))
        assertTrue(MichiLocalPlaybackGate.permits("local_phone", true, false, true))
    }
    @Test fun remoteHostAndInconsistentStatesFailClosed() {
        assertFalse(MichiLocalPlaybackGate.permits("michi_micro", false, true, true))
        assertFalse(MichiLocalPlaybackGate.permits("local_phone", true, true, true))
        assertFalse(MichiLocalPlaybackGate.permits("local_phone", false, false, true))
        assertFalse(MichiLocalPlaybackGate.permits("", true, false, true))
    }
}
