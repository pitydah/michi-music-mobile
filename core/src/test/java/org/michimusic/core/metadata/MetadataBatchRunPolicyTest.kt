package org.michimusic.core.metadata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MetadataBatchRunPolicyTest {
    private fun original(n: Int)=MetadataSnapshot(MetadataTarget("",n.toString(),
        "content://media/$n","proof-$n"),true,
        mapOf(MetadataField.TITLE to listOf("Title $n")))
    private fun review(x: MetadataSnapshot)=MetadataEditPreview.review(x,listOf(
        MetadataChange.Replace(MetadataField.TITLE,listOf("Revised"))))
    @Test fun mixedBatchNeverBecomesImplicitSuccess() {
        val a=original(1);val b=original(2)
        val rows=listOf(MetadataBatchRunPolicy.FileResult(0,a.target,
            MetadataBatchRunPolicy.State.PRIVATE_STAGE_READY,
            "12345678-1234-4123-8123-123456789abc"),
            MetadataBatchRunPolicy.FileResult(1,b.target,
                MetadataBatchRunPolicy.State.SOURCE_MUTATION_UNKNOWN,
                "12345678-1234-4123-8123-123456789abd"))
        val result=MetadataBatchRunPolicy.summarize(listOf(a,b),listOf(review(a),review(b)),rows)
        assertTrue(result is MetadataBatchRunPolicy.Result.Ready)
        val s=(result as MetadataBatchRunPolicy.Result.Ready).summary
        assertEquals(1,s.privatelyPrepared)
        assertEquals(1,s.uncertainOrRecovery)
        assertEquals(0,s.physicallySaved)
        assertEquals(false,s.allSucceeded)
    }
    @Test fun missingRowBlocksEntireSummary() {
        val a=original(1)
        assertTrue(MetadataBatchRunPolicy.summarize(listOf(a),listOf(review(a)),emptyList())
            is MetadataBatchRunPolicy.Result.Blocked)
    }
    @Test fun duplicateTransactionRejected() {
        val a=original(1);val b=original(2)
        val id="12345678-1234-4123-8123-123456789abc"
        assertEquals(MetadataBatchRunPolicy.Result.Blocked(
            MetadataBatchRunPolicy.Refusal.DUPLICATE_TRANSACTION,1),
            MetadataBatchRunPolicy.summarize(listOf(a,b),listOf(review(a),review(b)),
                listOf(MetadataBatchRunPolicy.FileResult(0,a.target,
                    MetadataBatchRunPolicy.State.PRIVATE_STAGE_READY,id),
                    MetadataBatchRunPolicy.FileResult(1,b.target,
                        MetadataBatchRunPolicy.State.PRIVATE_STAGE_READY,id))))
    }
    @Test fun wrongTargetCannotBorrowReceipt() {
        val a=original(1)
        assertTrue(MetadataBatchRunPolicy.summarize(listOf(a),listOf(review(a)),
            listOf(MetadataBatchRunPolicy.FileResult(0,original(2).target,
                MetadataBatchRunPolicy.State.NOT_STARTED))) is MetadataBatchRunPolicy.Result.Blocked)
    }
    @Test fun unmodifiedTrackMustStayUnchanged() {
        val a=original(1)
        val result=MetadataBatchRunPolicy.summarize(listOf(a),listOf(MetadataReview.Unchanged),
            listOf(MetadataBatchRunPolicy.FileResult(0,a.target,MetadataBatchRunPolicy.State.UNCHANGED)))
        assertTrue(result is MetadataBatchRunPolicy.Result.Ready)
        assertEquals(1,(result as MetadataBatchRunPolicy.Result.Ready).summary.unchanged)
    }
}
