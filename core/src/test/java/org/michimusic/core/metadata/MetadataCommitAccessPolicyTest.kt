package org.michimusic.core.metadata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class MetadataCommitAccessPolicyTest {
    private val id="00000000-2222-4222-8222-000000000000"
    private val uri="content://media/external/audio/media/30"
    private fun request()=MetadataExactCommitRequest("","t1",uri,id,
        MetadataExactGrantKind.MEDIASTORE_ITEM,true,true,true,true,true,true,false)
    private fun decision(r: MetadataExactCommitRequest)=MetadataCommitAccessPolicy.evaluate(r,id,"","t1",uri,
        MetadataExactGrantKind.MEDIASTORE_ITEM)
    private fun blocked(r: MetadataExactCommitRequest)=
        (decision(r) as MetadataCommitGateResult.Blocked).reason

    @Test fun matchingLocalOwnerAndUriOnlyPassesPreflightNotCertification() {
        assertEquals(MetadataCommitGateResult.ReadyForCertifiedCapabilityCheck,decision(request()))
        assertFalse(MetadataCommitCertification.mayMutateOriginal())
    }
    @Test fun differentTransactionIdCannotBorrowConsent() {
        assertEquals(MetadataCommitBlock.NOT_PREPARED,blocked(request().copy(transactionId="other")))
    }
    @Test fun differentTrackIdIsWrongOwner() {
        assertEquals(MetadataCommitBlock.WRONG_OWNER,blocked(request().copy(trackId="t2")))
    }
    @Test fun otherDocumentNeverBorrowsGrant() {
        assertEquals(MetadataCommitBlock.WRONG_DOCUMENT,
            blocked(request().copy(contentUri="content://media/external/audio/media/31")))
    }
    @Test fun providerTypeMismatchIsRejected() {
        assertEquals(MetadataCommitBlock.WRONG_DOCUMENT,
            blocked(request().copy(provider=MetadataExactGrantKind.SAF_DOCUMENT)))
    }
    @Test fun remoteUriIsNotLocal() {
        assertEquals(MetadataCommitBlock.NOT_LOCAL,blocked(request().copy(contentUri="https://remote.invalid/a")))
    }
    @Test fun gestureRequiredForEveryAttempt() {
        assertEquals(MetadataCommitBlock.NO_EXPLICIT_USER_ACTION,
            blocked(request().copy(explicitUserAction=false)))
    }
    @Test fun playbackIsHardGate() {
        assertEquals(MetadataCommitBlock.PLAYBACK_ACTIVE,blocked(request().copy(playbackClosed=false)))
    }
    @Test fun grantWithoutRecoverabilityCannotCommit() {
        assertEquals(MetadataCommitBlock.PROVIDER_NOT_RECOVERABLE,
            blocked(request().copy(providerRecoverable=false)))
    }
    @Test fun grantNotConfirmedNeverPasses() {
        assertEquals(MetadataCommitBlock.GRANT_NOT_CONFIRMED,
            blocked(request().copy(grantConfirmed=false)))
    }
    @Test fun recoveryPendingAlwaysBlocks() {
        assertEquals(MetadataCommitBlock.RECOVERY_PENDING,blocked(request().copy(recoveryPending=true)))
    }
    @Test fun sourceChangeAtTimeOfUseAlwaysBlocks() {
        assertEquals(MetadataCommitBlock.CHANGED_SOURCE,
            blocked(request().copy(exactTargetCurrent=false)))
        assertEquals(MetadataCommitBlock.CHANGED_SOURCE,
            blocked(request().copy(ownerStillCurrent=false)))
    }
}
