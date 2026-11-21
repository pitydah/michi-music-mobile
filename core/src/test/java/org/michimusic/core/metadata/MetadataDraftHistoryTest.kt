package org.michimusic.core.metadata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MetadataDraftHistoryTest {
    private val snapshot = MetadataSnapshot(MetadataTarget("", "id", "content://test/id", "proof"),
        tagsVerified = true, known = mapOf(MetadataField.TITLE to listOf("Old"),
            MetadataField.GENRE to listOf("Jazz; Fusion")),
        verifiedFields = setOf(MetadataField.TITLE, MetadataField.GENRE))
    private fun changed(h: MetadataDraftHistory, field: MetadataField, text: String) =
        (h.change(MetadataChange.Replace(field, listOf(text))) as MetadataDraftHistory.Transition.Accepted).history

    @Test fun undoRedoPreservesUnrelatedTags() {
        val h = changed(changed(MetadataDraftHistory.start(snapshot), MetadataField.TITLE, "A"),
            MetadataField.GENRE, "Rock")
        assertEquals(listOf("A"), (h.review as MetadataReview.Ready).proposed.known[MetadataField.TITLE])
        val previous = h.undo()
        assertEquals(listOf("Jazz; Fusion"), (previous.review as MetadataReview.Ready).proposed.known[MetadataField.GENRE])
        assertEquals(h.changes, previous.redo().changes)
    }
    @Test fun newChangeTruncatesRedo() {
        val h = changed(changed(MetadataDraftHistory.start(snapshot), MetadataField.TITLE, "A"),
            MetadataField.TITLE, "B")
        val updated = changed(h.undo(), MetadataField.TITLE, "C")
        assertFalse(updated.canRedo)
        assertEquals(listOf("C"), (updated.review as MetadataReview.Ready).proposed.known[MetadataField.TITLE])
    }
    @Test fun invalidChangeDoesNotEnterHistory() {
        val h = MetadataDraftHistory.start(snapshot)
        assertEquals(MetadataBlockReason.EMPTY_VALUE,
            (h.change(MetadataChange.Replace(MetadataField.TITLE, listOf(" ")))
                as MetadataDraftHistory.Transition.Rejected).reason)
        assertEquals(1, h.steps)
    }
    @Test fun originalIsImmutableAcrossUndo() {
        val h = changed(MetadataDraftHistory.start(snapshot), MetadataField.TITLE, "New")
        assertTrue(h.canUndo)
        assertEquals(listOf("Old"), h.original.known[MetadataField.TITLE])
        assertEquals(MetadataReview.Unchanged, h.undo().review)
    }
}
