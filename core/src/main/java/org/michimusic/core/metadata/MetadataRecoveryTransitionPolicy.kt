package org.michimusic.core.metadata

/**
 * ME-06. Append-only logical transition graph. Any UNKNOWN_BYTES state must
 * preserve original+candidate private copies and block new operations.
 */
object MetadataRecoveryTransitionPolicy {
    enum class Decision { ALLOWED, REFUSED, PROTECT_PRIVATE_ARTIFACTS }
    fun advance(from: MetadataRecoveryPhase, to: MetadataRecoveryPhase): Decision = when (from) {
        MetadataRecoveryPhase.SNAPSHOTTING -> if (to == MetadataRecoveryPhase.BACKUP_DURABLE)
            Decision.ALLOWED else Decision.REFUSED
        MetadataRecoveryPhase.BACKUP_DURABLE -> if (to == MetadataRecoveryPhase.STAGE_READY)
            Decision.ALLOWED else Decision.REFUSED
        MetadataRecoveryPhase.STAGE_READY -> if (to == MetadataRecoveryPhase.MUTATING)
            Decision.PROTECT_PRIVATE_ARTIFACTS else Decision.REFUSED
        MetadataRecoveryPhase.MUTATING -> if (to in setOf(MetadataRecoveryPhase.MUTATION_UNKNOWN,
                MetadataRecoveryPhase.POSTCHECK_VERIFIED, MetadataRecoveryPhase.ROLLBACK_PENDING))
            Decision.PROTECT_PRIVATE_ARTIFACTS else Decision.REFUSED
        MetadataRecoveryPhase.MUTATION_UNKNOWN -> if (to in setOf(
                MetadataRecoveryPhase.POSTCHECK_VERIFIED, MetadataRecoveryPhase.ROLLBACK_PENDING,
                MetadataRecoveryPhase.RESTORED_VERIFIED))
            Decision.PROTECT_PRIVATE_ARTIFACTS else Decision.REFUSED
        MetadataRecoveryPhase.ROLLBACK_PENDING -> if (to == MetadataRecoveryPhase.RESTORED_VERIFIED)
            Decision.PROTECT_PRIVATE_ARTIFACTS else Decision.REFUSED
        MetadataRecoveryPhase.POSTCHECK_VERIFIED,
        MetadataRecoveryPhase.RESTORED_VERIFIED -> if (to == MetadataRecoveryPhase.CLEANUP_SAFE)
            Decision.PROTECT_PRIVATE_ARTIFACTS else Decision.REFUSED
        MetadataRecoveryPhase.CLEANUP_SAFE -> Decision.REFUSED
    }
}
