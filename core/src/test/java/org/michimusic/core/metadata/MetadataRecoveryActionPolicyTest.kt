package org.michimusic.core.metadata

import org.junit.Assert.assertEquals
import org.junit.Test

class MetadataRecoveryActionPolicyTest {
    private fun check(
        phase: MetadataRecoveryPhase? = MetadataRecoveryPhase.STAGE_READY,
        readable: Boolean = true, pending: Boolean = true,
        exact: Boolean = true, intact: Boolean = true, current: Boolean = true,
    ) = MetadataRecoveryActionPolicy.inspect(readable,pending,exact,intact,phase,current)

    @Test fun noPendingIsNotError() {
        assertEquals(MetadataRecoveryActionPolicy.Action.NONE,check(pending=false).action)
    }
    @Test fun snapshottingCanBeDiscardedOnlyWhenTargetMatches() {
        assertEquals(MetadataRecoveryActionPolicy.Action.PRIVATE_PREPARATION_CAN_BE_DISCARDED,
            check(phase=MetadataRecoveryPhase.SNAPSHOTTING).action)
    }
    @Test fun durableBackupStillPreMutation() {
        assertEquals(MetadataRecoveryActionPolicy.Action.PRIVATE_PREPARATION_CAN_BE_DISCARDED,
            check(phase=MetadataRecoveryPhase.BACKUP_DURABLE).action)
    }
    @Test fun preparedStageCanBeDiscardedByUser() {
        assertEquals(MetadataRecoveryActionPolicy.Reason.PREPARED_ONLY,check().reason)
    }
    @Test fun wrongOwnerTrackOrUriBlocked() {
        assertEquals(MetadataRecoveryActionPolicy.Reason.WRONG_TARGET,check(exact=false).reason)
    }
    @Test fun changedOwnerNeverAllowsCleanup() {
        assertEquals(MetadataRecoveryActionPolicy.Reason.TARGET_NOT_CURRENT,check(current=false).reason)
    }
    @Test fun badBackupNeverAllowsCleanup() {
        assertEquals(MetadataRecoveryActionPolicy.Reason.PRIVATE_ARTIFACTS_INVALID,check(intact=false).reason)
    }
    @Test fun corruptJournalNeverAllowsCleanup() {
        assertEquals(MetadataRecoveryActionPolicy.Reason.JOURNAL_CORRUPT,check(readable=false).reason)
    }
    @Test fun mutatingNeverAllowsPrivateCleanup() {
        assertEquals(MetadataRecoveryActionPolicy.Action.KEEP_PRIVATE_ARTIFACTS,
            check(phase=MetadataRecoveryPhase.MUTATING).action)
    }
    @Test fun unknownBytesNeverAllowsCleanup() {
        assertEquals(MetadataRecoveryActionPolicy.Reason.SOURCE_MAY_HAVE_CHANGED,
            check(phase=MetadataRecoveryPhase.MUTATION_UNKNOWN).reason)
    }
    @Test fun postcheckNeverAutomaticallyCleansBackup() {
        assertEquals(MetadataRecoveryActionPolicy.Action.KEEP_PRIVATE_ARTIFACTS,
            check(phase=MetadataRecoveryPhase.POSTCHECK_VERIFIED).action)
    }
    @Test fun restoredStillRequiresCertifiedCleanup() {
        assertEquals(MetadataRecoveryActionPolicy.Action.KEEP_PRIVATE_ARTIFACTS,
            check(phase=MetadataRecoveryPhase.RESTORED_VERIFIED).action)
    }
    @Test fun cleanupSafePhaseDoesNotReauthorizeDeletion() {
        assertEquals(MetadataRecoveryActionPolicy.Action.KEEP_PRIVATE_ARTIFACTS,
            check(phase=MetadataRecoveryPhase.CLEANUP_SAFE).action)
    }
}
