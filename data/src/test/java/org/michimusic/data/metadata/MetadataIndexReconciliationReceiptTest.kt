package org.michimusic.data.metadata

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MetadataIndexReconciliationReceiptTest {
    private val v1 = MetadataIndexReconciliationReceipt(
        transactionId = "5f50aa5f-d5c2-4bc2-8884-77d93bfc9e68",
        sourceId = "lib_device_local",
        trackId = "local_192",
        uri = "content://media/external_primary/audio/media/192",
        originalSha256 = "d49359e65ba926363a29005f5b933b46547a3198131f8b0d7dca884e88478ca8",
        candidateSha256 = "6382aa11fa32ef12883a2b89fe2bfe540554d0f96cc3a2dddeaa6b8f4afe3791",
        candidateBytes = 5033,
        format = "ID3_V24",
        version = 1,
    )
    private val v3 = MetadataIndexReconciliationReceipt(
        transactionId = "5f50aa5f-d5c2-4bc2-8884-77d93bfc9e68",
        sourceId = "lib_device_local",
        trackId = "local_192",
        uri = "content://media/external_primary/audio/media/192",
        originalSha256 = "d49359e65ba926363a29005f5b933b46547a3198131f8b0d7dca884e88478ca8",
        candidateSha256 = "6382aa11fa32ef12883a2b89fe2bfe540554d0f96cc3a2dddeaa6b8f4afe3791",
        candidateBytes = 5033,
        format = "ID3_V24",
        ownerEvidenceToken = "me04:fixture-token",
        previousVerifiedAt = 77L,
        previousVerifiedSha256 = "a".repeat(64),
        previousSize = 5000L,
        previousMetadataRevision = 12L,
    )

    @Test fun v3RoundTripKeepsExactIdentityAndRowPreimage() {
        val decoded = MetadataIndexReconciliationReceipt.decode(v3.encode())!!
        assertEquals(v3, decoded)
        assertTrue(decoded.actionable)
        assertEquals(77L, decoded.expectedIndex()!!.version)
        assertEquals("a".repeat(64), decoded.expectedIndex()!!.observedSourceSha256)
        assertEquals(12L, decoded.expectedIndex()!!.metadataRevision)
    }

    @Test fun v1IsReadableButNeverActionable() {
        val decoded = MetadataIndexReconciliationReceipt.decode(v1.encode())!!
        assertEquals(v1, decoded)
        assertFalse(decoded.actionable)
        assertNull(decoded.expectedIndex())
    }

    @Test fun unhashedPreimageIsActionableOnlyWithEmptyVerification() {
        val unhashed = v3.copy(previousVerifiedAt = 0L, previousVerifiedSha256 = "")
        assertTrue(unhashed.actionable)
        assertEquals(0L, unhashed.expectedIndex()!!.version)
        assertFalse(v3.copy(previousVerifiedAt = 0L).actionable)
        assertFalse(v3.copy(previousVerifiedSha256 = "").actionable)
    }

    @Test fun v3SafReceiptCanReachProviderBoundReconciliation() {
        val saf = v3.copy(
            sourceId = "safroot_0123456789abcdef0123456789abcdef01234567",
            trackId = "saf_0123456789abcdef0123456789abcdef01234567",
            uri = "content://com.android.externalstorage.documents/document/" +
                "primary%3ADownload%2Fmichi.mp3",
        )

        assertTrue(saf.actionable)
        assertEquals(saf.sourceId, saf.expectedIndex()!!.sourceId)
        assertFalse(saf.copy(sourceId = "").actionable)
        assertFalse(saf.copy(uri = "file:///sdcard/Download/michi.mp3").actionable)
    }

    @Test fun v2IsReadableWithConservativeRevisionButNeverActionable() {
        val legacy = v3.copy(version = 2, previousMetadataRevision = 0L)
        val decoded = MetadataIndexReconciliationReceipt.decode(legacy.encode())!!
        assertEquals(legacy, decoded)
        assertEquals(0L, decoded.previousMetadataRevision)
        assertFalse(decoded.actionable)
        assertNull(decoded.expectedIndex())
    }

    @Test fun tamperedBytesAreRejected() {
        val bytes = v3.encode().also {
            it[it.lastIndex - 2] = (it[it.lastIndex - 2] + 1).toByte()
        }
        assertNull(MetadataIndexReconciliationReceipt.decode(bytes))
    }

    @Test fun fileWriteIsAtomicAndReadable() {
        val root = Files.createTempDirectory("me13-receipt").toFile()
        try {
            assertEquals(true, MetadataIndexReconciliationReceipt.write(root, v3))
            assertEquals(v3, MetadataIndexReconciliationReceipt.read(root, v3.transactionId))
            val target = MetadataIndexReconciliationReceipt.file(root, v3.transactionId)
            assertEquals(true, target.isFile)
        } finally { root.deleteRecursively() }
    }

    @Test fun boundedListingSkipsSymlinksAndOversizedFiles() {
        val root = Files.createTempDirectory("me13-receipt-list").toFile()
        try {
            assertEquals(true, MetadataIndexReconciliationReceipt.write(root, v3))
            val valid = MetadataIndexReconciliationReceipt.file(root, v3.transactionId)
            val oversized = File(root, "index-reconciliation-${"c".repeat(36)}.bin")
            oversized.writeBytes(ByteArray((MetadataIndexReconciliationReceipt.MAX_FILE_BYTES + 1).toInt()))
            val symlink = File(root, "index-reconciliation-${"d".repeat(36)}.bin")
            Files.createSymbolicLink(symlink.toPath(), valid.toPath())
            val listed = MetadataIndexReconciliationReceipt.list(root)
            assertEquals(listOf(valid), listed)
            assertNotNull(MetadataIndexReconciliationReceipt.read(valid))
            val entries = MetadataIndexReconciliationReceipt.entries(root)
            assertEquals(listOf(
                MetadataIndexReconciliationReceipt.Entry.ValidCandidate(valid),
                MetadataIndexReconciliationReceipt.Entry.Suspicious(oversized.name,
                    "INVALID_RECEIPT_SIZE"),
                MetadataIndexReconciliationReceipt.Entry.Suspicious(symlink.name,
                    "RECEIPT_SYMLINK"),
            ), entries)
        } finally { root.deleteRecursively() }
    }

    @Test fun nonRegularCandidateIsReportedAndNeverFollowed() {
        val root = Files.createTempDirectory("me13-receipt-dir").toFile()
        try {
            val directory = File(root, "index-reconciliation-${"e".repeat(36)}.bin")
            assertTrue(directory.mkdir())
            File(directory, "child").writeText("x")
            assertEquals(listOf(
                MetadataIndexReconciliationReceipt.Entry.Suspicious(directory.name,
                    "NOT_REGULAR_FILE"),
            ), MetadataIndexReconciliationReceipt.entries(root))
            assertTrue(MetadataIndexReconciliationReceipt.list(root).isEmpty())
            assertNull(MetadataIndexReconciliationReceipt.read(directory))
        } finally { root.deleteRecursively() }
    }

    @Test fun atomicWriteRefusesToReplaceADirectory() {
        val root = Files.createTempDirectory("me13-receipt-atomic").toFile()
        try {
            val target = MetadataIndexReconciliationReceipt.file(root, v3.transactionId)
            assertTrue(target.mkdir())
            File(target, "child").writeText("x")
            assertFalse(MetadataIndexReconciliationReceipt.write(root, v3))
        } finally { root.deleteRecursively() }
    }
}
