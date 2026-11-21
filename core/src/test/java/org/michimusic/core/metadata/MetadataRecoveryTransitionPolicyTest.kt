package org.michimusic.core.metadata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MetadataRecoveryTransitionPolicyTest {
    private fun transition(a:MetadataRecoveryPhase,b:MetadataRecoveryPhase)=
        MetadataRecoveryTransitionPolicy.advance(a,b)
    @Test fun prepareChainOnlyMovesForward() {
        assertEquals(MetadataRecoveryTransitionPolicy.Decision.ALLOWED,
            transition(MetadataRecoveryPhase.SNAPSHOTTING,MetadataRecoveryPhase.BACKUP_DURABLE))
        assertEquals(MetadataRecoveryTransitionPolicy.Decision.ALLOWED,
            transition(MetadataRecoveryPhase.BACKUP_DURABLE,MetadataRecoveryPhase.STAGE_READY))
    }
    @Test fun noJumpToMutationWithoutStage() {
        assertEquals(MetadataRecoveryTransitionPolicy.Decision.REFUSED,
            transition(MetadataRecoveryPhase.SNAPSHOTTING,MetadataRecoveryPhase.MUTATING))
    }
    @Test fun proposedMutationIsProtectionClassNotAuthorization() {
        assertEquals(MetadataRecoveryTransitionPolicy.Decision.PROTECT_PRIVATE_ARTIFACTS,
            transition(MetadataRecoveryPhase.STAGE_READY,MetadataRecoveryPhase.MUTATING))
        assertEquals(false,MetadataCommitCertification.mayMutateOriginal())
    }
    @Test fun ambiguousSourceRequiresPreservedBackups() {
        assertEquals(MetadataRecoveryTransitionPolicy.Decision.PROTECT_PRIVATE_ARTIFACTS,
            transition(MetadataRecoveryPhase.MUTATING,MetadataRecoveryPhase.MUTATION_UNKNOWN))
        assertEquals(MetadataRecoveryTransitionPolicy.Decision.PROTECT_PRIVATE_ARTIFACTS,
            transition(MetadataRecoveryPhase.MUTATION_UNKNOWN,MetadataRecoveryPhase.ROLLBACK_PENDING))
    }
    @Test fun terminalStateCannotMutate() {
        for (phase in MetadataRecoveryPhase.entries) assertEquals(
            MetadataRecoveryTransitionPolicy.Decision.REFUSED,
            transition(MetadataRecoveryPhase.CLEANUP_SAFE,phase))
    }
    @Test fun noAutomaticRollbackFromPrepared() {
        assertEquals(MetadataRecoveryTransitionPolicy.Decision.REFUSED,
            transition(MetadataRecoveryPhase.STAGE_READY,MetadataRecoveryPhase.ROLLBACK_PENDING))
    }
}
