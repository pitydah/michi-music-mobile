package org.michimusic.core.metadata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MetadataPreparePolicyTest {
    private fun original() = MetadataSnapshot(
        MetadataTarget("", "song", "content://media/external/audio/media/7", "proof"),
        true, mapOf(MetadataField.TITLE to listOf("Original")),
        verifiedFields = setOf(MetadataField.TITLE))
    private fun review(): MetadataReview = MetadataEditPreview.review(original(),
        listOf(MetadataChange.Replace(MetadataField.TITLE, listOf("New title"))))
    private fun ticket() = MetadataPrepareTicket("", "song", "content://media/external/audio/media/7",
        4096, true, true, true)
    private fun blocked(ticket: MetadataPrepareTicket, review: MetadataReview = review()) =
        (MetadataPreparePolicy.evaluate(ticket, review) as MetadataPrepareDecision.Refused).reason

    @Test fun validLocalBlankOwnerCanPrepareNotCommit() {
        assertEquals(MetadataPrepareDecision.EligibleToInspectProvider,
            MetadataPreparePolicy.evaluate(ticket(), review()))
    }
    @Test fun noImplicitGesture() { assertEquals(MetadataPrepareRejection.ACTION_REQUIRED,
        blocked(ticket().copy(explicitUserAction=false))) }
    @Test fun playbackDescriptorMustBeClosed() { assertEquals(MetadataPrepareRejection.ACTIVE_PLAYBACK,
        blocked(ticket().copy(playbackClosed=false))) }
    @Test fun sourceChangedBlocks() { assertEquals(MetadataPrepareRejection.OWNER_CHANGED,
        blocked(ticket().copy(ownerStillCurrent=false))) }
    @Test fun otherTrackCannotReapplyReceipt() { assertEquals(MetadataPrepareRejection.TARGET_MISMATCH,
        blocked(ticket().copy(trackId="different"))) }
    @Test fun remoteUriRejected() { assertEquals(MetadataPrepareRejection.INVALID_TARGET,
        blocked(ticket().copy(uri="https://example.invalid/audio"))) }
    @Test fun zeroAndGiantSizeRejected() {
        assertEquals(MetadataPrepareRejection.INVALID_SIZE, blocked(ticket().copy(expectedBytes=0)))
        assertEquals(MetadataPrepareRejection.INVALID_SIZE,
            blocked(ticket().copy(expectedBytes=MetadataPreparePolicy.MAX_ORIGINAL_BYTES+1)))
    }
    @Test fun noChangeCannotCreateBackup() { assertEquals(MetadataPrepareRejection.UNVERIFIED_REVIEW,
        blocked(ticket(), MetadataReview.Unchanged)) }
    @Test fun noFieldEvidenceBlocksEvenIfSnapshotIsTaggedVerified() {
        val base=original().copy(verifiedFields=emptySet())
        assertEquals(MetadataPrepareRejection.UNVERIFIED_REVIEW,
            blocked(ticket(), MetadataEditPreview.review(base,
                listOf(MetadataChange.Replace(MetadataField.TITLE, listOf("B"))))))
    }
    @Test fun forgedReadyDiffRefused() {
        val r=review() as MetadataReview.Ready
        val modified=r.copy(diffs=listOf(MetadataFieldDiff(MetadataField.TITLE,
            listOf("Wrong old value"), listOf("New title"))))
        assertEquals(MetadataPrepareRejection.UNVERIFIED_REVIEW,blocked(ticket(),modified))
    }
    @Test fun spaceReservesTwoCopiesAndSafetyMargin() {
        val n=4096L
        assertFalse(MetadataPreparePolicy.hasPrivateSpace(2*n+MetadataPreparePolicy.REQUIRED_RESERVE_BYTES-1,n))
        assertTrue(MetadataPreparePolicy.hasPrivateSpace(2*n+MetadataPreparePolicy.REQUIRED_RESERVE_BYTES,n))
    }
    @Test fun cannotDiscardMutatedOrUnknownJournal() {
        for(phase in MetadataRecoveryPhase.entries) {
            val discard=MetadataRecoveryPhasePolicy.canDiscardPrivateOnly(phase)
            assertEquals(phase in setOf(MetadataRecoveryPhase.SNAPSHOTTING,
                MetadataRecoveryPhase.BACKUP_DURABLE, MetadataRecoveryPhase.STAGE_READY),discard)
            assertEquals(!discard,MetadataRecoveryPhasePolicy.sourceMayHaveChanged(phase))
        }
    }
}
