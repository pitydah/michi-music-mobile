package org.michimusic.core.metadata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class MetadataProviderRecoveryProfileTest {
    private fun complete(provider: MetadataProviderRecoveryProfile.Kind =
        MetadataProviderRecoveryProfile.Kind.MEDIASTORE_AUDIO) =
        MetadataProviderRecoveryProfile.Evidence(provider,true,true,true,true,true,
            true,true,true,true,true,true,true,true)

    @Test fun remoteRejectedEvenWithAllFlags() {
        assertEquals(MetadataProviderRecoveryProfile.Verdict.REJECT_PROVIDER,
            MetadataProviderRecoveryProfile.evaluate(complete(
                MetadataProviderRecoveryProfile.Kind.UNKNOWN_OR_REMOTE)))
    }
    @Test fun missingExactGrantRejected() {
        assertEquals(MetadataProviderRecoveryProfile.Verdict.DENY_UNSCOPED_GRANT,
            MetadataProviderRecoveryProfile.evaluate(complete().copy(exactUriGrant=false)))
    }
    @Test fun sourceOwnerMustBeCurrent() {
        assertEquals(MetadataProviderRecoveryProfile.Verdict.DENY_UNVERIFIED_STAMP,
            MetadataProviderRecoveryProfile.evaluate(complete().copy(localOwnerAndDocumentRevalidated=false)))
    }
    @Test fun playbackActiveIsNoGo() {
        assertEquals(MetadataProviderRecoveryProfile.Verdict.DENY_ACTIVE_PLAYBACK,
            MetadataProviderRecoveryProfile.evaluate(complete().copy(sourceClosedForPlayback=false)))
    }
    @Test fun backupAndStageMustBothBeVerified() {
        assertEquals(MetadataProviderRecoveryProfile.Verdict.DENY_UNVERIFIED_BACKUP,
            MetadataProviderRecoveryProfile.evaluate(complete().copy(stagedCandidateFullShaVerified=false)))
    }
    @Test fun providerMustHavePartialTruncateAndCloseCutTests() {
        for(e in listOf(complete().copy(testedAfterPartialWrite=false),
            complete().copy(testedAfterTruncate=false),
            complete().copy(testedAfterProviderCloseFailure=false),
            complete().copy(providerCapabilityRecordExists=false))) {
            assertEquals(MetadataProviderRecoveryProfile.Verdict.DENY_UNPROVEN_WRITE_BEHAVIOR,
                MetadataProviderRecoveryProfile.evaluate(e))
        }
    }
    @Test fun restoreMustBeFullDigestAndPreserveOtherTags() {
        assertEquals(MetadataProviderRecoveryProfile.Verdict.DENY_UNPROVEN_RECOVERY,
            MetadataProviderRecoveryProfile.evaluate(complete().copy(originalRestoreFullShaVerified=false)))
        assertEquals(MetadataProviderRecoveryProfile.Verdict.DENY_UNPROVEN_RECOVERY,
            MetadataProviderRecoveryProfile.evaluate(complete().copy(unknownTagsAndAudioBytesPreserved=false)))
    }
    @Test fun readbackMustReopenExactProviderUri() {
        assertEquals(MetadataProviderRecoveryProfile.Verdict.DENY_UNVERIFIED_READBACK,
            MetadataProviderRecoveryProfile.evaluate(complete().copy(reopenExactUriVerified=false)))
    }
    @Test fun safAndMediaStoreCanBeReviewedButNeverAutoCertified() {
        for (kind in listOf(MetadataProviderRecoveryProfile.Kind.SAF_LOCAL_DOCUMENT,
            MetadataProviderRecoveryProfile.Kind.MEDIASTORE_AUDIO)) {
            val evidence=complete(kind)
            assertEquals(MetadataProviderRecoveryProfile.Verdict.READY_FOR_EXTERNAL_REVIEW,
                MetadataProviderRecoveryProfile.evaluate(evidence))
            assertFalse(MetadataProviderRecoveryProfile.permitsSourceMutation(evidence))
        }
    }
}
