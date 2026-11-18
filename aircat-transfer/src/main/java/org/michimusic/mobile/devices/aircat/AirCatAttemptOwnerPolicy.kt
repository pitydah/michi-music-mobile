package org.michimusic.mobile.devices.aircat

import kotlinx.coroutines.Job

/**
 * A49: a transfer's active coroutine Job is its attempt epoch. The resume
 * scheduler records the new owner before re-opening the visible lane.
 * A previous attempt may finish cancellation cleanup, but cannot publish
 * an event after ownership has passed to a different Job.
 *
 * A null owner is supported for explicit offline journal recovery and early
 * preflight, which do not use a registered streaming job. Terminal events
 * are separately sealed by AirCatProgressLedger.
 */
internal object AirCatAttemptOwnerPolicy {
    fun mayPublish(registeredOwner: Job?, emittingOwner: Job?): Boolean =
        if (registeredOwner == null) {
            // A60: cleanup can empty activeJobs before a delayed cancelled
            // callback unwinds. An untracked explicit recovery is legitimate
            // only while its own coroutine remains active.
            emittingOwner?.isActive == true
        } else emittingOwner != null && registeredOwner === emittingOwner
}
