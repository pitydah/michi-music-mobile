package org.michimusic.core.metadata

/**
 * ME-10S: reversible CSV of explicit, verified changes for the EXACT selection order.
 * The export contains no URI, owner token, trackId, filename or provider detail.
 * It is the inverse of MetadataCsvBulkImport, not the informational CSV preview.
 * Values are UTF-8 URL-safe Base64 to avoid spreadsheet formula injection;
 * the editable plaintext importer remains supported separately. No automatic clipboard.
 */
object MetadataCsvBulkExport {
    private const val MAX_CHARS = 128 * 1024
    private const val MAX_ROWS = 4096
    enum class Reason {
        EMPTY_SELECTION, INVALID_SELECTION, UNVERIFIED_DIFF, TOO_MANY_ROWS, TOO_LARGE,
        UNREPRESENTABLE_DIFF,
    }
    sealed interface Result {
        data class Ready(val csv: String, val changedTracks: Int, val rows: Int) : Result
        data class Blocked(val reason: Reason, val trackPosition: Int? = null) : Result
    }

    fun fromReviews(reviews: List<MetadataReview>,
                    originals: List<MetadataSnapshot>): Result {
        if (reviews.isEmpty()) return Result.Blocked(Reason.EMPTY_SELECTION)
        if (reviews.size > 128 || reviews.size != originals.size ||
            MetadataEditPreview.reviewBatch(originals, emptyList())
                is MetadataEditPreview.BatchReview.Blocked)
            return Result.Blocked(Reason.INVALID_SELECTION)
        val out = StringBuilder(MetadataCsvBulkImport.HEADER_B64).append("\r\n")
        var count = 0
        var changed = 0
        for ((index, item) in reviews.withIndex()) {
            if (item is MetadataReview.Blocked)
                return Result.Blocked(Reason.UNVERIFIED_DIFF, index + 1)
            if (item is MetadataReview.Unchanged) continue
            item as MetadataReview.Ready
            if (item.original != originals[index] ||
                item.proposed.target != originals[index].target || item.diffs.isEmpty())
                return Result.Blocked(Reason.INVALID_SELECTION, index + 1)
            val operations = item.diffs.map { diff ->
                if (diff.after.isEmpty()) MetadataChange.Clear(diff.field)
                else MetadataChange.Replace(diff.field, diff.after)
            }
            if (MetadataEditPreview.review(originals[index], operations) != item)
                return Result.Blocked(Reason.UNVERIFIED_DIFF, index + 1)
            changed++
            if (item.diffs.size > 20)
                return Result.Blocked(Reason.UNREPRESENTABLE_DIFF, index + 1)
            for (diff in item.diffs) {
                if (diff.field !in originals[index].verifiedFields)
                    return Result.Blocked(Reason.UNVERIFIED_DIFF, index + 1)
                if (diff.after.size > 32)
                    return Result.Blocked(Reason.UNREPRESENTABLE_DIFF, index + 1)
                val values = if (diff.after.isEmpty()) listOf(0 to "") else
                    diff.after.mapIndexed { pos, value -> (pos + 1) to value }
                for ((position, value) in values) {
                    out.append(listOf((index + 1).toString(), diff.field.name,
                        if (diff.after.isEmpty()) "CLEAR" else "REPLACE",
                        position.toString(), java.util.Base64.getUrlEncoder().withoutPadding()
                            .encodeToString(value.toByteArray(Charsets.UTF_8))).joinToString(",") {
                            cell(it)
                        }).append("\r\n")
                    count++
                    if (count > MAX_ROWS) return Result.Blocked(Reason.TOO_MANY_ROWS, index + 1)
                    if (out.length > MAX_CHARS) return Result.Blocked(Reason.TOO_LARGE, index + 1)
                }
            }
        }
        return Result.Ready(out.toString(), changed, count)
    }

    /** Base64 data has no formula tokens; standard RFC 4180 quoting preserves cells. */
    private fun cell(value: String): String =
        "\"" + value.replace("\"", "\"\"") + "\""
}
