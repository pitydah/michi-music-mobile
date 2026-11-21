package org.michimusic.core.metadata

/**
 * ME-10Y: chooses exactly one explicitly requested private preparation step.
 * This is a pure policy, not a writer. The current V1 journal has ONE slot:
 * a stage-ready or uncertain source suspends every later step. A caller must
 * inspect/reconcile that transaction separately, never skip or resume writes.
 */
object MetadataBatchSequentialPolicy {
    enum class Reason {
        ACTION_REQUIRED, INVALID_BATCH, INVALID_ROW, WRONG_SELECTION_ORDER,
        EXISTING_PRIVATE_STAGE, SOURCE_RECOVERY_REQUIRED, NOT_A_EDIT,
        TRANSACTION_COLLISION, ROW_STATE_INVALID,
    }
    sealed interface Verdict {
        data class Eligible(val index: Int, val target: MetadataTarget) : Verdict
        data class Blocked(val reason: Reason, val index: Int? = null) : Verdict
    }
    sealed interface Transition {
        data class Updated(val rows: List<MetadataBatchRunPolicy.FileResult>) : Transition
        data class Blocked(val reason: Reason) : Transition
    }

    fun initial(reviews: List<MetadataReview>, snapshots: List<MetadataSnapshot>):
        List<MetadataBatchRunPolicy.FileResult>? {
        if (reviews.size != snapshots.size || snapshots.isEmpty() || snapshots.size > 128) return null
        if (MetadataEditPreview.reviewBatch(snapshots, emptyList()) is
            MetadataEditPreview.BatchReview.Blocked) return null
        return reviews.mapIndexed { index, review ->
            if (review !is MetadataReview.Ready && review != MetadataReview.Unchanged) return null
            if (review is MetadataReview.Ready) {
                if (review.original != snapshots[index]) return null
                val ops=review.diffs.map { d ->
                    if (d.after.isEmpty()) MetadataChange.Clear(d.field)
                    else MetadataChange.Replace(d.field,d.after)
                }
                if (MetadataEditPreview.review(review.original,ops) != review) return null
            }
            MetadataBatchRunPolicy.FileResult(index, snapshots[index].target,
                if (review == MetadataReview.Unchanged) MetadataBatchRunPolicy.State.UNCHANGED
                else MetadataBatchRunPolicy.State.NOT_STARTED)
        }
    }

    fun next(snapshots: List<MetadataSnapshot>, reviews: List<MetadataReview>,
             rows: List<MetadataBatchRunPolicy.FileResult>, index: Int,
             explicitUserAction: Boolean, journalClear: Boolean): Verdict {
        if (!explicitUserAction) return Verdict.Blocked(Reason.ACTION_REQUIRED)
        if (MetadataBatchRunPolicy.summarize(snapshots, reviews, rows)
            !is MetadataBatchRunPolicy.Result.Ready) return Verdict.Blocked(Reason.INVALID_BATCH)
        if (index !in rows.indices) return Verdict.Blocked(Reason.INVALID_ROW)
        if (rows.any { it.state in setOf(
                MetadataBatchRunPolicy.State.SOURCE_MUTATION_UNKNOWN,
                MetadataBatchRunPolicy.State.SOURCE_CANDIDATE_OBSERVED_UNCERTIFIED,
                MetadataBatchRunPolicy.State.RECOVERY_REQUIRED,
                MetadataBatchRunPolicy.State.RESTORED_OBSERVED) })
            return Verdict.Blocked(Reason.SOURCE_RECOVERY_REQUIRED)
        if (!journalClear || rows.any { it.state == MetadataBatchRunPolicy.State.PRIVATE_STAGE_READY })
            return Verdict.Blocked(Reason.EXISTING_PRIVATE_STAGE)
        val row=rows[index]
        if (row.state != MetadataBatchRunPolicy.State.NOT_STARTED ||
            reviews[index] !is MetadataReview.Ready)
            return Verdict.Blocked(Reason.NOT_A_EDIT,index)
        // First eligible unattempted row in the user-selected order. Previously
        // blocked rows may be passed, but not silently reprocessed.
        val earlier=rows.take(index).indexOfFirst { it.state == MetadataBatchRunPolicy.State.NOT_STARTED }
        if (earlier != -1) return Verdict.Blocked(Reason.WRONG_SELECTION_ORDER,earlier)
        return Verdict.Eligible(index,row.target)
    }

    /** A private stage can be finished only AFTER user-confirmed journal discard. */
    fun recordPrivateDiscard(rows: List<MetadataBatchRunPolicy.FileResult>, index: Int,
                             transactionId: String, journalClear: Boolean): Transition {
        if (!journalClear || index !in rows.indices ||
            rows[index].state != MetadataBatchRunPolicy.State.PRIVATE_STAGE_READY ||
            rows[index].transactionId != transactionId)
            return Transition.Blocked(Reason.ROW_STATE_INVALID)
        val next=rows.toMutableList()
        next[index]=next[index].copy(state=MetadataBatchRunPolicy.State.PRIVATE_STAGE_DISCARDED)
        return Transition.Updated(next.toList())
    }

    fun record(rows: List<MetadataBatchRunPolicy.FileResult>, index: Int,
               preparedTransactionId: String?): Transition {
        if (index !in rows.indices || rows[index].state != MetadataBatchRunPolicy.State.NOT_STARTED)
            return Transition.Blocked(Reason.ROW_STATE_INVALID)
        val id=preparedTransactionId
        if (id != null && rows.any { it.transactionId == id })
            return Transition.Blocked(Reason.TRANSACTION_COLLISION)
        val proposed=rows.toMutableList()
        proposed[index]=proposed[index].copy(
            state=if (id == null) MetadataBatchRunPolicy.State.PREPARATION_BLOCKED
                else MetadataBatchRunPolicy.State.PRIVATE_STAGE_READY,
            transactionId=id)
        return Transition.Updated(proposed.toList())
    }
}
