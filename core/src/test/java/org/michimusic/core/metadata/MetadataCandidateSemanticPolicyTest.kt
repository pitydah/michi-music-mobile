package org.michimusic.core.metadata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MetadataCandidateSemanticPolicyTest {
    private val target = MetadataTarget("", "track-1", "content://media/external/audio/media/1", "token")
    private val old = MetadataSnapshot(target, true,
        mapOf(MetadataField.TITLE to listOf("Old title"),
            MetadataField.GENRE to listOf("Jazz; Fusion", "Jazz")),
        verifiedFields = setOf(MetadataField.TITLE, MetadataField.GENRE))
    private val review = MetadataEditPreview.review(old, listOf(
        MetadataChange.Replace(MetadataField.TITLE, listOf("New title")))) as MetadataReview.Ready
    private val receipt = MetadataCommitIntentManifest.create(
        "01234567-1234-4abc-8abc-123456789abc", review,
        MetadataDocumentProvider.MEDIASTORE, MetadataAudioContainer.FLAC,
        1024L, "a".repeat(64), 1032L, "b".repeat(64))

    private fun report(
        title: MetadataFieldObservation = MetadataFieldObservation.Present(listOf("Old title")),
        genre: MetadataFieldObservation = MetadataFieldObservation.Present(listOf("Jazz; Fusion", "Jazz")),
        format: MetadataPhysicalContainer = MetadataPhysicalContainer.FLAC,
        version: Int = MetadataReadFieldCatalog.PARSER_VERSION,
        overrides: Map<MetadataField, MetadataFieldObservation> = emptyMap(),
    ): MetadataFieldReadReport {
        val observations = MetadataReadFieldCatalog.primary.associateWith {
            MetadataFieldObservation.VerifiedAbsent as MetadataFieldObservation
        }.toMutableMap()
        observations[MetadataField.TITLE] = title
        observations[MetadataField.GENRE] = genre
        observations.putAll(overrides)
        return MetadataFieldReadReport(format, version, observations)
    }

    private fun check(
        original: MetadataFieldReadReport = report(),
        candidate: MetadataFieldReadReport = report(
            title = MetadataFieldObservation.Present(listOf("New title"))),
        manifest: MetadataCommitIntentManifest.Receipt = receipt,
    ) = MetadataCandidateSemanticPolicy.compare(manifest, original, candidate)

    @Test fun acceptsOnlyFullyObservedStructuredChanges() {
        val verdict = check()
        assertTrue(verdict is MetadataCandidateSemanticPolicy.Verdict.ObservedFieldsMatch)
        verdict as MetadataCandidateSemanticPolicy.Verdict.ObservedFieldsMatch
        assertFalse(verdict.sourceWriteCertified)
        assertFalse(verdict.unknownTagPreservationCertified)
        assertTrue(verdict.comparedUneditedFields > 0)
    }
    @Test fun rejectsOriginalThatDoesNotMatchApprovedBeforeValue() = assertEquals(
        MetadataCandidateSemanticPolicy.Verdict.Refused(
            MetadataCandidateSemanticPolicy.Refusal.ORIGINAL_INTENT_MISMATCH, MetadataField.TITLE),
        check(original = report(title = MetadataFieldObservation.Present(listOf("External edit")))))
    @Test fun rejectsCandidateThatDoesNotMatchApprovedAfterValue() = assertEquals(
        MetadataCandidateSemanticPolicy.Verdict.Refused(
            MetadataCandidateSemanticPolicy.Refusal.CANDIDATE_INTENT_MISMATCH, MetadataField.TITLE),
        check(candidate = report(title = MetadataFieldObservation.Present(listOf("Another title")))))
    @Test fun rejectsCandidateDroppingUneditedGenre() = assertEquals(
        MetadataCandidateSemanticPolicy.Verdict.Refused(
            MetadataCandidateSemanticPolicy.Refusal.UNEDITED_FIELD_CHANGED, MetadataField.GENRE),
        check(candidate = report(title = MetadataFieldObservation.Present(listOf("New title")),
            genre = MetadataFieldObservation.VerifiedAbsent)))
    @Test fun rejectsReorderedDuplicatesInUntouchedField() = assertEquals(
        MetadataCandidateSemanticPolicy.Verdict.Refused(
            MetadataCandidateSemanticPolicy.Refusal.UNEDITED_FIELD_CHANGED, MetadataField.GENRE),
        check(candidate = report(title = MetadataFieldObservation.Present(listOf("New title")),
            genre = MetadataFieldObservation.Present(listOf("Jazz", "Jazz; Fusion")))))
    @Test fun rejectsUnverifiedChangedOriginal() = assertEquals(
        MetadataCandidateSemanticPolicy.Verdict.Refused(
            MetadataCandidateSemanticPolicy.Refusal.ORIGINAL_CHANGED_FIELD_UNVERIFIABLE, MetadataField.TITLE),
        check(original = report(title = MetadataFieldObservation.Unverifiable(
            MetadataObservationIssue.BAD_TEXT_ENCODING))))
    @Test fun rejectsAmbiguousChangedCandidate() = assertEquals(
        MetadataCandidateSemanticPolicy.Verdict.Refused(
            MetadataCandidateSemanticPolicy.Refusal.CANDIDATE_CHANGED_FIELD_UNVERIFIABLE, MetadataField.TITLE),
        check(candidate = report(title = MetadataFieldObservation.Conflicted(
            MetadataObservationIssue.DUPLICATE_FIELD))))
    @Test fun rejectsUnobservedUntouchedField() = assertEquals(
        MetadataCandidateSemanticPolicy.Verdict.Refused(
            MetadataCandidateSemanticPolicy.Refusal.UNEDITED_FIELD_UNVERIFIABLE, MetadataField.ARTIST),
        check(candidate = report(title = MetadataFieldObservation.Present(listOf("New title")),
            overrides = mapOf(MetadataField.ARTIST to MetadataFieldObservation.Unverifiable(
                MetadataObservationIssue.FIELD_NOT_SUPPORTED)))))
    @Test fun rejectsChangedUnknownTagClaimAsVerifiedAbsent() = assertEquals(
        MetadataCandidateSemanticPolicy.Verdict.Refused(
            MetadataCandidateSemanticPolicy.Refusal.UNEDITED_FIELD_UNVERIFIABLE, MetadataField.ALBUM),
        check(original = report(overrides = mapOf(MetadataField.ALBUM to
            MetadataFieldObservation.Unverifiable(MetadataObservationIssue.UNSUPPORTED_FLAGS)))))
    @Test fun rejectsUnsupportedFormat() = assertEquals(
        MetadataCandidateSemanticPolicy.Verdict.Refused(
            MetadataCandidateSemanticPolicy.Refusal.CONTAINER_MISMATCH),
        check(candidate = report(title = MetadataFieldObservation.Present(listOf("New title")),
            format = MetadataPhysicalContainer.ID3_V24)))
    @Test fun rejectsLegacyParserVersion() = assertEquals(
        MetadataCandidateSemanticPolicy.Verdict.Refused(
            MetadataCandidateSemanticPolicy.Refusal.PARSER_VERSION_UNTRUSTED),
        check(original = report(version = 2)))
    @Test fun rejectsManifestWithUnsupportedContainer() = assertEquals(
        MetadataCandidateSemanticPolicy.Verdict.Refused(
            MetadataCandidateSemanticPolicy.Refusal.INVALID_INTENT),
        check(manifest = receipt.copy(container = MetadataAudioContainer.UNKNOWN)))
    @Test fun rejectsUnexpectedExtraObservedFieldMutation() = assertEquals(
        MetadataCandidateSemanticPolicy.Verdict.Refused(
            MetadataCandidateSemanticPolicy.Refusal.UNEDITED_FIELD_CHANGED, MetadataField.BARCODE),
        check(original = report(overrides = mapOf(MetadataField.BARCODE to
            MetadataFieldObservation.Present(listOf("AAA")))),
            candidate = report(title = MetadataFieldObservation.Present(listOf("New title")),
                overrides = mapOf(MetadataField.BARCODE to
                    MetadataFieldObservation.Present(listOf("BBB"))))))
    @Test fun refusesMissingObservationAsProofOfAbsence() {
        val input = report()
        val missing = input.copy(observations = input.observations - MetadataField.ARTIST)
        assertEquals(MetadataCandidateSemanticPolicy.Verdict.Refused(
            MetadataCandidateSemanticPolicy.Refusal.UNEDITED_FIELD_UNVERIFIABLE, MetadataField.ARTIST),
            check(original = missing))
    }
    @Test fun rejectsChangedValueWithSameCardinality() = assertEquals(
        MetadataCandidateSemanticPolicy.Verdict.Refused(
            MetadataCandidateSemanticPolicy.Refusal.CANDIDATE_INTENT_MISMATCH, MetadataField.TITLE),
        check(candidate = report(title = MetadataFieldObservation.Present(listOf("A different")))))

    @Test fun describedTextResourceCoversItsScalarProjectionOnly() {
        val before = "Comentario original"
        val after = "Comentario corregido"
        val describedIntent = MetadataCommitIntentManifest.Receipt(
            transactionId = "11234567-1234-4abc-8abc-123456789abc",
            ownerSha256 = MetadataPrivateSessionIdentity.digest(target),
            provider = MetadataDocumentProvider.MEDIASTORE,
            container = MetadataAudioContainer.ID3_V24,
            originalBytes = 1024,
            originalSha256 = "c".repeat(64),
            candidateBytes = 1032,
            candidateSha256 = "d".repeat(64),
            changes = emptyList(),
            resources = listOf(
                MetadataCommitIntentManifest.ResourceChange.DescribedText(
                    kind = MetadataId3DescribedTagCodec.Kind.COMMENT,
                    language = "spa",
                    description = "principal",
                    beforeCount = 1,
                    afterCount = 1,
                    beforeSha256 = MetadataCommitIntentManifest.valuesDigest(listOf(before)),
                    afterSha256 = MetadataCommitIntentManifest.valuesDigest(listOf(after)),
                ),
            ),
        )
        val original = report(
            format = MetadataPhysicalContainer.ID3_V24,
            overrides = mapOf(
                MetadataField.COMMENT to MetadataFieldObservation.Present(listOf(before)),
            ),
        )
        val candidate = report(
            format = MetadataPhysicalContainer.ID3_V24,
            overrides = mapOf(
                MetadataField.COMMENT to MetadataFieldObservation.Present(listOf(after)),
            ),
        )

        assertTrue(
            MetadataCandidateSemanticPolicy.compare(describedIntent, original, candidate) is
                MetadataCandidateSemanticPolicy.Verdict.ObservedFieldsMatch,
        )
        assertEquals(
            MetadataCandidateSemanticPolicy.Verdict.Refused(
                MetadataCandidateSemanticPolicy.Refusal.UNEDITED_FIELD_CHANGED,
                MetadataField.ARTIST,
            ),
            MetadataCandidateSemanticPolicy.compare(
                describedIntent,
                original,
                candidate.copy(
                    observations = candidate.observations + (
                        MetadataField.ARTIST to
                            MetadataFieldObservation.Present(listOf("Cambio ajeno"))
                        ),
                ),
            ),
        )
    }
}
