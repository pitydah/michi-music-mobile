package org.michimusic.mobile.devices.aircat

import kotlinx.coroutines.Job
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AirCatAttemptOwnerPolicyTest {
    @Test fun currentAttemptIsAllowed() {
        val job = Job()
        assertTrue(AirCatAttemptOwnerPolicy.mayPublish(job, job))
        job.cancel()
    }

    @Test fun oldAttemptAfterResumeIsRejected() {
        val old = Job()
        val resumed = Job()
        assertFalse(AirCatAttemptOwnerPolicy.mayPublish(resumed, old))
        old.cancel(); resumed.cancel()
    }

    @Test fun untrackedUiAndExplicitRecoveryCanEmit() {
        assertTrue(AirCatAttemptOwnerPolicy.mayPublish(null, Job()))
    }

    @Test fun unownedCallbackCannotReplaceRegisteredAttempt() {
        val running = Job()
        assertFalse(AirCatAttemptOwnerPolicy.mayPublish(running, null))
        running.cancel()
    }
}
