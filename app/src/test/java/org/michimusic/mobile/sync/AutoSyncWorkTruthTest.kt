package org.michimusic.mobile.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoSyncWorkTruthTest {
    @Test fun emptyWorkIsNotScheduled() = assertEquals(AutoSyncWorkTruth.NOT_SCHEDULED,
        autoSyncWorkTruth(emptyList()))
    @Test fun cancelledIsNotScheduled() = assertEquals(AutoSyncWorkTruth.NOT_SCHEDULED,
        autoSyncWorkTruth(listOf("CANCELLED", "FAILED", "SUCCEEDED")))
    @Test fun queuedWinsOverBlocked() = assertEquals(AutoSyncWorkTruth.QUEUED,
        autoSyncWorkTruth(listOf("BLOCKED", "ENQUEUED")))
    @Test fun runningWinsOverQueued() = assertEquals(AutoSyncWorkTruth.RUNNING,
        autoSyncWorkTruth(listOf("ENQUEUED", "RUNNING")))
    @Test fun blockedCanBeReported() = assertEquals(AutoSyncWorkTruth.BLOCKED,
        autoSyncWorkTruth(listOf("BLOCKED")))
    @Test fun activePreferenceWithoutJobCannotClaimSuccess() = assertTrue(
        autoSyncWorkStatusLabel(true, AutoSyncWorkTruth.NOT_SCHEDULED).contains("sin tarea"))
    @Test fun disabledPreferenceOverridesStaleJob() = assertEquals(
        "Sincronización automática desactivada",
        autoSyncWorkStatusLabel(false, AutoSyncWorkTruth.QUEUED))
    @Test fun unknownIsNotClaimedScheduled() = assertTrue(
        autoSyncWorkStatusLabel(true, AutoSyncWorkTruth.UNKNOWN).contains("Consultando"))

    @Test fun selectedOwnerJobIsPreserved() = assertEquals(false,
        shouldPrunePeriodicSync(setOf(periodicSyncOwnerTag("server-a")), false, "server-a"))
    @Test fun revokedOwnerJobIsPruned() = assertTrue(
        shouldPrunePeriodicSync(setOf(periodicSyncOwnerTag("server-b")), false, "server-a"))
    @Test fun unlabelledLegacyJobIsPrunedDuringReconciliation() = assertTrue(
        shouldPrunePeriodicSync(setOf("michi_periodic_sync"), false, "server-a"))
    @Test fun finishedJobsAreNotCancelledAgain() = assertEquals(false,
        shouldPrunePeriodicSync(emptySet(), true, "server-a"))
    @Test fun peerRevokedWhileIdentityAwaitedNeverKeepsScheduledWork() {
        assertEquals(false, samePeriodicSyncPeer("server-a", "https://old", "server-a", "https://old", true))
    }

    @Test fun peerUrlChangedWhileIdentityAwaitedNeverKeepsOldEndpoint() {
        assertEquals(false, samePeriodicSyncPeer("server-a", "https://old", "server-a", "https://new", false))
    }

    @Test fun unchangedOwnerAndUrlRetainAuthorityToSchedule() {
        assertTrue(samePeriodicSyncPeer("server-a", "https://same", "server-a", "https://same", false))
    }

    @Test fun noAnonymousPeerCanOwnBackgroundWork() {
        assertEquals(false, samePeriodicSyncPeer("", "https://x", "", "https://x", false))
        assertEquals(false, samePeriodicSyncPeer("server-a", "", "server-a", "", false))
    }
}
