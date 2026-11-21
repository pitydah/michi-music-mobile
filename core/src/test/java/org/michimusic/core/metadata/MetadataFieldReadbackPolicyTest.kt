package org.michimusic.core.metadata

import org.junit.Assert.assertEquals
import org.junit.Test

class MetadataFieldReadbackPolicyTest {
    private fun review() : MetadataReview.Ready {
        val snap=MetadataSnapshot(MetadataTarget("","track","content://media/audio/1","receipt"),
            true,mapOf(MetadataField.TITLE to listOf("old"),
                MetadataField.GENRE to listOf("Jazz; Fusion")),
            verifiedFields=setOf(MetadataField.TITLE,MetadataField.GENRE))
        return MetadataEditPreview.review(snap,listOf(
            MetadataChange.Replace(MetadataField.TITLE,listOf("new")),
            MetadataChange.Replace(MetadataField.GENRE,listOf("Rock","Blues")))) as MetadataReview.Ready
    }
    private fun report(title: MetadataFieldObservation,
        genre:MetadataFieldObservation):MetadataFieldReadReport=MetadataFieldReadReport(
            MetadataPhysicalContainer.FLAC,1,mapOf(MetadataField.TITLE to title,MetadataField.GENRE to genre))
    @Test fun verifiesAllChangedFields() {
        assertEquals(MetadataFieldReadbackPolicy.Result.VERIFIED,MetadataFieldReadbackPolicy.check(
            review(),report(MetadataFieldObservation.Present(listOf("new")),
                MetadataFieldObservation.Present(listOf("Rock","Blues")))))
    }
    @Test fun exactMultivalueOrderMustMatch() {
        assertEquals(MetadataFieldReadbackPolicy.Result.DIFF_MISMATCH,MetadataFieldReadbackPolicy.check(
            review(),report(MetadataFieldObservation.Present(listOf("new")),
                MetadataFieldObservation.Present(listOf("Blues","Rock")))))
    }
    @Test fun literalSemicolonDoesNotEqualTwoGenres() {
        assertEquals(MetadataFieldReadbackPolicy.Result.DIFF_MISMATCH,MetadataFieldReadbackPolicy.check(
            review(),report(MetadataFieldObservation.Present(listOf("new")),
                MetadataFieldObservation.Present(listOf("Rock; Blues")))))
    }
    @Test fun unverifiedFieldCannotProduceSavedReceipt() {
        assertEquals(MetadataFieldReadbackPolicy.Result.UNVERIFIABLE,MetadataFieldReadbackPolicy.check(
            review(),report(MetadataFieldObservation.Present(listOf("new")),
                MetadataFieldObservation.Unverifiable(MetadataObservationIssue.TRUNCATED))))
    }
    @Test fun duplicateFieldConflictBlocksSuccess() {
        assertEquals(MetadataFieldReadbackPolicy.Result.CONFLICTED,MetadataFieldReadbackPolicy.check(
            review(),report(MetadataFieldObservation.Conflicted(MetadataObservationIssue.DUPLICATE_FIELD),
                MetadataFieldObservation.Present(listOf("Rock","Blues")))))
    }
    @Test fun absentAfterReplacementIsNotTheSameAsNewValue() {
        assertEquals(MetadataFieldReadbackPolicy.Result.DIFF_MISMATCH,MetadataFieldReadbackPolicy.check(
            review(),report(MetadataFieldObservation.VerifiedAbsent,
                MetadataFieldObservation.Present(listOf("Rock","Blues")))))
    }
    @Test fun clearRequiresVerifiedPhysicalAbsence() {
        val original=review().original
        val clear=MetadataEditPreview.review(original,listOf(MetadataChange.Clear(MetadataField.TITLE)))
            as MetadataReview.Ready
        assertEquals(MetadataFieldReadbackPolicy.Result.VERIFIED,MetadataFieldReadbackPolicy.check(
            clear,report(MetadataFieldObservation.VerifiedAbsent,MetadataFieldObservation.VerifiedAbsent)))
    }
    @Test fun missingFieldInReportIsUnverifiableNotAbsent() {
        assertEquals(MetadataFieldReadbackPolicy.Result.UNVERIFIABLE,MetadataFieldReadbackPolicy.check(
            review(),MetadataFieldReadReport(MetadataPhysicalContainer.FLAC,1,
                mapOf(MetadataField.TITLE to MetadataFieldObservation.Present(listOf("new"))))))
    }
}
