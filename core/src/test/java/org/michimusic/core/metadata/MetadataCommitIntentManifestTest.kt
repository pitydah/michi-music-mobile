package org.michimusic.core.metadata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MetadataCommitIntentManifestTest {
    private val tx = "aaaaaaaa-1234-4abc-8abc-123456789abc"
    private val originalHash = "a".repeat(64)
    private val candidateHash = "b".repeat(64)
    private val owner = MetadataTarget("", "song-1", "content://media/external/audio/media/4", "evidence-1")

    private fun ready(): MetadataReview.Ready {
        val original = MetadataSnapshot(owner, true, mapOf(
            MetadataField.GENRE to listOf("Jazz; Fusion", "Jazz")),
            verifiedFields=setOf(MetadataField.GENRE))
        return MetadataEditPreview.review(original, listOf(MetadataChange.Replace(
            MetadataField.GENRE, listOf("Jazz; Fusion", "Rock")))) as MetadataReview.Ready
    }

    private fun manifest() = MetadataCommitIntentManifest.create(tx, ready(),
        MetadataDocumentProvider.MEDIASTORE, MetadataAudioContainer.FLAC,
        1024, originalHash, 2048, candidateHash)

    private fun checked(r: MetadataCommitIntentManifest.Receipt, target: MetadataTarget=owner,
        provider: MetadataDocumentProvider=MetadataDocumentProvider.MEDIASTORE,
        container: MetadataAudioContainer=MetadataAudioContainer.FLAC,
        candidateSha: String=candidateHash) = MetadataCommitIntentManifest.verify(
        r, tx, target, provider, container, 1024, originalHash, 2048, candidateSha)

    @Test fun bindsExactReadyReviewAndCandidate() {
        val r = manifest()
        assertTrue(MetadataCommitIntentManifest.valid(r))
        assertEquals(MetadataCommitIntentManifest.Verdict.BoundForExternalReview, checked(r))
        assertFalse(r.sourceWriteCertified)
    }
    @Test fun rejectsChangedOwnerEvidenceToken() = assertEquals(
        MetadataCommitIntentManifest.Verdict.Refused(MetadataCommitIntentManifest.Refusal.OWNER_MISMATCH),
        checked(manifest(), owner.copy(evidenceToken="newer")))
    @Test fun rejectsAnotherTrackWithSameUri() = assertEquals(
        MetadataCommitIntentManifest.Verdict.Refused(MetadataCommitIntentManifest.Refusal.OWNER_MISMATCH),
        checked(manifest(), owner.copy(trackId="song-2")))
    @Test fun rejectsProviderSubstitution() = assertEquals(
        MetadataCommitIntentManifest.Verdict.Refused(MetadataCommitIntentManifest.Refusal.PROVIDER_MISMATCH),
        checked(manifest(), provider=MetadataDocumentProvider.SAF))
    @Test fun rejectsContainerSubstitution() = assertEquals(
        MetadataCommitIntentManifest.Verdict.Refused(MetadataCommitIntentManifest.Refusal.CONTAINER_MISMATCH),
        checked(manifest(), container=MetadataAudioContainer.ID3_V24))
    @Test fun rejectsCandidateDigestMismatch() = assertEquals(
        MetadataCommitIntentManifest.Verdict.Refused(MetadataCommitIntentManifest.Refusal.BYTE_EVIDENCE_MISMATCH),
        checked(manifest(), candidateSha="c".repeat(64)))
    @Test fun distinguishesLiteralSeparatorFromSeparateTags() = assertFalse(
        MetadataCommitIntentManifest.valuesDigest(listOf("Jazz; Fusion")) ==
            MetadataCommitIntentManifest.valuesDigest(listOf("Jazz", "Fusion")))
    @Test fun preservesDuplicatePositionsInDigest() = assertFalse(
        MetadataCommitIntentManifest.valuesDigest(listOf("Jazz", "Rock", "Jazz")) ==
            MetadataCommitIntentManifest.valuesDigest(listOf("Jazz", "Jazz", "Rock")))
    @Test fun rejectsFieldTampering() {
        val changed = manifest().copy(changes=listOf(manifest().changes.single().copy(
            afterSha256="0".repeat(64), beforeSha256="0".repeat(64))))
        // Structurally coherent but altered values are not a cryptographic
        // authorization; store envelope protects only accidental corruption.
        assertFalse(changed.sourceWriteCertified)
    }
    @Test fun rejectsDuplicatesOfSameField() {
        val receipt = manifest()
        assertFalse(MetadataCommitIntentManifest.valid(receipt.copy(
            changes=listOf(receipt.changes.single(),receipt.changes.single()))))
    }
    @Test fun rejectsUnstagedContainer() = assertFalse(MetadataCommitIntentManifest.valid(
        manifest().copy(container=MetadataAudioContainer.MP4_M4A)))
    @Test fun rejectsUnsupportedFieldForId3() {
        val source = manifest()
        val flacOnly = source.changes.single().copy(field=MetadataField.ORCHESTRA)
        assertFalse(MetadataCommitIntentManifest.valid(source.copy(
            container=MetadataAudioContainer.ID3_V24, changes=listOf(flacOnly))))
    }
    @Test fun id3v24CanDescribeItsPhysicallyRepresentableOrderedGenreList() {
        val receipt=MetadataCommitIntentManifest.create(tx,ready(),
            MetadataDocumentProvider.MEDIASTORE,MetadataAudioContainer.ID3_V24,
            1024,originalHash,2048,candidateHash)
        assertTrue(MetadataCommitIntentManifest.valid(receipt))
        assertFalse(receipt.sourceWriteCertified)
    }
    @Test fun apeV2ManifestRemainsNonCertifiedEvenWhenStructurallyValid() {
        val receipt=MetadataCommitIntentManifest.create(tx,ready(),
            MetadataDocumentProvider.SAF,MetadataAudioContainer.WAVPACK,
            1024,originalHash,2048,candidateHash)
        assertTrue(MetadataCommitIntentManifest.valid(receipt))
        assertFalse(receipt.sourceWriteCertified)
    }
    @Test fun rejectsNonNormalisedFieldOrdering() {
        val source = manifest()
        val second = source.changes.single().copy(field=MetadataField.TITLE)
        assertFalse(MetadataCommitIntentManifest.valid(source.copy(changes=listOf(
            second, source.changes.single()))))
    }
    @Test fun refusesNoChangeEvidence() {
        val r=manifest()
        assertFalse(MetadataCommitIntentManifest.valid(r.copy(
            originalBytes=2048,originalSha256=candidateHash)))
    }
}
