package org.michimusic.core.metadata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MetadataBatchReceiptCodecTest {
    private val id="12345678-1234-4123-8123-123456789abc"
    private fun track(n: Int)=MetadataSnapshot(MetadataTarget("",n.toString(),
        "content://media/$n","e-$n"),true,mapOf(MetadataField.TITLE to listOf("$n")))
    @Test fun progressSurvivesRoundTripForSameExactOwner() {
        val snapshots=listOf(track(1),track(2))
        val rows=listOf(MetadataBatchRunPolicy.FileResult(0,snapshots[0].target,
            MetadataBatchRunPolicy.State.PRIVATE_STAGE_READY,id),
            MetadataBatchRunPolicy.FileResult(1,snapshots[1].target,
                MetadataBatchRunPolicy.State.NOT_STARTED))
        val created=MetadataBatchReceiptCodec.create(id,snapshots,rows)
            as MetadataBatchReceiptCodec.Result.Valid
        val decoded=MetadataBatchReceiptCodec.decode(
            MetadataBatchReceiptCodec.encode(created.record),snapshots)
        assertEquals(created,decoded)
    }
    @Test fun otherOwnerCannotResumeSameBatch() {
        val snapshots=listOf(track(1))
        val row=listOf(MetadataBatchRunPolicy.FileResult(0,snapshots[0].target,
            MetadataBatchRunPolicy.State.NOT_STARTED))
        val body=MetadataBatchReceiptCodec.encode(
            (MetadataBatchReceiptCodec.create(id,snapshots,row) as MetadataBatchReceiptCodec.Result.Valid).record)
        assertEquals(MetadataBatchReceiptCodec.Result.Blocked(MetadataBatchReceiptCodec.Reason.OWNER_MISMATCH),
            MetadataBatchReceiptCodec.decode(body,listOf(track(2))))
    }
    @Test fun modifiedReceiptRejected() {
        val a=track(1)
        val row=listOf(MetadataBatchRunPolicy.FileResult(0,a.target,
            MetadataBatchRunPolicy.State.NOT_STARTED))
        val body=MetadataBatchReceiptCodec.encode(
            (MetadataBatchReceiptCodec.create(id,listOf(a),row) as MetadataBatchReceiptCodec.Result.Valid).record)
        assertTrue(MetadataBatchReceiptCodec.decode(body.replace("NOT_STARTED","SOURCE_MUTATION_UNKNOWN"),
            listOf(a)) is MetadataBatchReceiptCodec.Result.Blocked)
    }
}
