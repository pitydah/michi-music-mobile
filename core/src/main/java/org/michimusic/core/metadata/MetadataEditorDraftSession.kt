package org.michimusic.core.metadata

/**
 * ME-10: a user-owned, in-memory draft. Text typed by an IME must be allowed to
 * be temporarily blank/invalid; only [review] produces checked MetadataChange.
 * Neither a draft nor undo/redo is an on-disk tag transaction.
 */
data class MetadataEditorDraftSession private constructor(
    val original: MetadataSnapshot,
    private val timeline: List<Frame>,
    private val cursor: Int,
    /**
     * ME-11U: one logical field edit can span several IME updates. Consecutive
     * [type] calls sharing this key replace the current frame instead of adding
     * one undo step per keystroke. A null key keeps the historical behaviour and
     * is still used by callers that need frame-by-frame history.
     */
    private val coalesce: Coalesce? = null,
) {
    private data class Frame(
        val typed: Map<MetadataField, List<String>> = emptyMap(),
        val cleared: Set<MetadataField> = emptySet(),
    )
    private data class Coalesce(val field: MetadataField, val key: String)
    private val frame: Frame get() = timeline[cursor]
    val canUndo: Boolean get() = cursor > 0
    val canRedo: Boolean get() = cursor < timeline.lastIndex
    val isDirty: Boolean get() = frame.typed.isNotEmpty() || frame.cleared.isNotEmpty()
    val editedFields: Set<MetadataField> get() = frame.typed.keys + frame.cleared
    val historyDepth: Int get() = timeline.size

    fun isCleared(field: MetadataField): Boolean = field in frame.cleared
    fun canEdit(field: MetadataField): Boolean = original.tagsVerified && field in original.verifiedFields &&
        when (original.fieldObservations[field]) {
            null, is MetadataFieldObservation.Present, MetadataFieldObservation.VerifiedAbsent -> true
            is MetadataFieldObservation.Conflicted, is MetadataFieldObservation.Unverifiable -> false
        }

    fun values(field: MetadataField): List<String> = when {
        field in frame.cleared -> emptyList()
        field in frame.typed -> frame.typed.getValue(field)
        else -> original.known[field].orEmpty()
    }

    fun type(
        field: MetadataField,
        values: List<String>,
        coalesceKey: String? = null,
    ): MetadataEditorDraftSession {
        if (!canEdit(field)) return this
        // An empty/blank text input is NOT the explicit destructive Clear operation.
        val frozen = if (values.isEmpty()) listOf("") else values.toList()
        val originalValues = original.known[field].orEmpty()
        val next = if (frozen == originalValues) Frame(frame.typed - field, frame.cleared - field)
            else Frame(frame.typed + (field to frozen), frame.cleared - field)
        if (next == frame) return this
        val marker = coalesceKey?.let { Coalesce(field, it) }
        if (marker != null && marker == coalesce && cursor == timeline.lastIndex) {
            val replaced = timeline.toMutableList().also { it[cursor] = next }
            return copy(timeline = replaced, coalesce = marker)
        }
        return record(next, marker)
    }

    fun clear(field: MetadataField): MetadataEditorDraftSession {
        if (!canEdit(field)) return this
        return record(Frame(frame.typed - field, frame.cleared + field), null)
    }

    fun keep(field: MetadataField): MetadataEditorDraftSession =
        record(Frame(frame.typed - field, frame.cleared - field), null)

    fun undo(): MetadataEditorDraftSession =
        if (canUndo) copy(cursor = cursor - 1, coalesce = null) else this
    fun redo(): MetadataEditorDraftSession =
        if (canRedo) copy(cursor = cursor + 1, coalesce = null) else this

    /** Local field validation for a11y/error indicators; not a write grant. */
    fun fieldError(field: MetadataField): MetadataBlockReason? {
        val op = operation(field) ?: return null
        return (MetadataEditPreview.review(original, listOf(op)) as? MetadataReview.Blocked)?.reason
    }

    val review: MetadataReview get() {
        if (editedFields.any { !canEdit(it) })
            return MetadataReview.Blocked(MetadataBlockReason.FIELD_NOT_VERIFIED)
        return MetadataEditPreview.review(original, editedFields.sortedBy { it.ordinal }.mapNotNull(::operation))
    }

    private fun operation(field: MetadataField): MetadataChange? = when {
        field in frame.cleared -> MetadataChange.Clear(field)
        field in frame.typed -> MetadataChange.Replace(field, frame.typed.getValue(field))
        else -> null
    }

    private fun record(next: Frame, marker: Coalesce?): MetadataEditorDraftSession {
        if (next == frame) return this
        val trimmed = (timeline.take(cursor + 1) + next).takeLast(MAX_HISTORY)
        return copy(timeline = trimmed, cursor = trimmed.lastIndex, coalesce = marker)
    }

    companion object {
        private const val MAX_HISTORY = 32
        fun start(snapshot: MetadataSnapshot): MetadataEditorDraftSession =
            MetadataEditorDraftSession(snapshot, listOf(Frame()), 0)
    }
}
