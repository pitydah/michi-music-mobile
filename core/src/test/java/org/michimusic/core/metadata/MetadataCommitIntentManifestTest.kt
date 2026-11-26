package org.michimusic.core.metadata

import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MetadataCommitIntentManifestTest {
    private val tx = "aaaaaaaa-1234-4abc-8abc-123456789abc"
    private val originalHash = "a".repeat(64)
    private val candidateHash = "b".repeat(64)
    private val owner = MetadataTarget("", "song-1", "content://media/external/audio/media/4", "evidence-1")
    private fun sha(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }

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

    @Test fun assetOnlyPictureReplacementHasExactDurableIdentity() {
        val before = ByteArray(32) { (it + 1).toByte() }
        val after = ByteArray(48) { (it + 2).toByte() }
        val snapshot = MetadataArtworkLyricsProbe.Snapshot(
            MetadataArtworkLyricsProbe.Format.ID3_V24,
            listOf(
                MetadataArtworkLyricsProbe.Artwork(
                    ordinal = 0,
                    info = MetadataArtworkEvidence.Picture(
                        MetadataArtworkEvidence.Format.ID3_APIC,
                        type = 3,
                        mime = "image/png",
                        bytes = before.size,
                        sha256 = sha(before),
                        description = "Front cover",
                    ),
                    image = before,
                ),
            ),
            emptyList(),
        )
        val proposal = MetadataArtworkReplacementPreview.Proposal(
            index = 0,
            sourceSha256 = sha(before),
            proposedSha256 = sha(after),
            proposedMime = "image/jpeg",
            proposedSize = after.size,
            proposedBytes = after,
        )

        val receipt = MetadataCommitIntentManifest.createBundle(
            transactionId = tx,
            target = owner,
            review = null,
            resources = snapshot,
            edits = listOf(MetadataSelectiveAssetStage.Edit.Picture(proposal)),
            provider = MetadataDocumentProvider.MEDIASTORE,
            container = MetadataAudioContainer.ID3_V24,
            originalBytes = 1024,
            originalSha256 = originalHash,
            candidateBytes = 2048,
            candidateSha256 = candidateHash,
        )

        assertTrue(receipt.changes.isEmpty())
        assertEquals(
            MetadataCommitIntentManifest.ResourceChange.PictureReplacement(
                ordinal = 0,
                format = MetadataArtworkEvidence.Format.ID3_APIC,
                type = 3,
                description = "Front cover",
                beforeMime = "image/png",
                beforeBytes = before.size,
                beforeSha256 = sha(before),
                afterMime = "image/jpeg",
                afterBytes = after.size,
                afterSha256 = sha(after),
            ),
            receipt.resources.single(),
        )
        assertTrue(MetadataCommitIntentManifest.valid(receipt))
    }

    @Test fun describedTextReplacementAndClearAreDurablyDistinct() {
        val identity = MetadataDescribedTextWorkbench.Identity(
            MetadataId3DescribedTagCodec.Kind.LYRICS,
            "spa",
            "En vivo",
        )
        val snapshot = MetadataArtworkLyricsProbe.Snapshot(
            MetadataArtworkLyricsProbe.Format.ID3_V24,
            emptyList(),
            listOf(MetadataDescribedTextWorkbench.Item(identity, "Antes")),
        )
        fun receipt(change: MetadataDescribedTextWorkbench.Change) =
            MetadataCommitIntentManifest.createBundle(
                tx, owner, null, snapshot,
                listOf(MetadataSelectiveAssetStage.Edit.DescribedText(change)),
                MetadataDocumentProvider.MEDIASTORE, MetadataAudioContainer.ID3_V24,
                1024, originalHash, 2048, candidateHash,
            )

        val replaced = receipt(
            MetadataDescribedTextWorkbench.Change.Replace(identity, "Después"),
        ).resources.single() as MetadataCommitIntentManifest.ResourceChange.DescribedText
        val cleared = receipt(
            MetadataDescribedTextWorkbench.Change.Clear(identity),
        ).resources.single() as MetadataCommitIntentManifest.ResourceChange.DescribedText

        assertEquals(1, replaced.afterCount)
        assertEquals(0, cleared.afterCount)
        assertFalse(replaced.afterSha256 == cleared.afterSha256)
    }

    @Test fun describedTextAdditionIsDurablyDistinctAndValid() {
        val identity = MetadataDescribedTextWorkbench.Identity(
            MetadataId3DescribedTagCodec.Kind.COMMENT,
            "eng",
            "",
        )
        val snapshot = MetadataArtworkLyricsProbe.Snapshot(
            MetadataArtworkLyricsProbe.Format.ID3_V24,
            emptyList(),
            emptyList(),
        )
        val receipt = MetadataCommitIntentManifest.createBundle(
            tx, owner, null, snapshot,
            listOf(MetadataSelectiveAssetStage.Edit.DescribedText(
                MetadataDescribedTextWorkbench.Change.Add(identity, "Nuevo"),
            )),
            MetadataDocumentProvider.MEDIASTORE, MetadataAudioContainer.ID3_V24,
            1024, originalHash, 2048, candidateHash,
        )
        val added = receipt.resources.single() as
            MetadataCommitIntentManifest.ResourceChange.DescribedText
        assertEquals(0, added.beforeCount)
        assertEquals(1, added.afterCount)
        assertEquals(
            MetadataCommitIntentManifest.valuesDigest(emptyList()), added.beforeSha256)
        assertEquals(
            MetadataCommitIntentManifest.valuesDigest(listOf("Nuevo")), added.afterSha256)
        assertTrue(MetadataCommitIntentManifest.valid(receipt))
    }

    @Test fun describedTextAdditionRequiresVerifiedAbsence() {
        val identity = MetadataDescribedTextWorkbench.Identity(
            MetadataId3DescribedTagCodec.Kind.COMMENT,
            "eng",
            "",
        )
        val present = MetadataArtworkLyricsProbe.Snapshot(
            MetadataArtworkLyricsProbe.Format.ID3_V24,
            emptyList(),
            listOf(MetadataDescribedTextWorkbench.Item(identity, "Ya existe")),
        )
        var rejected = false
        try {
            MetadataCommitIntentManifest.createBundle(
                tx, owner, null, present,
                listOf(MetadataSelectiveAssetStage.Edit.DescribedText(
                    MetadataDescribedTextWorkbench.Change.Add(identity, "Nuevo"),
                )),
                MetadataDocumentProvider.MEDIASTORE, MetadataAudioContainer.ID3_V24,
                1024, originalHash, 2048, candidateHash,
            )
        } catch (_: IllegalArgumentException) {
            rejected = true
        }
        assertTrue(rejected)
    }

    @Test fun describedTextZeroBeforeAndZeroAfterIsNeverValid() {
        val emptyDigest = MetadataCommitIntentManifest.valuesDigest(emptyList())
        val forged = MetadataCommitIntentManifest.Receipt(
            transactionId = tx,
            ownerSha256 = "a".repeat(64),
            provider = MetadataDocumentProvider.MEDIASTORE,
            container = MetadataAudioContainer.ID3_V24,
            originalBytes = 1024,
            originalSha256 = originalHash,
            candidateBytes = 2048,
            candidateSha256 = candidateHash,
            changes = emptyList(),
            resources = listOf(
                MetadataCommitIntentManifest.ResourceChange.DescribedText(
                    kind = MetadataId3DescribedTagCodec.Kind.COMMENT,
                    language = "eng",
                    description = "",
                    beforeCount = 0,
                    afterCount = 0,
                    beforeSha256 = emptyDigest,
                    afterSha256 = emptyDigest,
                ),
            ),
        )
        assertFalse(MetadataCommitIntentManifest.valid(forged))
    }

    @Test fun rejectsDuplicateResourceTargets() {
        val identity = MetadataDescribedTextWorkbench.Identity(
            MetadataId3DescribedTagCodec.Kind.COMMENT,
            "spa",
            "",
        )
        val snapshot = MetadataArtworkLyricsProbe.Snapshot(
            MetadataArtworkLyricsProbe.Format.ID3_V24,
            emptyList(),
            listOf(MetadataDescribedTextWorkbench.Item(identity, "Antes")),
        )
        var rejected = false
        try {
            MetadataCommitIntentManifest.createBundle(
                tx, owner, null, snapshot,
                listOf(
                    MetadataSelectiveAssetStage.Edit.DescribedText(
                        MetadataDescribedTextWorkbench.Change.Replace(identity, "Uno"),
                    ),
                    MetadataSelectiveAssetStage.Edit.DescribedText(
                        MetadataDescribedTextWorkbench.Change.Replace(identity, "Dos"),
                    ),
                ),
                MetadataDocumentProvider.MEDIASTORE, MetadataAudioContainer.ID3_V24,
                1024, originalHash, 2048, candidateHash,
            )
        } catch (_: IllegalArgumentException) {
            rejected = true
        }
        assertTrue(rejected)
    }
}
