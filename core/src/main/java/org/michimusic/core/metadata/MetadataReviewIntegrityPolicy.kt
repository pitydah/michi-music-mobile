package org.michimusic.core.metadata

/**
 * A serialized or UI-built Ready object is not an authorization. Reconstruct
 * the exact candidate from the original physical field evidence. This rejects
 * invented diffs, changed target/owner, silently altered opaque tags, duplicate
 * field operations and stale proposed values before any private binary stage.
 */
object MetadataReviewIntegrityPolicy {
    enum class Reason { NOT_READY, INVALID_DIFF, REPLAY_DID_NOT_MATCH,
        TARGET_CHANGED, OPAQUE_TAGS_CHANGED, NOT_VERIFIED }
    sealed interface Verdict {
        data object Exact : Verdict
        data class Rejected(val reason: Reason) : Verdict
    }

    fun inspect(review: MetadataReview): Verdict {
        val ready = review as? MetadataReview.Ready
            ?: return Verdict.Rejected(Reason.NOT_READY)
        if (!ready.original.tagsVerified || ready.diffs.isEmpty())
            return Verdict.Rejected(Reason.NOT_VERIFIED)
        if (ready.original.target != ready.proposed.target)
            return Verdict.Rejected(Reason.TARGET_CHANGED)
        if (ready.original.opaque != ready.proposed.opaque)
            return Verdict.Rejected(Reason.OPAQUE_TAGS_CHANGED)
        if (ready.diffs.map { it.field }.distinct().size != ready.diffs.size ||
            ready.diffs.any { it.field !in ready.original.verifiedFields })
            return Verdict.Rejected(Reason.INVALID_DIFF)
        val operations = ready.diffs.map { diff ->
            if (diff.after.isEmpty()) MetadataChange.Clear(diff.field)
            else MetadataChange.Replace(diff.field, diff.after)
        }
        val replay = MetadataEditPreview.review(ready.original, operations)
        if (replay !is MetadataReview.Ready || replay != ready)
            return Verdict.Rejected(Reason.REPLAY_DID_NOT_MATCH)
        return Verdict.Exact
    }
}
