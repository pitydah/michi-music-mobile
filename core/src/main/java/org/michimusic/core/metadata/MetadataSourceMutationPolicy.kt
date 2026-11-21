package org.michimusic.core.metadata

/**
 * ME-10M. A source write must never be inferred from a private candidate, a
 * permission grant, or a matching hash alone. This policy is only the last
 * prerequisite check before a separately certified provider transport.
 * A Ready verdict is NOT a write authorization and does not set CAN_COMMIT.
 */
object MetadataSourceMutationPolicy {
    enum class Reason {
        USER_ACTION_REQUIRED, JOURNAL_NOT_STAGE_READY, TRANSACTION_CHANGED,
        TARGET_CHANGED, SOURCE_UNVERIFIED, PRIVATE_BACKUP_UNVERIFIED,
        SOURCE_IN_PLAYBACK, GRANT_MISSING, PROVIDER_UNSUPPORTED,
        FORMAT_NOT_CERTIFIED, TRANSPORT_NOT_CERTIFIED, RECOVERY_NOT_CERTIFIED,
    }

    data class Input(
        val explicitUserAction: Boolean,
        val phase: MetadataRecoveryPhase,
        val transactionMatches: Boolean,
        val exactTargetMatches: Boolean,
        val sourceBytesReverified: Boolean,
        val privateArtifactsVerified: Boolean,
        val playbackClosed: Boolean,
        val exactWriteGrantPresent: Boolean,
        val provider: MetadataDocumentProvider,
        val format: MetadataAudioContainer,
        val formatAndFieldsCertified: Boolean,
        val transportCertified: Boolean,
        val providerRecoveryCertified: Boolean,
    )

    sealed interface Verdict {
        data class Blocked(val reason: Reason) : Verdict
        /** This alone never authorizes IO; production has independent closed gates. */
        data object PrerequisitesObserved : Verdict
    }

    fun evaluate(input: Input): Verdict = when {
        !input.explicitUserAction -> Verdict.Blocked(Reason.USER_ACTION_REQUIRED)
        input.phase != MetadataRecoveryPhase.STAGE_READY ->
            Verdict.Blocked(Reason.JOURNAL_NOT_STAGE_READY)
        !input.transactionMatches -> Verdict.Blocked(Reason.TRANSACTION_CHANGED)
        !input.exactTargetMatches -> Verdict.Blocked(Reason.TARGET_CHANGED)
        !input.sourceBytesReverified -> Verdict.Blocked(Reason.SOURCE_UNVERIFIED)
        !input.privateArtifactsVerified -> Verdict.Blocked(Reason.PRIVATE_BACKUP_UNVERIFIED)
        !input.playbackClosed -> Verdict.Blocked(Reason.SOURCE_IN_PLAYBACK)
        !input.exactWriteGrantPresent -> Verdict.Blocked(Reason.GRANT_MISSING)
        input.provider !in setOf(MetadataDocumentProvider.MEDIASTORE, MetadataDocumentProvider.SAF) ->
            Verdict.Blocked(Reason.PROVIDER_UNSUPPORTED)
        input.format !in setOf(MetadataAudioContainer.ID3_V23, MetadataAudioContainer.ID3_V24,
            MetadataAudioContainer.FLAC) || !input.formatAndFieldsCertified ->
            Verdict.Blocked(Reason.FORMAT_NOT_CERTIFIED)
        !input.transportCertified -> Verdict.Blocked(Reason.TRANSPORT_NOT_CERTIFIED)
        !input.providerRecoveryCertified -> Verdict.Blocked(Reason.RECOVERY_NOT_CERTIFIED)
        else -> Verdict.PrerequisitesObserved
    }
}
