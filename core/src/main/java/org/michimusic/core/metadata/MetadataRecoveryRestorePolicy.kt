package org.michimusic.core.metadata

/**
 * ME-10R: conservative, pure policy for explicit restoration of original bytes.
 * A source whose bytes are UNKNOWN must NEVER be overwritten with an old copy:
 * another editor, user, provider or process may have modified it since staging.
 * Requires a recognized candidate fingerprint and the exact retained backup.
 * This is NOT certification of provider-specific restore or rollback atomicity.
 */
object MetadataRecoveryRestorePolicy {
    enum class Refusal {
        USER_CONFIRMATION_REQUIRED, WRONG_PHASE, TARGET_CHANGED, PLAYBACK_ACTIVE,
        PRIVATE_BACKUP_INVALID, SOURCE_NOT_VERIFIED_CANDIDATE,
        PROVIDER_UNKNOWN, NO_EXACT_GRANT, PROVIDER_RECOVERY_UNCERTIFIED,
        SOURCE_WRITE_UNCERTIFIED,
    }
    sealed interface Decision {
        data object ReadyForTransportBehindCertification : Decision
        data class Blocked(val reason: Refusal) : Decision
    }
    data class Input(
        val explicitUserConfirmation: Boolean,
        val journalPhase: MetadataRecoveryPhase,
        val exactTargetStillCurrent: Boolean,
        val playbackClosed: Boolean,
        val backupIntact: Boolean,
        val observedSource: MetadataPhysicalOutcomePolicy.State,
        val provider: MetadataDocumentProvider,
        val exactWriteGrant: Boolean,
        val providerRecoveryCertified: Boolean,
        val sourceWriteCertified: Boolean,
    )
    fun evaluate(input: Input): Decision = when {
        !input.explicitUserConfirmation -> Decision.Blocked(Refusal.USER_CONFIRMATION_REQUIRED)
        input.journalPhase != MetadataRecoveryPhase.MUTATION_UNKNOWN ->
            Decision.Blocked(Refusal.WRONG_PHASE)
        !input.exactTargetStillCurrent -> Decision.Blocked(Refusal.TARGET_CHANGED)
        !input.playbackClosed -> Decision.Blocked(Refusal.PLAYBACK_ACTIVE)
        !input.backupIntact -> Decision.Blocked(Refusal.PRIVATE_BACKUP_INVALID)
        input.observedSource != MetadataPhysicalOutcomePolicy.State.CANDIDATE_BYTES_VERIFIED ->
            Decision.Blocked(Refusal.SOURCE_NOT_VERIFIED_CANDIDATE)
        input.provider !in setOf(MetadataDocumentProvider.MEDIASTORE,MetadataDocumentProvider.SAF) ->
            Decision.Blocked(Refusal.PROVIDER_UNKNOWN)
        !input.exactWriteGrant -> Decision.Blocked(Refusal.NO_EXACT_GRANT)
        !input.providerRecoveryCertified -> Decision.Blocked(Refusal.PROVIDER_RECOVERY_UNCERTIFIED)
        !input.sourceWriteCertified -> Decision.Blocked(Refusal.SOURCE_WRITE_UNCERTIFIED)
        else -> Decision.ReadyForTransportBehindCertification
    }
}
