package org.michimusic.core.metadata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Unit source for expanded Music Metadata catalog, no actual Android IO. */
class MetadataExpandedFieldContractTest {
    private fun snapshot(field: MetadataField, value: String) = MetadataSnapshot(
        MetadataTarget("", "t", "content://fixture/t", "proof"), true,
        mapOf(field to listOf(value)), verifiedFields = setOf(field))

    @Test fun expandedFieldsBelongToExactlyOneSection() {
        assertEquals(66, MetadataField.entries.size)
        val all = MetadataEditorSection.entries.flatMap { it.fields }
        assertEquals(66, all.size)
        assertEquals(MetadataField.entries.toSet(), all.toSet())
    }
    @Test fun classicCreditsAreStructuredAndMultiple() {
        assertTrue(MetadataField.ORCHESTRA.multiple)
        assertTrue(MetadataField.ENSEMBLE.multiple)
        assertTrue(MetadataField.INSTRUMENT.multiple)
        assertFalse(MetadataField.MOVEMENT_NUMBER.multiple)
    }
    @Test fun extendedFlacStageIsOnlyCandidateNotCommit() {
        val s = snapshot(MetadataField.ORCHESTRA, "Orquesta Nacional")
        val report = MetadataCapabilityRegistry.assess(s, MetadataAudioContainer.FLAC,
            MetadataDocumentProvider.SAF, formatSniffed = true, fields = setOf(MetadataField.ORCHESTRA))
        assertEquals(MetadataFieldCapability.STAGING_CANDIDATE, report.fields.single().capability)
        assertFalse(report.canCommitToSource)
    }
    @Test fun id3CannotStageFieldWithoutKnownFrame() {
        val s = snapshot(MetadataField.ORCHESTRA, "Ensemble")
        val report = MetadataCapabilityRegistry.assess(s, MetadataAudioContainer.ID3_V24,
            MetadataDocumentProvider.MEDIASTORE, formatSniffed = true, fields = setOf(MetadataField.ORCHESTRA))
        assertEquals(MetadataFieldCapability.PREVIEW_ONLY, report.fields.single().capability)
    }
    @Test fun expandedReadCatalogDoesNotProveFieldsWithoutParserSupport() {
        assertTrue(MetadataField.RECORDING_DATE in MetadataReadFieldCatalog.primary)
        assertTrue(MetadataField.ORCHESTRA in MetadataReadFieldCatalog.primary)
        assertTrue(MetadataField.ALBUM_SUBTITLE in MetadataReadFieldCatalog.primary)
        assertTrue(MetadataField.MUSICBRAINZ_LABEL_ID in MetadataReadFieldCatalog.primary)
    }
    @Test fun externalSuggestionsCannotElevateUnverifiedField() {
        val current = snapshot(MetadataField.TITLE, "Original")
        assertEquals(MetadataReview.Blocked(MetadataBlockReason.FIELD_NOT_VERIFIED),
            MetadataEditPreview.review(current, listOf(
                MetadataChange.Replace(MetadataField.RECORDING_DATE, listOf("2020")))))
    }
    @Test fun multiOrchestraKeepsLiteralSemicolons() {
        val snap = snapshot(MetadataField.ORCHESTRA, "Orquesta A; Sinfónica")
        val diff = MetadataEditPreview.review(snap, listOf(MetadataChange.Replace(
            MetadataField.ORCHESTRA, listOf("Orquesta A; Sinfónica", "Ensamble B")))) as MetadataReview.Ready
        assertEquals(listOf("Orquesta A; Sinfónica", "Ensamble B"), diff.diffs.single().after)
    }
}
