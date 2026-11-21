package org.michimusic.core.metadata

/**
 * ME-03. All-or-blocked PREPARATION of an ordered batch. This plan is deliberately
 * incapable of committing anything. A transactional Android host must obtain fresh
 * proof, separate approval, space budget, locks, backup journal and permissions.
 */
object MetadataWriteBatchPlan {
    enum class BlockReason {
        EMPTY_SELECTION, TOO_MANY, DUPLICATE_TARGET, MIXED_OWNERS, REVIEW_NOT_READY,
        FORMAT_NOT_PROVEN, UNSUPPORTED_FIELD, INSUFFICIENT_SESSION_PROOF,
    }

    data class Candidate(
        val index: Int,
        val review: MetadataReview.Ready,
        val container: MetadataAudioContainer,
        val provider: MetadataDocumentProvider,
        val changedFields: Set<MetadataField>,
    )

    sealed interface Outcome {
        data class Prepared(val candidates: List<Candidate>, val unchanged: Int) : Outcome {
            val total: Int get() = candidates.size + unchanged
            val canWrite: Boolean = false
        }
        data class Blocked(val index: Int?, val reason: BlockReason,
                           val detail: MetadataCapabilityReason? = null) : Outcome
    }

    /** Must receive entries in the exact current user selection order. */
    fun prepare(
        snapshots: List<MetadataSnapshot>,
        reviews: List<MetadataReview>,
        containers: List<MetadataAudioContainer>,
        providers: List<MetadataDocumentProvider>,
        sniffed: List<Boolean>,
    ): Outcome {
        if (snapshots.isEmpty()) return Outcome.Blocked(null, BlockReason.EMPTY_SELECTION)
        if (snapshots.size > 128) return Outcome.Blocked(null, BlockReason.TOO_MANY)
        if (reviews.size != snapshots.size || containers.size != snapshots.size ||
            providers.size != snapshots.size || sniffed.size != snapshots.size)
            return Outcome.Blocked(null, BlockReason.INSUFFICIENT_SESSION_PROOF)
        if (snapshots.map { it.target.sourceId }.distinct().size != 1)
            return Outcome.Blocked(null, BlockReason.MIXED_OWNERS)
        val identities = snapshots.map { it.target.sourceId to (it.target.trackId to it.target.uri) }
        if (identities.distinct().size != snapshots.size ||
            snapshots.map { it.target.uri }.distinct().size != snapshots.size)
            return Outcome.Blocked(null, BlockReason.DUPLICATE_TARGET)
        val prepared = mutableListOf<Candidate>()
        var unchanged = 0
        reviews.forEachIndexed { index, review ->
            val snapshot = snapshots[index]
            if (!snapshot.tagsVerified || snapshot.target.evidenceToken.isBlank())
                return Outcome.Blocked(index, BlockReason.INSUFFICIENT_SESSION_PROOF)
            if (review == MetadataReview.Unchanged) { unchanged++; return@forEachIndexed }
            if (review !is MetadataReview.Ready)
                return Outcome.Blocked(index, BlockReason.REVIEW_NOT_READY)
            val original = review.original
            if (original != snapshot || original.target != review.proposed.target ||
                review.diffs.any { it.field !in original.verifiedFields })
                return Outcome.Blocked(index, BlockReason.INSUFFICIENT_SESSION_PROOF)
            val check = MetadataCapabilityRegistry.reviewStagingCandidate(
                review, containers[index], providers[index], sniffed[index])
            if (check is MetadataCapabilityRegistry.StageReview.Blocked)
                return Outcome.Blocked(index,
                    if (MetadataCapabilityReason.EXPLICIT_FORMAT_SNIFF_REQUIRED in check.reasons)
                        BlockReason.FORMAT_NOT_PROVEN else BlockReason.UNSUPPORTED_FIELD,
                    check.reasons.firstOrNull())
            prepared += Candidate(index, review, containers[index], providers[index],
                review.diffs.mapTo(linkedSetOf()) { it.field })
        }
        if (prepared.isEmpty()) return Outcome.Prepared(emptyList(), unchanged)
        return Outcome.Prepared(prepared, unchanged)
    }
}
