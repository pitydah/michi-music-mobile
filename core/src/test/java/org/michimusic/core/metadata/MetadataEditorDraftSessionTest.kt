package org.michimusic.core.metadata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MetadataEditorDraftSessionTest {
    private fun original() = MetadataSnapshot(
        MetadataTarget("", "one", "content://test/one", "evidence"), true,
        mapOf(MetadataField.TITLE to listOf("Before"), MetadataField.GENRE to listOf("Jazz; Fusion")),
        verifiedFields = setOf(MetadataField.TITLE, MetadataField.GENRE, MetadataField.ALBUM),
        fieldObservations = mapOf(MetadataField.ALBUM to MetadataFieldObservation.VerifiedAbsent),
    )

    @Test fun emptyImeTextDoesNotMeanClear() {
        val d = MetadataEditorDraftSession.start(original()).type(MetadataField.TITLE, listOf(""))
        assertTrue(d.isDirty)
        assertFalse(d.isCleared(MetadataField.TITLE))
        assertEquals(MetadataBlockReason.EMPTY_VALUE, (d.review as MetadataReview.Blocked).reason)
    }
    @Test fun explicitClearIsTheOnlyDeletionAction() {
        val d = MetadataEditorDraftSession.start(original()).clear(MetadataField.TITLE)
        assertTrue(d.isCleared(MetadataField.TITLE))
        assertEquals(emptyList<String>(), (d.review as MetadataReview.Ready).diffs.single().after)
    }
    @Test fun typedCorrectValueBecomesReadyWithoutMutation() {
        val s = original()
        val d = MetadataEditorDraftSession.start(s).type(MetadataField.TITLE, listOf("After"))
        assertEquals(listOf("After"), (d.review as MetadataReview.Ready).diffs.single().after)
        assertEquals(listOf("Before"), s.known[MetadataField.TITLE])
    }
    @Test fun restoredOriginalBecomesClean() {
        val d = MetadataEditorDraftSession.start(original()).type(MetadataField.TITLE, listOf("Next"))
            .type(MetadataField.TITLE, listOf("Before"))
        assertFalse(d.isDirty)
        assertEquals(MetadataReview.Unchanged, d.review)
    }
    @Test fun clearThenTypeRestoresEditableValue() {
        val d = MetadataEditorDraftSession.start(original()).clear(MetadataField.TITLE)
            .type(MetadataField.TITLE, listOf("Revised"))
        assertFalse(d.isCleared(MetadataField.TITLE))
        assertEquals(listOf("Revised"), (d.review as MetadataReview.Ready).diffs.single().after)
    }
    @Test fun undoRedoAndBranchingStayLocal() {
        val d1 = MetadataEditorDraftSession.start(original()).type(MetadataField.TITLE, listOf("One"))
            .type(MetadataField.TITLE, listOf("Two"))
        assertEquals(listOf("One"), d1.undo().values(MetadataField.TITLE))
        assertEquals(listOf("Two"), d1.undo().redo().values(MetadataField.TITLE))
        assertFalse(d1.undo().type(MetadataField.TITLE, listOf("Fork")).canRedo)
    }
    @Test fun keepOnlyRemovesTheRequestedField() {
        val d = MetadataEditorDraftSession.start(original()).type(MetadataField.TITLE, listOf("New"))
            .type(MetadataField.ALBUM, listOf("Album"))
            .keep(MetadataField.TITLE)
        assertEquals(setOf(MetadataField.ALBUM), d.editedFields)
    }
    @Test fun unverifiedFieldsCannotBeEdited() {
        val d = MetadataEditorDraftSession.start(original())
        assertTrue(d === d.type(MetadataField.MOOD, listOf("Dreamy")))
        assertEquals(MetadataReview.Unchanged, d.review)
    }
    @Test fun conflictBlocksEvenWithVerifiedFlag() {
        val s = original().copy(fieldObservations = mapOf(
            MetadataField.TITLE to MetadataFieldObservation.Conflicted(MetadataObservationIssue.DUPLICATE_FIELD)))
        assertFalse(MetadataEditorDraftSession.start(s).canEdit(MetadataField.TITLE))
    }
    @Test fun independentSemicolonStaysOneGenreValue() {
        val d = MetadataEditorDraftSession.start(original()).type(MetadataField.GENRE, listOf("Jazz; Fusion", "Rock"))
        assertEquals(listOf("Jazz; Fusion", "Rock"), (d.review as MetadataReview.Ready).diffs.single().after)
    }
    @Test fun placeholderBlankCannotDestroyAbsentValue() {
        val d = MetadataEditorDraftSession.start(original()).type(MetadataField.ALBUM, listOf(""))
        assertEquals(MetadataBlockReason.EMPTY_VALUE, d.fieldError(MetadataField.ALBUM))
    }
    @Test fun clearUnverifiedFieldIsNoop() {
        val d = MetadataEditorDraftSession.start(original()).clear(MetadataField.WORK)
        assertFalse(d.isDirty)
    }
    @Test fun historyIsBoundedTo32Frames() {
        var d = MetadataEditorDraftSession.start(original())
        repeat(150) { d = d.type(MetadataField.TITLE, listOf("Change $it")) }
        assertTrue(d.historyDepth <= 32)
    }
    @Test fun sessionIsBoundToSourceEvidence() {
        val a = MetadataEditorDraftSession.start(original()).type(MetadataField.TITLE, listOf("Changed"))
        val b = MetadataEditorDraftSession.start(original().copy(
            target = MetadataTarget("", "other", "content://test/other", "new-evidence")))
        assertTrue(a.isDirty)
        assertFalse(b.isDirty)
    }
}
