package org.michimusic.core.metadata

/**
 * ME-10P: opt-in CSV -> per-track PREVIEW, never an implicit physical write.
 * The user first explicitly selects and cold-verifies the tracks. Rows refer
 * ONLY to the selection's displayed 1-based order. No URI, track ID, token or
 * original tag is required in the CSV. Missing rows mean KEEP, not CLEAR.
 *
 * Format: pista,campo,accion,indice,valor (RFC 4180 quoted CSV).
 * accion=REPLACE has consecutive 1..N indices; accion=CLEAR has index 0 and
 * an empty value; any mixed/duplicate/ambiguous group blocks the whole batch.
 */
object MetadataCsvBulkImport {
    const val HEADER = "pista,campo,accion,indice,valor"
    /** Spreadsheet-safe reversible export: UTF-8 URL-safe Base64, no executable formulas. */
    const val HEADER_B64 = "pista,campo,accion,indice,valor_b64"
    private const val MAX_CHARS = 128 * 1024
    private const val MAX_ROWS = 4096
    enum class Issue {
        INVALID_SELECTION, BAD_HEADER, MALFORMED_CSV, TOO_LARGE, TOO_MANY_ROWS,
        INVALID_TRACK, UNKNOWN_FIELD, FIELD_NOT_VERIFIED, BAD_ACTION,
        INVALID_ENCODING,
        MIXED_ACTIONS, DUPLICATE_POSITION, MISSING_POSITION, INVALID_VALUE,
        TOO_MANY_FIELDS, INVALID_CHANGE,
    }
    sealed interface Result {
        data class Ready(
            val reviews: List<MetadataReview>, val edits: Int, val unchanged: Int,
        ) : Result
        data class Blocked(val reason: Issue, val row: Int? = null,
                           val track: Int? = null) : Result
    }
    private data class Record(val track: Int, val field: MetadataField,
                              val operation: String, val valuePosition: Int,
                              val value: String, val csvRow: Int)
    private fun parseCsv(input: String): List<List<String>>? {
        if (input.length > MAX_CHARS || '\u0000' in input) return null
        val result = mutableListOf<List<String>>()
        val row = mutableListOf<String>()
        val cell = StringBuilder()
        var quoted = false
        var at = 0
        var justClosed = false
        while (at < input.length) {
            val ch = input[at]
            if (quoted) {
                if (ch == '"') {
                    if (at + 1 < input.length && input[at + 1] == '"') {
                        cell.append('"'); at++
                    } else { quoted = false; justClosed = true }
                } else cell.append(ch)
            } else when (ch) {
                '"' -> {
                    if (cell.isNotEmpty() || justClosed) return null
                    quoted = true
                }
                ',' -> { row += cell.toString(); cell.clear(); justClosed = false }
                '\r', '\n' -> {
                    if (ch == '\r' && at + 1 < input.length && input[at + 1] == '\n') at++
                    row += cell.toString(); cell.clear()
                    result += row.toList(); row.clear(); justClosed = false
                    if (result.size > MAX_ROWS + 1) return null
                }
                else -> {
                    if (justClosed) return null
                    cell.append(ch)
                }
            }
            if (cell.length > 65536) return null
            at++
        }
        if (quoted) return null
        if (row.isNotEmpty() || cell.isNotEmpty() || justClosed) {
            row += cell.toString(); result += row.toList()
        }
        return result
    }

    fun review(snapshots: List<MetadataSnapshot>, csv: String): Result {
        if (snapshots.isEmpty() || snapshots.size > 128 ||
            MetadataEditPreview.reviewBatch(snapshots, emptyList())
                is MetadataEditPreview.BatchReview.Blocked)
            return Result.Blocked(Issue.INVALID_SELECTION)
        if (csv.length > MAX_CHARS) return Result.Blocked(Issue.TOO_LARGE)
        val lines = parseCsv(csv) ?: return Result.Blocked(Issue.MALFORMED_CSV)
        val encoded = lines.firstOrNull() == HEADER_B64.split(',')
        if (lines.isEmpty() || (!encoded && lines.first() != HEADER.split(',')))
            return Result.Blocked(Issue.BAD_HEADER)
        if (lines.size - 1 > MAX_ROWS) return Result.Blocked(Issue.TOO_MANY_ROWS)
        val records = mutableListOf<Record>()
        for ((index, cells) in lines.drop(1).withIndex()) {
            if (cells.size != 5) return Result.Blocked(Issue.MALFORMED_CSV, index + 2)
            if (cells.all { it.isEmpty() }) continue
            val track = cells[0].toIntOrNull() ?: return Result.Blocked(Issue.INVALID_TRACK, index + 2)
            if (track !in 1..snapshots.size || cells[0] != track.toString())
                return Result.Blocked(Issue.INVALID_TRACK, index + 2)
            val field = MetadataField.entries.firstOrNull { it.name == cells[1] }
                ?: return Result.Blocked(Issue.UNKNOWN_FIELD, index + 2, track)
            if (field !in snapshots[track - 1].verifiedFields)
                return Result.Blocked(Issue.FIELD_NOT_VERIFIED, index + 2, track)
            if (cells[2] != "REPLACE" && cells[2] != "CLEAR")
                return Result.Blocked(Issue.BAD_ACTION, index + 2, track)
            val position = cells[3].toIntOrNull()
                ?: return Result.Blocked(Issue.MISSING_POSITION, index + 2, track)
            val value = if (!encoded) cells[4] else try {
                if (!cells[4].matches(Regex("[A-Za-z0-9_-]*")))
                    return Result.Blocked(Issue.INVALID_ENCODING,index + 2,track)
                val decoded=java.util.Base64.getUrlDecoder().decode(cells[4])
                if (decoded.size > 65536 * 4)
                    return Result.Blocked(Issue.INVALID_ENCODING,index + 2,track)
                Charsets.UTF_8.newDecoder()
                    .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(decoded)).toString()
            } catch (_: Exception) {
                return Result.Blocked(Issue.INVALID_ENCODING,index + 2,track)
            }
            records += Record(track, field, cells[2], position, value, index + 2)
        }
        val byTrack = records.groupBy { it.track }
        val output = ArrayList<MetadataReview>(snapshots.size)
        var edits = 0
        for ((index, original) in snapshots.withIndex()) {
            val byField = byTrack[index + 1].orEmpty().groupBy { it.field }
            if (byField.size > 20) return Result.Blocked(Issue.TOO_MANY_FIELDS, track = index + 1)
            val operations = mutableListOf<MetadataChange>()
            for ((field, rows) in byField) {
                if (rows.map { it.operation }.distinct().size != 1)
                    return Result.Blocked(Issue.MIXED_ACTIONS, rows.first().csvRow, index + 1)
                if (rows.first().operation == "CLEAR") {
                    if (rows.size != 1 || rows.single().valuePosition != 0 ||
                        rows.single().value.isNotEmpty())
                        return Result.Blocked(Issue.INVALID_VALUE, rows.first().csvRow, index + 1)
                    operations += MetadataChange.Clear(field)
                } else {
                    val order = rows.map { it.valuePosition }
                    if (order.size > 32 || order.distinct().size != order.size)
                        return Result.Blocked(Issue.DUPLICATE_POSITION, rows.first().csvRow, index + 1)
                    if (order.sorted() != (1..order.size).toList())
                        return Result.Blocked(Issue.MISSING_POSITION, rows.first().csvRow, index + 1)
                    val values = rows.sortedBy { it.valuePosition }.map { it.value }
                    if (values.any { it.isBlank() || it.length > field.maxChars || '\u0000' in it })
                        return Result.Blocked(Issue.INVALID_VALUE, rows.first().csvRow, index + 1)
                    if (!field.multiple && values.size > 1)
                        return Result.Blocked(Issue.INVALID_VALUE, rows.first().csvRow, index + 1)
                    operations += MetadataChange.Replace(field, values)
                }
            }
            val reviewed = MetadataEditPreview.review(original, operations)
            if (reviewed is MetadataReview.Blocked)
                return Result.Blocked(Issue.INVALID_CHANGE, track = index + 1)
            if (reviewed is MetadataReview.Ready) edits++
            output += reviewed
        }
        return Result.Ready(output, edits, output.size - edits)
    }
}
