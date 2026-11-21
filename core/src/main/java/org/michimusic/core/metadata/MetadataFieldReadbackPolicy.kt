package org.michimusic.core.metadata

/**
 * ME-06. Stage bytes matching source bytes is necessary, not sufficient: a
 * candidate also needs per-field physical observation of all changed fields.
 * This policy never promotes unknown frames, aliases or partial decodes to
 * verified absences. All readback reports are obtained on an explicit gesture.
 */
object MetadataFieldReadbackPolicy {
    enum class Result { VERIFIED, UNVERIFIABLE, CONFLICTED, DIFF_MISMATCH }
    fun check(review: MetadataReview.Ready, report: MetadataFieldReadReport): Result {
        if (review.diffs.isEmpty()) return Result.DIFF_MISMATCH
        for (diff in review.diffs) {
            val seen=report.observation(diff.field)
            when(seen) {
                is MetadataFieldObservation.Unverifiable -> return Result.UNVERIFIABLE
                is MetadataFieldObservation.Conflicted -> return Result.CONFLICTED
                MetadataFieldObservation.VerifiedAbsent -> if (diff.after.isNotEmpty())
                    return Result.DIFF_MISMATCH
                is MetadataFieldObservation.Present -> if (seen.values != diff.after)
                    return Result.DIFF_MISMATCH
            }
        }
        return Result.VERIFIED
    }
}
