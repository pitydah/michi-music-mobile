package org.michimusic.mobile.ui.screens

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.michimusic.mobile.playback.remoteQueueRemovalUnambiguous

class QueueInteractionPolicyTest {
    private val snapshot = listOf("A", "B", "A")
    private val entryIds = listOf("instance-1", "instance-2", "instance-3")
    private fun selection(index: Int, remote: Boolean = false, entryId: String? = null) =
        QueueActionSelection(index, snapshot[index], snapshot, entryId, "device", remote)

    @Test fun localDuplicateIsResolvedByEntryId() {
        assertTrue(queueActionSelectionIsCurrent(selection(2, entryId = "instance-3"), snapshot, entryIds, "device", false))
        assertFalse(queueActionSelectionIsCurrent(selection(2, entryId = "instance-1"), snapshot, entryIds, "device", false))
    }

    @Test fun legacyLocalDuplicateFallsBackToCurrentIndexAndSnapshot() {
        assertTrue(queueActionSelectionIsCurrent(selection(2), snapshot, emptyList(), "device", false))
        assertTrue(queueActionSelectionIsCurrent(selection(1), snapshot, emptyList(), "device", false))
        assertFalse(queueActionSelectionIsCurrent(selection(2), listOf("A", "A", "B"), emptyList(), "device", false))
    }

    @Test fun reorderingInvalidatesMenuSelection() {
        assertFalse(queueActionSelectionIsCurrent(selection(2, entryId = "instance-3"), listOf("A", "A", "B"), entryIds, "device", false))
        assertFalse(queueActionSelectionIsCurrent(selection(2, entryId = "instance-3"), snapshot, entryIds.reversed(), "device", false))
    }

    @Test fun outputSwitchOrRemoteModeInvalidatesSelection() {
        assertFalse(queueActionSelectionIsCurrent(selection(1), snapshot, entryIds, "new-device", false))
        assertFalse(queueActionSelectionIsCurrent(selection(1), snapshot, entryIds, "device", true))
    }

    @Test fun remoteCannotRemoveAmbiguousOccurrence() {
        assertFalse(remoteQueueRemovalUnambiguous(snapshot, 0))
        assertFalse(remoteQueueRemovalUnambiguous(snapshot, 2))
        assertTrue(remoteQueueRemovalUnambiguous(snapshot, 1))
        assertFalse(remoteQueueRemovalUnambiguous(snapshot, 3))
    }

    @Test fun remoteSnapshotInvalidatedAfterQueueChanges() {
        assertTrue(queueActionSelectionIsCurrent(selection(2, remote = true), snapshot, emptyList(), "device", true))
        assertFalse(queueActionSelectionIsCurrent(selection(2, remote = true), listOf("A", "B"), emptyList(), "device", true))
    }

    @Test fun clearConfirmationIsBoundToOriginalQueueAndEndpoint() {
        val request = QueueClearRequest("device", false, snapshot, entryIds)
        assertTrue(queueClearRequestIsCurrent(request, snapshot, entryIds, "device", false))
        assertFalse(queueClearRequestIsCurrent(request, snapshot, entryIds, "other", false))
        assertFalse(queueClearRequestIsCurrent(request, snapshot, entryIds.reversed(), "device", false))
        assertFalse(queueClearRequestIsCurrent(request, listOf("B", "A", "A"), entryIds, "device", false))
        assertFalse(queueClearRequestIsCurrent(request, snapshot, entryIds, "device", true))
    }

    @Test fun pendingOrDisconnectedRemoteActionsAreBlocked() {
        assertFalse(queueActionsEnabled(true, true, true, true))
        assertFalse(queueActionsEnabled(true, true, false, false))
        assertTrue(queueActionsEnabled(true, false, true, false))
        assertFalse(queueActionsEnabled(false, false, false, false))
        assertTrue(queueActionsEnabled(false, true, false, true))
    }
}
