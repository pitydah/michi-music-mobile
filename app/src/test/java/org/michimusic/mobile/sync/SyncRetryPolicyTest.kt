package org.michimusic.mobile.sync

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncRetryPolicyTest {
    @Test fun retryIsBoundedToThreeAttemptsForIoErrorsOnly() {
        val transient = java.io.IOException("temporary network failure")
        assertTrue(SyncRetryPolicy.shouldRetryUnhandled(transient, 0))
        assertTrue(SyncRetryPolicy.shouldRetryUnhandled(transient, 2))
        assertFalse(SyncRetryPolicy.shouldRetryUnhandled(transient, 3))
        assertFalse(SyncRetryPolicy.shouldRetryUnhandled(IllegalStateException("bug"), 0))
        assertFalse(SyncRetryPolicy.shouldRetryUnhandled(IllegalArgumentException("invalid"), 0))
    }
}
