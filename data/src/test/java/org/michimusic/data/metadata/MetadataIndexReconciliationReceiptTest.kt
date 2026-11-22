package org.michimusic.data.metadata

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class MetadataIndexReconciliationReceiptTest {
    private val receipt = MetadataIndexReconciliationReceipt(
        transactionId = "5f50aa5f-d5c2-4bc2-8884-77d93bfc9e68",
        sourceId = "lib_device_local",
        trackId = "local_192",
        uri = "content://media/external_primary/audio/media/192",
        originalSha256 = "d49359e65ba926363a29005f5b933b46547a3198131f8b0d7dca884e88478ca8",
        candidateSha256 = "6382aa11fa32ef12883a2b89fe2bfe540554d0f96cc3a2dddeaa6b8f4afe3791",
        candidateBytes = 5033,
        format = "ID3_V24",
    )

    @Test fun roundTripKeepsExactIdentity() {
        val decoded = MetadataIndexReconciliationReceipt.decode(receipt.encode())!!
        assertEquals(receipt, decoded)
    }

    @Test fun tamperedBytesAreRejected() {
        val bytes = receipt.encode().also { it[it.lastIndex - 2] = (it[it.lastIndex - 2] + 1).toByte() }
        assertNull(MetadataIndexReconciliationReceipt.decode(bytes))
    }

    @Test fun fileWriteIsAtomicAndReadable() {
        val root = Files.createTempDirectory("me13-receipt").toFile()
        try {
            assertEquals(true, MetadataIndexReconciliationReceipt.write(root, receipt))
            assertEquals(receipt, MetadataIndexReconciliationReceipt.read(root, receipt.transactionId))
            val target = MetadataIndexReconciliationReceipt.file(root, receipt.transactionId)
            assertEquals(true, target.isFile)
        } finally { root.deleteRecursively() }
    }
}
