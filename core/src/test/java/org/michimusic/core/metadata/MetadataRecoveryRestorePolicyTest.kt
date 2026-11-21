package org.michimusic.core.metadata

import org.junit.Assert.assertEquals
import org.junit.Test

class MetadataRecoveryRestorePolicyTest {
    private val policy=MetadataRecoveryRestorePolicy
    private fun input() = MetadataRecoveryRestorePolicy.Input(
        true, MetadataRecoveryPhase.MUTATION_UNKNOWN, true, true, true,
        MetadataPhysicalOutcomePolicy.State.CANDIDATE_BYTES_VERIFIED,
        MetadataDocumentProvider.MEDIASTORE,true,true,true)
    private fun blocked(input: MetadataRecoveryRestorePolicy.Input,
                        expected: MetadataRecoveryRestorePolicy.Refusal) = assertEquals(
        MetadataRecoveryRestorePolicy.Decision.Blocked(expected),policy.evaluate(input))
    @Test fun allPreconditionsProduceReviewOnlyReadiness() = assertEquals(
        MetadataRecoveryRestorePolicy.Decision.ReadyForTransportBehindCertification,policy.evaluate(input()))
    @Test fun noConfirmationNeverRestores() = blocked(input().copy(explicitUserConfirmation=false),
        MetadataRecoveryRestorePolicy.Refusal.USER_CONFIRMATION_REQUIRED)
    @Test fun stageReadyCannotBeRestored() = blocked(input().copy(journalPhase=MetadataRecoveryPhase.STAGE_READY),
        MetadataRecoveryRestorePolicy.Refusal.WRONG_PHASE)
    @Test fun invalidOwnerStopsRestoration() = blocked(input().copy(exactTargetStillCurrent=false),
        MetadataRecoveryRestorePolicy.Refusal.TARGET_CHANGED)
    @Test fun playbackMustBeClosed() = blocked(input().copy(playbackClosed=false),
        MetadataRecoveryRestorePolicy.Refusal.PLAYBACK_ACTIVE)
    @Test fun corruptBackupStopsRestoration() = blocked(input().copy(backupIntact=false),
        MetadataRecoveryRestorePolicy.Refusal.PRIVATE_BACKUP_INVALID)
    @Test fun unknownCurrentBytesStopRestoration() = blocked(
        input().copy(observedSource=MetadataPhysicalOutcomePolicy.State.UNKNOWN_BYTES),
        MetadataRecoveryRestorePolicy.Refusal.SOURCE_NOT_VERIFIED_CANDIDATE)
    @Test fun alreadyOriginalIsNotOverwritten() = blocked(
        input().copy(observedSource=MetadataPhysicalOutcomePolicy.State.ORIGINAL_INTACT),
        MetadataRecoveryRestorePolicy.Refusal.SOURCE_NOT_VERIFIED_CANDIDATE)
    @Test fun unseekableProviderIsNotAccepted() = blocked(
        input().copy(provider=MetadataDocumentProvider.UNKNOWN),MetadataRecoveryRestorePolicy.Refusal.PROVIDER_UNKNOWN)
    @Test fun permissionRequired() = blocked(input().copy(exactWriteGrant=false),
        MetadataRecoveryRestorePolicy.Refusal.NO_EXACT_GRANT)
    @Test fun providerNotCertified() = blocked(input().copy(providerRecoveryCertified=false),
        MetadataRecoveryRestorePolicy.Refusal.PROVIDER_RECOVERY_UNCERTIFIED)
    @Test fun globalWriterClosed() = blocked(input().copy(sourceWriteCertified=false),
        MetadataRecoveryRestorePolicy.Refusal.SOURCE_WRITE_UNCERTIFIED)
}
