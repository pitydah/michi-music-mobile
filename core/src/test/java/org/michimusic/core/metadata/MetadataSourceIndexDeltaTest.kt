package org.michimusic.core.metadata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MetadataSourceIndexDeltaTest {
    private val base=MetadataSnapshot(MetadataTarget("","id","content://media/42","token"),true,
        mapOf(MetadataField.TITLE to listOf("A"), MetadataField.GENRE to listOf("Jazz")),
        verifiedFields=setOf(MetadataField.TITLE,MetadataField.GENRE))
    private val review=MetadataEditPreview.review(base,listOf(
        MetadataChange.Replace(MetadataField.TITLE,listOf("B")))) as MetadataReview.Ready
    private val manifest=MetadataCommitIntentManifest.create(
        "12345678-1234-4123-8123-123456789abc",review,
        MetadataDocumentProvider.MEDIASTORE,MetadataAudioContainer.FLAC,
        100,"a".repeat(64),105,"b".repeat(64))
    private fun report(title: String, genre: String)=MetadataFieldReadReport(
        MetadataPhysicalContainer.FLAC,MetadataReadFieldCatalog.PARSER_VERSION,
        mapOf(MetadataField.TITLE to MetadataFieldObservation.Present(listOf(title)),
            MetadataField.GENRE to MetadataFieldObservation.Present(listOf(genre))))
    @Test fun candidateValuesPreparedButNoRoomPermission() {
        val result=MetadataSourceIndexDelta.propose(base,manifest,"b".repeat(64),report("B","Jazz"))
        assertTrue(result is MetadataSourceIndexDelta.Result.PreparedForExternalCas)
        val d=(result as MetadataSourceIndexDelta.Result.PreparedForExternalCas).delta
        assertEquals(listOf("B"),d.observedValues[MetadataField.TITLE])
        assertEquals(false,d.authorizedForRoom)
    }
    @Test fun unmodifiedGenreMustNotDrift() {
        assertEquals(MetadataSourceIndexDelta.Result.Blocked(
            MetadataSourceIndexDelta.Reason.UNCHANGED_FIELD_DRIFT,MetadataField.GENRE),
            MetadataSourceIndexDelta.propose(base,manifest,"b".repeat(64),report("B","Rock")))
    }
    @Test fun wrongSourceDigestRefused() {
        assertEquals(MetadataSourceIndexDelta.Result.Blocked(
            MetadataSourceIndexDelta.Reason.WRONG_SOURCE_HASH),
            MetadataSourceIndexDelta.propose(base,manifest,"a".repeat(64),report("B","Jazz")))
    }
    @Test fun unavailableUnchangedFieldBlocks() {
        val report=report("B","Jazz").copy(observations=mapOf(
            MetadataField.TITLE to MetadataFieldObservation.Present(listOf("B")),
            MetadataField.GENRE to MetadataFieldObservation.Unverifiable(
                MetadataObservationIssue.FIELD_NOT_SUPPORTED)))
        assertEquals(MetadataSourceIndexDelta.Result.Blocked(
            MetadataSourceIndexDelta.Reason.INVALID_RECONCILIATION,MetadataField.GENRE),
            MetadataSourceIndexDelta.propose(base,manifest,"b".repeat(64),report))
    }
}
