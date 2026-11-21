package org.michimusic.core.metadata

/** ME-03 bounded CSV *text* proposal for deliberate copying/export by a future UI.
 * No external permission, SAF write, remote upload, file persistence or scanning.
 * The content is private; the UI must not place it on the clipboard by default.
 */
object MetadataCsvPreview {
    enum class Error { NO_SELECTION, TOO_MANY, TOO_MANY_FIELDS, TOO_LARGE }
    sealed interface Result {
        data class Ready(val csv: String, val rows: Int, val columns: Int) : Result
        data class Blocked(val reason: Error) : Result
    }

    fun create(reviews: List<MetadataReview>, fields: List<MetadataField>,
               originalSnapshots: List<MetadataSnapshot>? = null): Result {
        if (reviews.isEmpty()) return Result.Blocked(Error.NO_SELECTION)
        if (reviews.size > 128) return Result.Blocked(Error.TOO_MANY)
        if (fields.size > 20 || fields.distinct().size != fields.size)
            return Result.Blocked(Error.TOO_MANY_FIELDS)
        if (originalSnapshots != null && originalSnapshots.size != reviews.size)
            return Result.Blocked(Error.TOO_MANY)
        val out = StringBuilder()
        out.append((listOf("estado", "origen", "campo_modificado") + fields.map { it.name })
            .joinToString(",") { cell(it) }).append("\r\n")
        for ((index, review) in reviews.withIndex()) {
            val status = when (review) {
                is MetadataReview.Ready -> "PREVIEW"
                is MetadataReview.Blocked -> "BLOCKED"
                MetadataReview.Unchanged -> "UNCHANGED"
            }
            // Deliberately never put content URI, trackId, provider details, or token in CSV.
            val expected = originalSnapshots?.get(index)
            val ready = review as? MetadataReview.Ready
            if (ready != null && expected != null && ready.original != expected)
                return Result.Blocked(Error.TOO_MANY)
            val final = ready?.proposed ?: if (review == MetadataReview.Unchanged) expected else null
            val values = fields.map { field ->
                if (final == null || field !in final.verifiedFields) "[NO VERIFICADO]"
                else final.known[field].orEmpty().joinToString(" | ")
            }
            val row = listOf(status, "DRAFT", (review as? MetadataReview.Ready)
                ?.diffs?.joinToString(" | ") { it.field.name }.orEmpty()) + values
            out.append(row.joinToString(",") { cell(it) }).append("\r\n")
            if (out.length > MAX_CHARS) return Result.Blocked(Error.TOO_LARGE)
        }
        return Result.Ready(out.toString(), reviews.size, fields.size + 3)
    }

    /** Protect CSV opened in spreadsheet software against formulas including after leading whitespace. */
    private fun cell(s: String): String {
        val first = s.trimStart().firstOrNull()
        val safe = if (first == '=' || first == '+' || first == '-' || first == '@' ||
            first == '\t' || first == '\r') "'" + s else s
        return "\"" + safe.replace("\"", "\"\"") + "\""
    }
    private const val MAX_CHARS = 128 * 1024
}
