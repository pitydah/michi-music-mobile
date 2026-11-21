package org.michimusic.core.metadata

/**
 * ME-03: explicit undo/redo for one user-opened editor session. The current draft
 * is always reconstructed from the original snapshot. Nothing is persisted,
 * even on orientation changes, unless the UI supplies its own non-sensitive state.
 */
data class MetadataDraftHistory private constructor(
    val original: MetadataSnapshot,
    private val timeline: List<List<MetadataChange>>,
    private val position: Int,
) {
    val changes: List<MetadataChange> get() = timeline[position]
    val canUndo: Boolean get() = position > 0
    val canRedo: Boolean get() = position + 1 < timeline.size
    val steps: Int get() = timeline.size
    val review: MetadataReview get() = MetadataEditPreview.review(original, changes)

    sealed interface Transition {
        data class Accepted(val history: MetadataDraftHistory) : Transition
        data class Rejected(val reason: MetadataBlockReason) : Transition
    }

    /** Replacement of one field; unrelated drafted fields survive intact. */
    fun change(change: MetadataChange): Transition {
        val proposed = changes.filterNot { it.field == change.field } + change
        return moveTo(proposed)
    }

    fun removeFieldDraft(field: MetadataField): Transition =
        moveTo(changes.filterNot { it.field == field })

    /** Undo/redo do not create history records or persist to disk. */
    fun undo(): MetadataDraftHistory = if (canUndo) copy(position = position - 1) else this
    fun redo(): MetadataDraftHistory = if (canRedo) copy(position = position + 1) else this

    private fun moveTo(proposed: List<MetadataChange>): Transition {
        if (proposed == changes) return Transition.Accepted(this)
        val result = MetadataEditPreview.review(original, proposed)
        if (result is MetadataReview.Blocked) return Transition.Rejected(result.reason)
        val frozen = proposed.map { change ->
            if (change is MetadataChange.Replace) change.copy(values = change.values.toList())
            else change
        }
        val next = (timeline.take(position + 1) + listOf(frozen)).takeLast(MAX_HISTORY)
        return Transition.Accepted(copy(timeline = next, position = next.lastIndex))
    }

    companion object {
        private const val MAX_HISTORY = 24
        fun start(original: MetadataSnapshot): MetadataDraftHistory =
            MetadataDraftHistory(original, listOf(emptyList()), 0)
    }
}
