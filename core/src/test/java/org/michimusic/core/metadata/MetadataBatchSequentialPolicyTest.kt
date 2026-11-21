package org.michimusic.core.metadata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MetadataBatchSequentialPolicyTest {
    private fun snap(n: Int)=MetadataSnapshot(MetadataTarget("", "track$n",
        "content://media/external/audio/$n", "verified$n"),true,
        mapOf(MetadataField.TITLE to listOf("Old$n")))
    private fun review(s: MetadataSnapshot)=MetadataEditPreview.review(s,listOf(
        MetadataChange.Replace(MetadataField.TITLE,listOf("New"))))
    private fun setup(): Triple<List<MetadataSnapshot>,List<MetadataReview>,List<MetadataBatchRunPolicy.FileResult>> {
        val snaps=listOf(snap(1),snap(2));val reviews=snaps.map(::review)
        return Triple(snaps,reviews,MetadataBatchSequentialPolicy.initial(reviews,snaps)!!)
    }
    @Test fun firstRowEligibleWithOneClearJournal() {
        val(s,r,rows)=setup()
        assertTrue(MetadataBatchSequentialPolicy.next(s,r,rows,0,true,true)
            is MetadataBatchSequentialPolicy.Verdict.Eligible)
    }
    @Test fun noImplicitGesture() {
        val(s,r,rows)=setup()
        assertEquals(MetadataBatchSequentialPolicy.Verdict.Blocked(
            MetadataBatchSequentialPolicy.Reason.ACTION_REQUIRED),
            MetadataBatchSequentialPolicy.next(s,r,rows,0,false,true))
    }
    @Test fun cannotSkipEarlierTrack() {
        val(s,r,rows)=setup()
        assertEquals(MetadataBatchSequentialPolicy.Verdict.Blocked(
            MetadataBatchSequentialPolicy.Reason.WRONG_SELECTION_ORDER,0),
            MetadataBatchSequentialPolicy.next(s,r,rows,1,true,true))
    }
    @Test fun existingStageBlocksEntireBatch() {
        val(s,r,rows)=setup()
        val stage=MetadataBatchSequentialPolicy.record(rows,0,
            "12345678-1234-4123-8123-123456789abc") as MetadataBatchSequentialPolicy.Transition.Updated
        assertEquals(MetadataBatchSequentialPolicy.Verdict.Blocked(
            MetadataBatchSequentialPolicy.Reason.EXISTING_PRIVATE_STAGE),
            MetadataBatchSequentialPolicy.next(s,r,stage.rows,1,true,true))
    }
    @Test fun journalAloneAlsoBlocks() {
        val(s,r,rows)=setup()
        assertEquals(MetadataBatchSequentialPolicy.Verdict.Blocked(
            MetadataBatchSequentialPolicy.Reason.EXISTING_PRIVATE_STAGE),
            MetadataBatchSequentialPolicy.next(s,r,rows,0,true,false))
    }
    @Test fun blockedPriorRowAllowsNextOnlyOnNewGesture() {
        val(s,r,rows)=setup()
        val blocked=MetadataBatchSequentialPolicy.record(rows,0,null)
            as MetadataBatchSequentialPolicy.Transition.Updated
        assertTrue(MetadataBatchSequentialPolicy.next(s,r,blocked.rows,1,true,true)
            is MetadataBatchSequentialPolicy.Verdict.Eligible)
    }
    @Test fun cannotReuseSameTransaction() {
        val(s,r,rows)=setup()
        val stage=MetadataBatchSequentialPolicy.record(rows,0,
            "12345678-1234-4123-8123-123456789abc") as MetadataBatchSequentialPolicy.Transition.Updated
        assertEquals(MetadataBatchSequentialPolicy.Reason.ROW_STATE_INVALID,
            (MetadataBatchSequentialPolicy.record(stage.rows,0,
                "12345678-1234-4123-8123-123456789abc") as
                MetadataBatchSequentialPolicy.Transition.Blocked).reason)
    }
    @Test fun wrongSelectionIdentityRejected() {
        val(s,r,rows)=setup()
        assertTrue(MetadataBatchSequentialPolicy.next(s.reversed(),r,rows,0,true,true)
            is MetadataBatchSequentialPolicy.Verdict.Blocked)
    }
    @Test fun malformedReadyIsNotTrusted() {
        val s=listOf(snap(1));val r=listOf(MetadataReview.Ready(s[0],s[0],emptyList()))
        assertEquals(null,MetadataBatchSequentialPolicy.initial(r,s))
    }
    @Test fun explicitPrivateDiscardAllowsNextSequentialRow() {
        val(s,r,rows)=setup()
        val id="12345678-1234-4123-8123-123456789abc"
        val prepared=(MetadataBatchSequentialPolicy.record(rows,0,id)
            as MetadataBatchSequentialPolicy.Transition.Updated).rows
        val discarded=MetadataBatchSequentialPolicy.recordPrivateDiscard(prepared,0,id,true)
            as MetadataBatchSequentialPolicy.Transition.Updated
        assertEquals(MetadataBatchRunPolicy.State.PRIVATE_STAGE_DISCARDED,discarded.rows[0].state)
        assertTrue(MetadataBatchSequentialPolicy.next(s,r,discarded.rows,1,true,true)
            is MetadataBatchSequentialPolicy.Verdict.Eligible)
        val summary=MetadataBatchRunPolicy.summarize(s,r,discarded.rows)
            as MetadataBatchRunPolicy.Result.Ready
        assertEquals(1,summary.summary.privateStageDiscarded)
        assertEquals(0,summary.summary.physicallySaved)
    }
    @Test fun privateDiscardRequiresClearJournal() {
        val(s,r,rows)=setup()
        val id="12345678-1234-4123-8123-123456789abc"
        val prepared=(MetadataBatchSequentialPolicy.record(rows,0,id)
            as MetadataBatchSequentialPolicy.Transition.Updated).rows
        assertTrue(MetadataBatchSequentialPolicy.recordPrivateDiscard(prepared,0,id,false)
            is MetadataBatchSequentialPolicy.Transition.Blocked)
    }
    @Test fun privateDiscardCannotBorrowAnotherId() {
        val(s,r,rows)=setup()
        val id="12345678-1234-4123-8123-123456789abc"
        val prepared=(MetadataBatchSequentialPolicy.record(rows,0,id)
            as MetadataBatchSequentialPolicy.Transition.Updated).rows
        assertTrue(MetadataBatchSequentialPolicy.recordPrivateDiscard(prepared,0,
            "12345678-1234-4123-8123-123456789abd",true)
            is MetadataBatchSequentialPolicy.Transition.Blocked)
    }
}
