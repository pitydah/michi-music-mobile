package org.michimusic.core.metadata

import java.util.Locale

/** ME-01: original Kotlin workbench; all operations are preview-only, never I/O. */
sealed interface MetadataBatchAction {
    data class Set(val change: MetadataChange) : MetadataBatchAction
    data class Copy(
        val source: MetadataField, val destination: MetadataField,
        val onlyIfMissing: Boolean = true,
    ) : MetadataBatchAction
    data class Append(val field: MetadataField, val values: List<String>) : MetadataBatchAction
    /** Remove exactly one indexed occurrence, never all matching literal values. */
    data class RemoveExact(
        val field: MetadataField, val index: Int, val expectedValue: String,
    ) : MetadataBatchAction
    data class FindReplace(
        val field: MetadataField, val find: String, val replacement: String,
        val ignoreCase: Boolean = false,
    ) : MetadataBatchAction
    data class Trim(val field: MetadataField) : MetadataBatchAction
    data class ChangeCase(val field: MetadataField, val mode: CaseMode) : MetadataBatchAction
    /** Index in the explicit selection order; no automatic album or filename sorting. */
    data class TrackSequence(val first: Int = 1, val padding: Int = 2) : MetadataBatchAction
    /** Filename must be a basename provided explicitly from the selected document. */
    data class ImportFilename(val template: String) : MetadataBatchAction
}

enum class CaseMode { UPPERCASE, LOWERCASE }

data class MetadataBatchEntry(val snapshot: MetadataSnapshot, val fileName: String? = null)

enum class MetadataBatchError {
    INVALID_RECIPE, INVALID_FIND, INVALID_REMOVAL, INVALID_NUMBERING, MISSING_FILENAME,
    FILENAME_TEMPLATE_REJECTED, VALIDATION_REJECTED, SELECTION_REJECTED,
}

sealed interface MetadataBatchResult {
    data class Blocked(
        val reason: MetadataBatchError,
        val index: Int? = null,
        val validation: MetadataBlockReason? = null,
        val filenameReason: MetadataFilenameToolkit.Reason? = null,
    ) : MetadataBatchResult

    /** Every entry, including unchanged files, appears in the same selection order. */
    data class Ready(val entries: List<MetadataReview>) : MetadataBatchResult {
        val modified: Int get() = entries.count { it is MetadataReview.Ready }
        val unchanged: Int get() = entries.count { it is MetadataReview.Unchanged }
    }
}

object MetadataBatchToolkit {
    private const val MAX_STEPS = 32

    /**
     * Re-evaluates from original evidence each time. This does not expose a write grant,
     * mutate a track, apply changes to Room, infer missing metadata, or silently skip tracks.
     */
    fun review(entries: List<MetadataBatchEntry>, actions: List<MetadataBatchAction>): MetadataBatchResult {
        val gate = MetadataEditPreview.reviewBatch(entries.map { it.snapshot }, emptyList())
        if (gate is MetadataEditPreview.BatchReview.Blocked)
            return MetadataBatchResult.Blocked(MetadataBatchError.SELECTION_REJECTED, validation = gate.reason)
        if (actions.size > MAX_STEPS)
            return MetadataBatchResult.Blocked(MetadataBatchError.INVALID_RECIPE)
        val previews = mutableListOf<MetadataReview>()
        for ((index, entry) in entries.withIndex()) {
            val original = entry.snapshot
            var current = original
            for (action in actions) {
                // A no-op is not proof of safety: absent/unparsed fields must not be
                // silently treated as blank, especially for Copy(onlyIfMissing).
                val requiredFields: Set<MetadataField> = when (action) {
                    is MetadataBatchAction.Set -> setOf(action.change.field)
                    is MetadataBatchAction.Copy -> setOf(action.source, action.destination)
                    is MetadataBatchAction.Append -> setOf(action.field)
                    is MetadataBatchAction.RemoveExact -> setOf(action.field)
                    is MetadataBatchAction.FindReplace -> setOf(action.field)
                    is MetadataBatchAction.Trim -> setOf(action.field)
                    is MetadataBatchAction.ChangeCase -> setOf(action.field)
                    is MetadataBatchAction.TrackSequence -> setOf(MetadataField.TRACK_NUMBER)
                    is MetadataBatchAction.ImportFilename -> emptySet() // inspected after capture
                }
                if (!current.verifiedFields.containsAll(requiredFields))
                    return MetadataBatchResult.Blocked(MetadataBatchError.VALIDATION_REJECTED,
                        index, MetadataBlockReason.FIELD_NOT_VERIFIED)
                val changes: List<MetadataChange> = when (action) {
                    is MetadataBatchAction.Set -> listOf(action.change)
                    is MetadataBatchAction.Copy -> {
                        val currentValues = current.known[action.source].orEmpty()
                        if (currentValues.isEmpty() || (action.onlyIfMissing &&
                            current.known[action.destination].orEmpty().isNotEmpty())) emptyList() else
                            listOf(MetadataChange.Replace(action.destination, currentValues.toList()))
                    }
                    is MetadataBatchAction.Append -> {
                        if (!action.field.multiple || action.values.isEmpty())
                            return MetadataBatchResult.Blocked(MetadataBatchError.INVALID_RECIPE, index)
                        listOf(MetadataChange.Replace(action.field,
                            current.known[action.field].orEmpty() + action.values.toList()))
                    }
                    is MetadataBatchAction.RemoveExact -> {
                        val before = current.known[action.field].orEmpty()
                        // Values and positions are explicit. A mixed or reordered album
                        // may not silently delete a different occurrence on another track.
                        if (!action.field.multiple || action.index !in before.indices ||
                            before[action.index] != action.expectedValue ||
                            action.expectedValue.isBlank())
                            return MetadataBatchResult.Blocked(MetadataBatchError.INVALID_REMOVAL, index)
                        val after = before.filterIndexed { i, _ -> i != action.index }
                        listOf(if (after.isEmpty()) MetadataChange.Clear(action.field)
                            else MetadataChange.Replace(action.field, after))
                    }
                    is MetadataBatchAction.FindReplace -> {
                        if (action.find.isEmpty() || action.find.length > 2048)
                            return MetadataBatchResult.Blocked(MetadataBatchError.INVALID_FIND, index)
                        val previous = current.known[action.field].orEmpty()
                        if (previous.isEmpty()) emptyList() else listOf(MetadataChange.Replace(
                            action.field, previous.map { it.replace(action.find, action.replacement, action.ignoreCase) }))
                    }
                    is MetadataBatchAction.Trim -> {
                        val previous = current.known[action.field].orEmpty()
                        if (previous.isEmpty()) emptyList() else
                            listOf(MetadataChange.Replace(action.field, previous.map { it.trim() }))
                    }
                    is MetadataBatchAction.ChangeCase -> {
                        val previous = current.known[action.field].orEmpty()
                        if (previous.isEmpty()) emptyList() else listOf(MetadataChange.Replace(
                            action.field, previous.map {
                                when (action.mode) {
                                    CaseMode.UPPERCASE -> it.uppercase(Locale.ROOT)
                                    CaseMode.LOWERCASE -> it.lowercase(Locale.ROOT)
                                }
                            }))
                    }
                    is MetadataBatchAction.TrackSequence -> {
                        if (action.first !in 1..9999 || action.padding !in 1..4 ||
                            action.first.toLong() + entries.size - 1 > 9999L)
                            return MetadataBatchResult.Blocked(MetadataBatchError.INVALID_NUMBERING, index)
                        listOf(MetadataChange.Replace(
                            MetadataField.TRACK_NUMBER,
                            listOf((action.first + index).toString().padStart(action.padding, '0'))))
                    }
                    is MetadataBatchAction.ImportFilename -> {
                        val name = entry.fileName ?: return MetadataBatchResult.Blocked(
                            MetadataBatchError.MISSING_FILENAME, index)
                        when (val extracted = MetadataFilenameToolkit.tagsFromFilename(name, action.template)) {
                            is MetadataFilenameToolkit.Result.Rejected -> return MetadataBatchResult.Blocked(
                                MetadataBatchError.FILENAME_TEMPLATE_REJECTED, index,
                                filenameReason = extracted.reason)
                            is MetadataFilenameToolkit.Result.Success -> extracted.value
                        }
                    }
                }
                if (changes.isEmpty()) continue
                when (val intermediate = MetadataEditPreview.review(current, changes)) {
                    is MetadataReview.Blocked -> return MetadataBatchResult.Blocked(
                        MetadataBatchError.VALIDATION_REJECTED, index, intermediate.reason)
                    is MetadataReview.Ready -> current = intermediate.proposed
                    MetadataReview.Unchanged -> Unit
                }
            }
            val diffChanges = (original.known.keys + current.known.keys).distinct().mapNotNull { field ->
                val before = original.known[field].orEmpty()
                val after = current.known[field].orEmpty()
                if (before == after) null
                else if (after.isEmpty()) MetadataChange.Clear(field)
                else MetadataChange.Replace(field, after)
            }
            when (val final = MetadataEditPreview.review(original, diffChanges)) {
                is MetadataReview.Blocked -> return MetadataBatchResult.Blocked(
                    MetadataBatchError.VALIDATION_REJECTED, index, final.reason)
                else -> previews += final
            }
        }
        return MetadataBatchResult.Ready(previews.toList())
    }
}
