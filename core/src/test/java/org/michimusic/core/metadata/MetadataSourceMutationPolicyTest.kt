package org.michimusic.core.metadata

import org.junit.Assert.assertEquals
import org.junit.Test

class MetadataSourceMutationPolicyTest {
    private fun valid() = MetadataSourceMutationPolicy.Input(
        explicitUserAction = true,
        phase = MetadataRecoveryPhase.STAGE_READY,
        transactionMatches = true,
        exactTargetMatches = true,
        sourceBytesReverified = true,
        privateArtifactsVerified = true,
        playbackClosed = true,
        exactWriteGrantPresent = true,
        provider = MetadataDocumentProvider.SAF,
        format = MetadataAudioContainer.FLAC,
        formatAndFieldsCertified = true,
        transportCertified = true,
        providerRecoveryCertified = true,
    )
    private fun blocked(input: MetadataSourceMutationPolicy.Input,
        expected: MetadataSourceMutationPolicy.Reason) {
        assertEquals(MetadataSourceMutationPolicy.Verdict.Blocked(expected),
            MetadataSourceMutationPolicy.evaluate(input))
    }

    @Test fun completeSyntheticPrerequisitesAreNotAWriteGrant() {
        assertEquals(MetadataSourceMutationPolicy.Verdict.PrerequisitesObserved,
            MetadataSourceMutationPolicy.evaluate(valid()))
        // The production mutation gate remains a separate immutable false.
        assertEquals(false, MetadataCommitCertification.mayMutateOriginal())
    }
    @Test fun requiresExplicitAction() = blocked(valid().copy(explicitUserAction=false),
        MetadataSourceMutationPolicy.Reason.USER_ACTION_REQUIRED)
    @Test fun requiresStageReadyPhase() = blocked(valid().copy(phase=MetadataRecoveryPhase.MUTATING),
        MetadataSourceMutationPolicy.Reason.JOURNAL_NOT_STAGE_READY)
    @Test fun rejectsReusedTransaction() = blocked(valid().copy(transactionMatches=false),
        MetadataSourceMutationPolicy.Reason.TRANSACTION_CHANGED)
    @Test fun rejectsReplacedDocument() = blocked(valid().copy(exactTargetMatches=false),
        MetadataSourceMutationPolicy.Reason.TARGET_CHANGED)
    @Test fun requiresFullSourceDigest() = blocked(valid().copy(sourceBytesReverified=false),
        MetadataSourceMutationPolicy.Reason.SOURCE_UNVERIFIED)
    @Test fun requiresDurableBackupAndCandidate() = blocked(valid().copy(privateArtifactsVerified=false),
        MetadataSourceMutationPolicy.Reason.PRIVATE_BACKUP_UNVERIFIED)
    @Test fun disallowsPlayback() = blocked(valid().copy(playbackClosed=false),
        MetadataSourceMutationPolicy.Reason.SOURCE_IN_PLAYBACK)
    @Test fun requiresExactUriGrant() = blocked(valid().copy(exactWriteGrantPresent=false),
        MetadataSourceMutationPolicy.Reason.GRANT_MISSING)
    @Test fun rejectsUncertifiedFormat() = blocked(valid().copy(formatAndFieldsCertified=false),
        MetadataSourceMutationPolicy.Reason.FORMAT_NOT_CERTIFIED)
    @Test fun rejectsUnknownTransport() = blocked(valid().copy(transportCertified=false),
        MetadataSourceMutationPolicy.Reason.TRANSPORT_NOT_CERTIFIED)
    @Test fun rejectsUnprovenRestore() = blocked(valid().copy(providerRecoveryCertified=false),
        MetadataSourceMutationPolicy.Reason.RECOVERY_NOT_CERTIFIED)
    @Test fun rejectsRemoteProvider() = blocked(
        valid().copy(provider=MetadataDocumentProvider.REMOTE),
        MetadataSourceMutationPolicy.Reason.PROVIDER_UNSUPPORTED)
    @Test fun rejectsUnsupportedContainer() = blocked(
        valid().copy(format=MetadataAudioContainer.MP4_M4A),
        MetadataSourceMutationPolicy.Reason.FORMAT_NOT_CERTIFIED)
    @Test fun sourceProofWinsOverUriGrant() = blocked(
        valid().copy(sourceBytesReverified=false, exactWriteGrantPresent=false),
        MetadataSourceMutationPolicy.Reason.SOURCE_UNVERIFIED)
    @Test fun acceptsId3InPureReviewOnly() {
        assertEquals(MetadataSourceMutationPolicy.Verdict.PrerequisitesObserved,
            MetadataSourceMutationPolicy.evaluate(valid().copy(format=MetadataAudioContainer.ID3_V24,
                provider=MetadataDocumentProvider.MEDIASTORE)))
    }
}
