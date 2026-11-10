package org.michimusic.mobile.playback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteQueueJumpPolicyTest {
    @Test fun requiresObservedIndexAndTrackIdentity() {
        assertTrue(remoteQueueJumpWasObserved(2, "A", 2, "A"))
        assertFalse(remoteQueueJumpWasObserved(2, "A", 0, "A"))
        assertFalse(remoteQueueJumpWasObserved(2, "A", 2, "B"))
        assertFalse(remoteQueueJumpWasObserved(2, "A", 2, null))
        assertFalse(remoteQueueJumpWasObserved(-1, "A", -1, "A"))
    }
}
