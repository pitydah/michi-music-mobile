package org.michimusic.core.metadata

/**
 * UI-facing, evidence-neutral count of effective field changes. It is derived from
 * [MetadataReview] rather than the number of touched fields, so touching a value and
 * then returning it to the original value is not reported as a modification.
 */
data class FieldReviewSummary(
    val effectiveFields: Int,
    val blocked: Boolean,
    val unchanged: Boolean,
)

fun fieldReviewSummary(draft: MetadataEditorDraftSession): FieldReviewSummary =
    when (val review = draft.review) {
        is MetadataReview.Ready -> FieldReviewSummary(review.diffs.size, false, false)
        is MetadataReview.Blocked -> FieldReviewSummary(0, true, false)
        MetadataReview.Unchanged -> FieldReviewSummary(0, false, true)
    }
