package org.michimusic.core.metadata

import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MetadataResourceSemanticPolicyTest {
    private val beforeBytes = ByteArray(32) { it.toByte() }
    private val afterBytes = ByteArray(48) { (it + 9).toByte() }
    private val unchangedBytes = ByteArray(24) { (it + 33).toByte() }

    @Test fun selectedPictureChangesAndUnselectedResourcesStayExact() {
        val original = snapshot(beforeBytes, unchangedBytes)
        val candidate = snapshot(afterBytes, unchangedBytes)
        val intent = receipt(original, candidate)

        assertEquals(
            MetadataResourceSemanticPolicy.Verdict.ResourcesMatch(1),
            MetadataResourceSemanticPolicy.compare(intent, original, candidate),
        )
    }

    @Test fun rejectsChangedUnselectedPictureEvenWhenSelectedPictureMatches() {
        val original = snapshot(beforeBytes, unchangedBytes)
        val candidate = snapshot(afterBytes, unchangedBytes.copyOf().also { it[0]++ })
        val result = MetadataResourceSemanticPolicy.compare(
            receipt(original, snapshot(afterBytes, unchangedBytes)),
            original,
            candidate,
        )
        assertEquals(
            MetadataResourceSemanticPolicy.Verdict.Refused(
                MetadataResourceSemanticPolicy.Refusal.UNSELECTED_RESOURCE_CHANGED,
            ),
            result,
        )
    }

    @Test fun rejectsCandidateWhoseSelectedBytesDoNotMatchIntent() {
        val original = snapshot(beforeBytes, unchangedBytes)
        val expected = snapshot(afterBytes, unchangedBytes)
        val candidate = snapshot(afterBytes.copyOf().also { it[0]++ }, unchangedBytes)
        val result = MetadataResourceSemanticPolicy.compare(
            receipt(original, expected), original, candidate,
        )
        assertTrue(
            result == MetadataResourceSemanticPolicy.Verdict.Refused(
                MetadataResourceSemanticPolicy.Refusal.CANDIDATE_RESOURCE_MISMATCH,
            ),
        )
    }

    @Test fun candidateReadbackVerifiesExactDurableAfterIdentity() {
        val original = snapshot(beforeBytes, unchangedBytes)
        val candidate = snapshot(afterBytes, unchangedBytes)
        val intent = receipt(original, candidate)
        assertEquals(
            MetadataResourceSemanticPolicy.Verdict.ResourcesMatch(1),
            MetadataResourceSemanticPolicy.compareCandidate(intent, candidate),
        )
        val tampered = snapshot(afterBytes.copyOf().also { it[it.lastIndex]++ }, unchangedBytes)
        assertEquals(
            MetadataResourceSemanticPolicy.Verdict.Refused(
                MetadataResourceSemanticPolicy.Refusal.CANDIDATE_RESOURCE_MISMATCH,
            ),
            MetadataResourceSemanticPolicy.compareCandidate(intent, tampered),
        )
    }

    private fun snapshot(first: ByteArray, second: ByteArray) =
        MetadataArtworkLyricsProbe.Snapshot(
            format = MetadataArtworkLyricsProbe.Format.ID3_V24,
            artworks = listOf(
                artwork(0, 3, "Cover", first),
                artwork(1, 4, "Back", second),
            ),
            texts = emptyList(),
        )

    private fun artwork(
        ordinal: Int,
        type: Int,
        description: String,
        bytes: ByteArray,
    ) = MetadataArtworkLyricsProbe.Artwork(
        ordinal,
        MetadataArtworkEvidence.Picture(
            format = MetadataArtworkEvidence.Format.ID3_APIC,
            type = type,
            mime = "image/jpeg",
            bytes = bytes.size,
            sha256 = sha(bytes),
            description = description,
        ),
        bytes,
    )

    private fun receipt(
        original: MetadataArtworkLyricsProbe.Snapshot,
        candidate: MetadataArtworkLyricsProbe.Snapshot,
    ) = MetadataCommitIntentManifest.Receipt(
        transactionId = "12341234-1234-4abc-8abc-123412341234",
        ownerSha256 = "a".repeat(64),
        provider = MetadataDocumentProvider.MEDIASTORE,
        container = MetadataAudioContainer.ID3_V24,
        originalBytes = 2048,
        originalSha256 = "b".repeat(64),
        candidateBytes = 4096,
        candidateSha256 = "c".repeat(64),
        changes = emptyList(),
        resources = listOf(
            MetadataCommitIntentManifest.ResourceChange.PictureReplacement(
                ordinal = 0,
                format = MetadataArtworkEvidence.Format.ID3_APIC,
                type = 3,
                description = "Cover",
                beforeMime = "image/jpeg",
                beforeBytes = original.artworks[0].image.size,
                beforeSha256 = original.artworks[0].info.sha256,
                afterMime = "image/jpeg",
                afterBytes = candidate.artworks[0].image.size,
                afterSha256 = candidate.artworks[0].info.sha256,
            ),
        ),
    )

    private fun sha(bytes: ByteArray) =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {
            "%02x".format(it.toInt() and 255)
        }
}
