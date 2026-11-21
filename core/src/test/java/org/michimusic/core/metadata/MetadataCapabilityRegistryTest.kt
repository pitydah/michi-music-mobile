package org.michimusic.core.metadata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MetadataCapabilityRegistryTest {
    private fun original(fields: Map<MetadataField, List<String>> = mapOf(
        MetadataField.TITLE to listOf("Old"), MetadataField.GENRE to listOf("Jazz; Fusion"),
    )) = MetadataSnapshot(MetadataTarget("", "id", "content://media/7", "cold-proof"),
        tagsVerified = true, known = fields, verifiedFields = fields.keys + MetadataField.GENRE)

    @Test fun flacCanPrepareTitleButNeverCommit() {
        val report = MetadataCapabilityRegistry.assess(original(), MetadataAudioContainer.FLAC,
            MetadataDocumentProvider.MEDIASTORE, formatSniffed = true)
        assertEquals(MetadataFieldCapability.STAGING_CANDIDATE,
            report.fields.first { it.field == MetadataField.TITLE }.capability)
        assertFalse(report.canCommitToSource)
    }
    @Test fun extensionIsNotEnoughToStage() {
        val report = MetadataCapabilityRegistry.assess(original(), MetadataAudioContainer.FLAC,
            MetadataDocumentProvider.SAF, formatSniffed = false)
        assertEquals(MetadataFieldCapability.PREVIEW_ONLY,
            report.fields.first { it.field == MetadataField.GENRE }.capability)
    }
    @Test fun unsupportedFieldsNeverBecomeStagingCandidate() {
        val snap = original(mapOf(MetadataField.TITLE to listOf("A"),
            MetadataField.SYNCED_LYRICS to listOf("[00:00.00]Hello")))
        val report = MetadataCapabilityRegistry.assess(snap, MetadataAudioContainer.FLAC,
            MetadataDocumentProvider.MEDIASTORE, formatSniffed = true)
        assertEquals(MetadataFieldCapability.PREVIEW_ONLY,
            report.fields.first { it.field == MetadataField.SYNCED_LYRICS }.capability)
    }
    @Test fun mp3IsStagingCandidateOnlyAfterMagicProof() {
        val report = MetadataCapabilityRegistry.assess(original(), MetadataAudioContainer.ID3_V24,
            MetadataDocumentProvider.MEDIASTORE, formatSniffed = true)
        assertEquals(MetadataFieldCapability.STAGING_CANDIDATE,
            report.fields.first { it.field == MetadataField.TITLE }.capability)
        assertFalse(report.canCommitToSource)
    }
    @Test fun unknownAndRemoteDocumentsBlockedFromStaging() {
        val report = MetadataCapabilityRegistry.assess(original(), MetadataAudioContainer.FLAC,
            MetadataDocumentProvider.REMOTE, formatSniffed = true)
        assertTrue(report.fields.none { it.capability == MetadataFieldCapability.STAGING_CANDIDATE })
    }
    @Test fun absentFieldCannotBeInventedAsVerified() {
        val report = MetadataCapabilityRegistry.assess(original(), MetadataAudioContainer.FLAC,
            MetadataDocumentProvider.SAF, formatSniffed = true)
        assertEquals(MetadataFieldCapability.NOT_VERIFIED,
            report.fields.first { it.field == MetadataField.COMPOSER }.capability)
    }
    @Test fun oneReviewWithUnknownFormatFailsClosed() {
        val review = MetadataEditPreview.review(original(), listOf(
            MetadataChange.Replace(MetadataField.TITLE, listOf("New"))))
        assertTrue(MetadataCapabilityRegistry.reviewStagingCandidate(review,
            MetadataAudioContainer.UNKNOWN, MetadataDocumentProvider.MEDIASTORE, true)
            is MetadataCapabilityRegistry.StageReview.Blocked)
    }
    @Test fun genreSeparatorIsPreservedInStageCandidate() {
        val review = MetadataEditPreview.review(original(), listOf(
            MetadataChange.Replace(MetadataField.GENRE, listOf("Jazz; Fusion", "Bebop"))))
        assertTrue(MetadataCapabilityRegistry.reviewStagingCandidate(review,
            MetadataAudioContainer.FLAC, MetadataDocumentProvider.SAF, true)
            is MetadataCapabilityRegistry.StageReview.Candidate)
    }
}
