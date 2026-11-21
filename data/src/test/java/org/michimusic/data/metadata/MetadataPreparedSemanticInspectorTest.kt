package org.michimusic.data.metadata

import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.michimusic.core.metadata.MetadataAudioContainer
import org.michimusic.core.metadata.MetadataChange
import org.michimusic.core.metadata.MetadataCommitIntentManifest
import org.michimusic.core.metadata.MetadataDocumentProvider
import org.michimusic.core.metadata.MetadataEditPreview
import org.michimusic.core.metadata.MetadataField
import org.michimusic.core.metadata.MetadataReview
import org.michimusic.core.metadata.MetadataSnapshot
import org.michimusic.core.metadata.MetadataTarget

/** No Android provider, source overwrite or media file is used in these cases. */
class MetadataPreparedSemanticInspectorTest {
    private val target = MetadataTarget("", "song", "content://media/external/audio/media/11", "ev")
    private fun hash(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
    private fun manifest(original: ByteArray, candidate: ByteArray): MetadataCommitIntentManifest.Receipt {
        val snapshot = MetadataSnapshot(target, true,
            mapOf(MetadataField.TITLE to listOf("Old")), verifiedFields = setOf(MetadataField.TITLE))
        val review = MetadataEditPreview.review(snapshot,
            listOf(MetadataChange.Replace(MetadataField.TITLE, listOf("New")))) as MetadataReview.Ready
        return MetadataCommitIntentManifest.create("01234567-1234-4abc-8abc-123456789abc",
            review, MetadataDocumentProvider.SAF, MetadataAudioContainer.ID3_V24,
            original.size.toLong(), hash(original), candidate.size.toLong(), hash(candidate))
    }
    private fun bytes(seed: Byte): ByteArray = ByteArray(24) { seed }

    @Test fun correctHashesButMalformedContainersCannotPassSemanticGate() {
        val root = Files.createTempDirectory("michi-semantic-").toFile()
        try {
            val original = File(root, "before.bin")
            val candidate = File(root, "after.bin")
            val before = bytes(1)
            val after = bytes(2)
            original.writeBytes(before)
            candidate.writeBytes(after)
            val result = MetadataPreparedSemanticInspector.inspectFiles(
                original, candidate, manifest(before, after))
            assertEquals(MetadataPreparedSemanticInspector.Refusal.SEMANTIC_MISMATCH,
                (result as MetadataPreparedSemanticInspector.Inspection.Refused).reason)
        } finally { root.deleteRecursively() }
    }
    @Test fun refusesCandidateWithSameLengthButDifferentHash() {
        val root = Files.createTempDirectory("michi-semantic-").toFile()
        try {
            val original = File(root, "before.bin")
            val candidate = File(root, "after.bin")
            val before = bytes(1)
            val after = bytes(2)
            original.writeBytes(before)
            candidate.writeBytes(bytes(3))
            val result = MetadataPreparedSemanticInspector.inspectFiles(
                original, candidate, manifest(before, after))
            assertEquals(MetadataPreparedSemanticInspector.Refusal.PRIVATE_HASH_MISMATCH,
                (result as MetadataPreparedSemanticInspector.Inspection.Refused).reason)
        } finally { root.deleteRecursively() }
    }
    @Test fun refusesMissingOriginalWithoutInventingBackup() {
        val root = Files.createTempDirectory("michi-semantic-").toFile()
        try {
            val candidate = File(root, "after.bin")
            val before = bytes(1)
            val after = bytes(2)
            candidate.writeBytes(after)
            val result = MetadataPreparedSemanticInspector.inspectFiles(
                File(root, "missing.bin"), candidate, manifest(before, after))
            assertEquals(MetadataPreparedSemanticInspector.Refusal.PRIVATE_FILE_UNREADABLE,
                (result as MetadataPreparedSemanticInspector.Inspection.Refused).reason)
        } finally { root.deleteRecursively() }
    }
    @Test fun refusesSymlinkedPrivateCandidateEvenWithMatchingBytes() {
        val root = Files.createTempDirectory("michi-semantic-").toFile()
        try {
            val original = File(root, "before.bin")
            val candidate = File(root, "candidate-link.bin")
            val actual = File(root, "target.bin")
            val before = bytes(1)
            val after = bytes(2)
            original.writeBytes(before)
            actual.writeBytes(after)
            Files.createSymbolicLink(candidate.toPath(), actual.toPath())
            val result = MetadataPreparedSemanticInspector.inspectFiles(
                original, candidate, manifest(before, after))
            assertEquals(MetadataPreparedSemanticInspector.Refusal.PRIVATE_FILE_UNREADABLE,
                (result as MetadataPreparedSemanticInspector.Inspection.Refused).reason)
            assertTrue(actual.readBytes().contentEquals(after))
        } finally { root.deleteRecursively() }
    }
    @Test fun noSourceWriteIsPossibleThroughInspector() {
        assertFalse(MetadataCommitIntentManifest.create("01234567-1234-4abc-8abc-123456789abc",
            MetadataEditPreview.review(MetadataSnapshot(target, true,
                mapOf(MetadataField.TITLE to listOf("Old")),
                verifiedFields = setOf(MetadataField.TITLE)),
                listOf(MetadataChange.Replace(MetadataField.TITLE, listOf("New")))) as MetadataReview.Ready,
            MetadataDocumentProvider.SAF, MetadataAudioContainer.ID3_V24,
            24, hash(bytes(1)), 24, hash(bytes(2))).sourceWriteCertified)
    }
}
