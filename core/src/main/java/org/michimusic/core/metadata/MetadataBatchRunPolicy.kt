package org.michimusic.core.metadata

/**
 * ME-10U: a lossless, per-track summary for a future SEQUENTIAL metadata batch.
 * The single protected journal V1 is not a parallel batch journal: no entry
 * may be silently skipped, and partial interruption cannot become SUCCESS.
 * Callers own durable per-file receipts; this reducer is intentionally pure.
 */
object MetadataBatchRunPolicy {
    enum class State {
        UNCHANGED, NOT_STARTED, PRIVATE_STAGE_READY, PRIVATE_STAGE_DISCARDED,
        PREPARATION_BLOCKED,
        SOURCE_MUTATION_UNKNOWN, SOURCE_CANDIDATE_OBSERVED_UNCERTIFIED,
        ORIGINAL_OBSERVED_INTACT, RECOVERY_REQUIRED, RESTORED_OBSERVED,
    }
    data class FileResult(
        val index: Int, val target: MetadataTarget, val state: State,
        val transactionId: String? = null,
    )
    data class Summary(
        val total: Int, val unchanged: Int, val waiting: Int, val privatelyPrepared: Int,
        val blocked: Int, val uncertainOrRecovery: Int, val candidateObservedUncertified: Int,
        val restoredOrIntact: Int,
        /** Private review completed after an explicit, verified journal discard. */
        val privateStageDiscarded: Int = 0,
    ) {
        /** An observed candidate is never a certified SAVED track. */
        val physicallySaved: Int get() = 0
        val allSucceeded: Boolean get() = false
    }
    enum class Refusal { INVALID_SELECTION, INVALID_ORDER, WRONG_TARGET, DUPLICATE_TRANSACTION,
        INCONSISTENT_NOOP, MISSING_TRANSACTION, BAD_TRANSACTION }
    sealed interface Result {
        data class Ready(val summary: Summary, val rows: List<FileResult>) : Result
        data class Blocked(val reason: Refusal, val index: Int? = null) : Result
    }
    private val tx=Regex("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}")

    fun summarize(snapshots: List<MetadataSnapshot>, reviews: List<MetadataReview>,
                  rows: List<FileResult>): Result {
        if (snapshots.isEmpty() || snapshots.size > 128 ||
            MetadataEditPreview.reviewBatch(snapshots, emptyList())
                is MetadataEditPreview.BatchReview.Blocked ||
            snapshots.size != reviews.size || rows.size != reviews.size)
            return Result.Blocked(Refusal.INVALID_SELECTION)
        val seen=mutableSetOf<String>()
        rows.forEachIndexed { i, row ->
            if (row.index != i) return Result.Blocked(Refusal.INVALID_ORDER,i)
            if (row.target != snapshots[i].target) return Result.Blocked(Refusal.WRONG_TARGET,i)
            val reviewed=reviews[i]
            if (reviewed is MetadataReview.Blocked ||
                reviewed is MetadataReview.Ready && reviewed.original != snapshots[i])
                return Result.Blocked(Refusal.INVALID_SELECTION,i)
            if (reviewed == MetadataReview.Unchanged && row.state != State.UNCHANGED ||
                reviewed != MetadataReview.Unchanged && row.state == State.UNCHANGED)
                return Result.Blocked(Refusal.INCONSISTENT_NOOP,i)
            val stateNeedsTx=row.state !in setOf(State.UNCHANGED,State.NOT_STARTED,
                State.PREPARATION_BLOCKED)
            if (stateNeedsTx != (row.transactionId != null))
                return Result.Blocked(Refusal.MISSING_TRANSACTION,i)
            if (row.transactionId != null) {
                if (!tx.matches(row.transactionId)) return Result.Blocked(Refusal.BAD_TRANSACTION,i)
                if (!seen.add(row.transactionId)) return Result.Blocked(Refusal.DUPLICATE_TRANSACTION,i)
            }
        }
        val statuses=rows.map { it.state }
        return Result.Ready(Summary(rows.size,
            statuses.count { it == State.UNCHANGED },
            statuses.count { it == State.NOT_STARTED },
            statuses.count { it == State.PRIVATE_STAGE_READY },
            statuses.count { it == State.PREPARATION_BLOCKED },
            statuses.count { it == State.SOURCE_MUTATION_UNKNOWN || it == State.RECOVERY_REQUIRED },
            statuses.count { it == State.SOURCE_CANDIDATE_OBSERVED_UNCERTIFIED },
            statuses.count { it == State.ORIGINAL_OBSERVED_INTACT || it == State.RESTORED_OBSERVED },
            statuses.count { it == State.PRIVATE_STAGE_DISCARDED }),
            rows.toList())
    }
}
