package org.michimusic.core.metadata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MetadataBatchReviewDigestTest {
    private fun original(title:String,id:String="x")=MetadataSnapshot(
        MetadataTarget("",id,"content://media/$id","owner-$id"),true,
        mapOf(MetadataField.TITLE to listOf(title),MetadataField.GENRE to listOf("Jazz; Fusion")))
    private fun change(s:MetadataSnapshot, title:String)=MetadataEditPreview.review(s,listOf(
        MetadataChange.Replace(MetadataField.TITLE,listOf(title))))
    @Test fun deterministicForIdenticalBatch() {
        val x=original("Old");val r=listOf(change(x,"New"))
        assertEquals(MetadataBatchReviewDigest.digest(listOf(x),r),
            MetadataBatchReviewDigest.digest(listOf(x),r))
    }
    @Test fun changesToProposedValuesRejectResume() {
        val x=original("Old")
        assertNotEquals(MetadataBatchReviewDigest.digest(listOf(x),listOf(change(x,"New"))),
            MetadataBatchReviewDigest.digest(listOf(x),listOf(change(x,"Other"))))
    }
    @Test fun ownerChangeRejectsResume() {
        val a=original("Old","a");val b=original("Old","b")
        assertNotEquals(MetadataBatchReviewDigest.digest(listOf(a),listOf(change(a,"New"))),
            MetadataBatchReviewDigest.digest(listOf(b),listOf(change(b,"New"))))
    }
    @Test fun selectionOrderIsPartOfFingerprint() {
        val a=original("A","a");val b=original("B","b")
        assertNotEquals(MetadataBatchReviewDigest.digest(listOf(a,b),listOf(change(a,"Z"),change(b,"Z"))),
            MetadataBatchReviewDigest.digest(listOf(b,a),listOf(change(b,"Z"),change(a,"Z"))))
    }
    @Test fun forgedReadyRefused() {
        val a=original("A")
        assertEquals(null,MetadataBatchReviewDigest.digest(listOf(a),listOf(
            MetadataReview.Ready(a,a,emptyList()))))
    }
    @Test fun literalDelimiterCannotAliasTwoValues() {
        val x=original("Old")
        assertTrue(MetadataCommitIntentManifest.valuesDigest(listOf("Jazz; Fusion")) !=
            MetadataCommitIntentManifest.valuesDigest(listOf("Jazz","Fusion")))
    }
}
