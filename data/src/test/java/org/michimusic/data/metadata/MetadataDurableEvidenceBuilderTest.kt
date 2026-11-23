package org.michimusic.data.metadata

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.michimusic.core.metadata.MediaStoreMp3TextProfile
import org.michimusic.core.metadata.MetadataAudioContainer
import org.michimusic.core.metadata.MetadataChange
import org.michimusic.core.metadata.MetadataCommitIntentManifest
import org.michimusic.core.metadata.MetadataDocumentProvider
import org.michimusic.core.metadata.MetadataEditPreview
import org.michimusic.core.metadata.MetadataField
import org.michimusic.core.metadata.MetadataId3StageRewriter
import org.michimusic.core.metadata.MetadataRecoveryPhase
import org.michimusic.core.metadata.MetadataSnapshot
import org.michimusic.core.metadata.MetadataTarget

class MetadataDurableEvidenceBuilderTest {
    private val tx = "b999b999-1234-4abc-8abc-999b999b999b"
    private val sourceId = "lib_device_local"
    private val trackId = "track-1"
    private val uri = "content://media/external/audio/media/7"
    private val token = "me04:durable-owner"
    private val target = MetadataTarget(sourceId, trackId, uri, token)
    private val original = sample("Original")
    private val candidate = stage(original, "Edited")
    private val review = review()
    private val intent = MetadataCommitIntentManifest.create(
        transactionId = tx, review = review, provider = MetadataDocumentProvider.MEDIASTORE,
        container = MetadataAudioContainer.ID3_V24,
        originalBytes = original.size.toLong(), originalSha256 = sha(original),
        candidateBytes = candidate.size.toLong(), candidateSha256 = sha(candidate),
    )
    private val entry = MetadataRecoveryJournalV1.Entry(
        tx, MetadataRecoveryPhase.MUTATION_UNKNOWN, sourceId, trackId, uri,
        original.size.toLong(), 98765, originalSha256 = sha(original),
        stageSha256 = sha(candidate), stageBytes = candidate.size.toLong(),
        format = MetadataAudioContainer.ID3_V24.name, ownerEvidenceToken = token,
    )

    @Test fun rebuildsTheOriginalSnapshotFromPrivateCopiesOnly() {
        val root = Files.createTempDirectory("me13-evidence-").toFile()
        try {
            val journal = MetadataRecoveryJournalV1(root)
            journal.backup(entry).writeBytes(original)
            journal.stage(entry).writeBytes(candidate)
            val rebuilt = MetadataDurableEvidenceBuilder.rebuild(journal, entry, intent, target)
            assertTrue(rebuilt is MetadataDurableEvidenceBuilder.Result.Rebuilt)
            val snapshot = (rebuilt as MetadataDurableEvidenceBuilder.Result.Rebuilt).original
            assertEquals(listOf("Original"), snapshot.known[MetadataField.TITLE])
            assertEquals(uri, snapshot.target.uri)
        } finally { root.deleteRecursively() }
    }

    @Test fun alteredCandidateIsRefusedBeforeAnyDigestProof() {
        val root = Files.createTempDirectory("me13-evidence-").toFile()
        try {
            val journal = MetadataRecoveryJournalV1(root)
            journal.backup(entry).writeBytes(original)
            val altered = candidate.copyOf()
            altered[altered.size - 1] = (altered.last().toInt() xor 1).toByte()
            journal.stage(entry).writeBytes(altered)
            val rebuilt = MetadataDurableEvidenceBuilder.rebuild(journal, entry, intent, target)
            assertTrue(rebuilt is MetadataDurableEvidenceBuilder.Result.Refused)
        } finally { root.deleteRecursively() }
    }

    @Test fun foreignOwnerTokenCannotReconstructTheReceipt() {
        val root = Files.createTempDirectory("me13-evidence-").toFile()
        try {
            val journal = MetadataRecoveryJournalV1(root)
            journal.backup(entry).writeBytes(original)
            journal.stage(entry).writeBytes(candidate)
            val rebuilt = MetadataDurableEvidenceBuilder.rebuild(
                journal, entry, intent, target.copy(evidenceToken = "me04:other"))
            assertEquals(
                "OWNER_EVIDENCE_MISMATCH",
                (rebuilt as MetadataDurableEvidenceBuilder.Result.Refused).code,
            )
        } finally { root.deleteRecursively() }
    }

    private fun review(): org.michimusic.core.metadata.MetadataReview.Ready {
        val snapshot = MetadataSnapshot(
            target = target, tagsVerified = true,
            known = mapOf(MetadataField.TITLE to listOf("Original")),
            verifiedFields = setOf(MetadataField.TITLE),
        )
        return MetadataEditPreview.review(
            snapshot, listOf(MetadataChange.Replace(MetadataField.TITLE, listOf("Edited"))),
        ) as org.michimusic.core.metadata.MetadataReview.Ready
    }

    private fun stage(source: ByteArray, title: String): ByteArray {
        val dir = Files.createTempDirectory("me13-stage-").toFile()
        try {
            val sourceFile = File(dir, "source.mp3").also { it.writeBytes(source) }
            val stageFile = File(dir, "stage.mp3")
            val snapshot = MetadataSnapshot(
                target = target, tagsVerified = true,
                known = mapOf(MetadataField.TITLE to listOf("Original")),
                verifiedFields = setOf(MetadataField.TITLE),
            )
            val proposal = MetadataEditPreview.review(
                snapshot, listOf(MetadataChange.Replace(MetadataField.TITLE, listOf(title))))
            MetadataId3StageRewriter.stage(sourceFile, stageFile, proposal, source.size.toLong())
            return stageFile.readBytes()
        } finally { dir.deleteRecursively() }
    }

    private fun sample(title: String): ByteArray {
        val body = title.toByteArray(Charsets.UTF_8)
        val frame = ByteArray(10 + 1 + body.size)
        "TIT2".toByteArray().copyInto(frame, 0)
        val size = 1 + body.size
        frame[4] = ((size shr 21) and 0x7f).toByte()
        frame[5] = ((size shr 14) and 0x7f).toByte()
        frame[6] = ((size shr 7) and 0x7f).toByte()
        frame[7] = (size and 0x7f).toByte()
        frame[10] = 3
        body.copyInto(frame, 11)
        val tag = ByteArray(10) + frame
        "ID3".toByteArray().copyInto(tag, 0)
        tag[3] = 4
        val tagSize = frame.size
        tag[6] = ((tagSize shr 21) and 0x7f).toByte()
        tag[7] = ((tagSize shr 14) and 0x7f).toByte()
        tag[8] = ((tagSize shr 7) and 0x7f).toByte()
        tag[9] = (tagSize and 0x7f).toByte()
        return tag + ByteArray(1024) { (it % 239).toByte() }
    }

    private fun sha(bytes: ByteArray): String =
        java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
}
