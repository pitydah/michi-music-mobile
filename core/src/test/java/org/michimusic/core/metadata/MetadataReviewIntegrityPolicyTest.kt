package org.michimusic.core.metadata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MetadataReviewIntegrityPolicyTest {
    private fun original() = MetadataSnapshot(
        MetadataTarget("", "id-1", "content://media/1", "cold-hash"),true,
        mapOf(MetadataField.GENRE to listOf("Jazz; Fusion")),
        opaque=mapOf("X-ORIGINAL" to listOf("preserve")),
        verifiedFields=setOf(MetadataField.GENRE,MetadataField.ARTIST))
    private fun review(): MetadataReview.Ready = MetadataEditPreview.review(
        original(),listOf(MetadataChange.Replace(MetadataField.GENRE,listOf("Bebop"))))
        as MetadataReview.Ready

    @Test fun exactReviewCanPreparePrivateCandidateButDoesNotAuthorizeWrite() {
        assertEquals(MetadataReviewIntegrityPolicy.Verdict.Exact,
            MetadataReviewIntegrityPolicy.inspect(review()))
    }
    @Test fun forgedChangedOwnerRejected() {
        val item=review()
        val falseOwner=item.copy(proposed=item.proposed.copy(
            target=item.proposed.target.copy(trackId="id-2")))
        assertTrue(MetadataReviewIntegrityPolicy.inspect(falseOwner)
            is MetadataReviewIntegrityPolicy.Verdict.Rejected)
    }
    @Test fun aModifiedOpaqueFrameIsNotPartOfFieldReview() {
        val item=review()
        assertTrue(MetadataReviewIntegrityPolicy.inspect(item.copy(proposed=item.proposed.copy(
            opaque=mapOf("X-ORIGINAL" to listOf("clobbered")))))
            is MetadataReviewIntegrityPolicy.Verdict.Rejected)
    }
    @Test fun fakeBeforeValueCannotBecomeEvidence() {
        val item=review()
        val fake=item.copy(diffs=listOf(item.diffs.single().copy(before=listOf("Disco"))))
        assertTrue(MetadataReviewIntegrityPolicy.inspect(fake)
            is MetadataReviewIntegrityPolicy.Verdict.Rejected)
    }
    @Test fun forgedExtraChangesAndDuplicateFieldsFailClosed() {
        val item=review()
        assertTrue(MetadataReviewIntegrityPolicy.inspect(item.copy(
            diffs=item.diffs+item.diffs.single()))
            is MetadataReviewIntegrityPolicy.Verdict.Rejected)
        assertTrue(MetadataReviewIntegrityPolicy.inspect(item.copy(
            proposed=item.proposed.copy(known=mapOf(MetadataField.GENRE to listOf("Rock")))))
            is MetadataReviewIntegrityPolicy.Verdict.Rejected)
    }
    @Test fun unverifiedFieldIsNeverPromotedByReadyWrapper() {
        val item=review()
        val fake=item.copy(diffs=listOf(MetadataFieldDiff(MetadataField.TITLE,
            emptyList(),listOf("Danger"))))
        assertTrue(MetadataReviewIntegrityPolicy.inspect(fake)
            is MetadataReviewIntegrityPolicy.Verdict.Rejected)
    }
}
