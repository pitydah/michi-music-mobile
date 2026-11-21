package org.michimusic.core.metadata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MetadataPostSourceFieldPolicyTest {
    private fun fixture(): MetadataCommitIntentManifest.Receipt {
        val original = MetadataSnapshot(
            MetadataTarget("", "1", "content://media/1", "cold-proof"), true,
            mapOf(MetadataField.TITLE to listOf("Old")),
            verifiedFields=setOf(MetadataField.TITLE))
        val review=MetadataEditPreview.review(original,listOf(
            MetadataChange.Replace(MetadataField.TITLE,listOf("New")))) as MetadataReview.Ready
        return MetadataCommitIntentManifest.create(
            "01234567-89ab-4cde-8f00-123456789abc",review,
            MetadataDocumentProvider.MEDIASTORE,MetadataAudioContainer.ID3_V23,
            100,"a".repeat(64),105,"b".repeat(64))
    }
    private fun observed(value: MetadataFieldObservation) = MetadataFieldReadReport(
        MetadataPhysicalContainer.ID3_V23,MetadataReadFieldCatalog.PARSER_VERSION,
        mapOf(MetadataField.TITLE to value))
    @Test fun validReadbackIsObservedButNotCommit() {
        val verdict=MetadataPostSourceFieldPolicy.compare(fixture(),
            observed(MetadataFieldObservation.Present(listOf("New"))))
        assertTrue(verdict is MetadataPostSourceFieldPolicy.Verdict.Compared)
        assertEquals(false,(verdict as MetadataPostSourceFieldPolicy.Verdict.Compared).indexPublicationAllowed)
    }
    @Test fun wrongTitleRefuses() {
        assertEquals(MetadataPostSourceFieldPolicy.Verdict.Blocked(
            MetadataPostSourceFieldPolicy.Refusal.CHANGED_FIELD_MISMATCH,MetadataField.TITLE),
            MetadataPostSourceFieldPolicy.compare(fixture(),
                observed(MetadataFieldObservation.Present(listOf("Old")))))
    }
    @Test fun missingEvidenceIsNeverAbsent() {
        assertEquals(MetadataPostSourceFieldPolicy.Verdict.Blocked(
            MetadataPostSourceFieldPolicy.Refusal.CHANGED_FIELD_UNVERIFIABLE,MetadataField.TITLE),
            MetadataPostSourceFieldPolicy.compare(fixture(),
                observed(MetadataFieldObservation.Unverifiable(
                    MetadataObservationIssue.PROVIDER_UNSEEKABLE))))
    }
    @Test fun provenAbsenceDoesNotMatchProposedValue() {
        assertTrue(MetadataPostSourceFieldPolicy.compare(fixture(),
            observed(MetadataFieldObservation.VerifiedAbsent)) is MetadataPostSourceFieldPolicy.Verdict.Blocked)
    }
    @Test fun wrongContainerCannotMasqueradeAsId3() {
        val f=observed(MetadataFieldObservation.Present(listOf("New"))).copy(
            container=MetadataPhysicalContainer.FLAC)
        assertEquals(MetadataPostSourceFieldPolicy.Verdict.Blocked(
            MetadataPostSourceFieldPolicy.Refusal.CONTAINER_MISMATCH),
            MetadataPostSourceFieldPolicy.compare(fixture(),f))
    }
    @Test fun wrongParserVersionRejected() {
        val f=observed(MetadataFieldObservation.Present(listOf("New"))).copy(parserVersion=1)
        assertEquals(MetadataPostSourceFieldPolicy.Verdict.Blocked(
            MetadataPostSourceFieldPolicy.Refusal.PARSER_UNTRUSTED),
            MetadataPostSourceFieldPolicy.compare(fixture(),f))
    }
}
