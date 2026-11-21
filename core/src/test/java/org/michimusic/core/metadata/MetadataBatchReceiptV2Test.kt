package org.michimusic.core.metadata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MetadataBatchReceiptV2Test {
    private val batchId="12345678-1234-4123-8123-123456789abc"
    private fun snapshot()=MetadataSnapshot(MetadataTarget("","song",
        "content://media/external/audio/1","original-proof"),true,
        mapOf(MetadataField.TITLE to listOf("Before")))
    private fun review(x:MetadataSnapshot,value:String)=MetadataEditPreview.review(x,listOf(
        MetadataChange.Replace(MetadataField.TITLE,listOf(value))))
    @Test fun v2IncludesReviewHashAndRoundTrips() {
        val x=snapshot();val reviews=listOf(review(x,"After"))
        val rows=listOf(MetadataBatchRunPolicy.FileResult(0,x.target,
            MetadataBatchRunPolicy.State.NOT_STARTED))
        val rec=(MetadataBatchReceiptCodec.create(batchId,listOf(x),rows,reviews)
            as MetadataBatchReceiptCodec.Result.Valid).record
        assertTrue(rec.reviewSha256 != null)
        val decoded=MetadataBatchReceiptCodec.decode(
            MetadataBatchReceiptCodec.encode(rec),listOf(x),reviews)
        assertEquals(MetadataBatchReceiptCodec.Result.Valid(rec),decoded)
    }
    @Test fun differentApprovedValueCannotResumeProgress() {
        val x=snapshot();val reviews=listOf(review(x,"After"))
        val rows=listOf(MetadataBatchRunPolicy.FileResult(0,x.target,
            MetadataBatchRunPolicy.State.NOT_STARTED))
        val rec=(MetadataBatchReceiptCodec.create(batchId,listOf(x),rows,reviews)
            as MetadataBatchReceiptCodec.Result.Valid).record
        assertTrue(MetadataBatchReceiptCodec.decode(MetadataBatchReceiptCodec.encode(rec),
            listOf(x),listOf(review(x,"Revised"))) is MetadataBatchReceiptCodec.Result.Blocked)
    }
    @Test fun oldV1ReceiptCannotAuthorizeNewReviewBoundRun() {
        val x=snapshot();val rows=listOf(MetadataBatchRunPolicy.FileResult(0,x.target,
            MetadataBatchRunPolicy.State.NOT_STARTED))
        val old=(MetadataBatchReceiptCodec.create(batchId,listOf(x),rows)
            as MetadataBatchReceiptCodec.Result.Valid).record
        assertTrue(MetadataBatchReceiptCodec.decode(MetadataBatchReceiptCodec.encode(old),
            listOf(x),listOf(review(x,"After"))) is MetadataBatchReceiptCodec.Result.Blocked)
    }
    @Test fun tamperedV2HeaderCannotPassDigestCheck() {
        val x=snapshot();val reviews=listOf(review(x,"After"))
        val rows=listOf(MetadataBatchRunPolicy.FileResult(0,x.target,
            MetadataBatchRunPolicy.State.NOT_STARTED))
        val rec=(MetadataBatchReceiptCodec.create(batchId,listOf(x),rows,reviews)
            as MetadataBatchReceiptCodec.Result.Valid).record
        val raw=MetadataBatchReceiptCodec.encode(rec)
        assertTrue(MetadataBatchReceiptCodec.decode(raw.replace("_V2","_V3"),
            listOf(x),reviews) is MetadataBatchReceiptCodec.Result.Blocked)
    }
}
