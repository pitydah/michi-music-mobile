package org.michimusic.data.metadata

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MetadataIndexAppliedReceiptTest {
    private val tx = "5f50aa5f-d5c2-4bc2-8884-77d93bfc9e68"
    private val receipt = MetadataIndexAppliedReceipt(
        transactionId = tx,
        sourceId = "lib_device_local",
        trackId = "local_192",
        uri = "content://media/external_primary/audio/media/192",
        candidateSha256 = "6382aa11fa32ef12883a2b89fe2bfe540554d0f96cc3a2dddeaa6b8f4afe3791",
        candidateBytes = 5033,
    )

    @Test fun roundTripIsAtomicAndDeletable() {
        val root = Files.createTempDirectory("me13-applied").toFile()
        try {
            assertTrue(MetadataIndexAppliedReceipt.write(root, receipt))
            assertEquals(receipt, MetadataIndexAppliedReceipt.read(root, tx))
            val file = MetadataIndexAppliedReceipt.file(root, tx)
            assertTrue(file.isFile)
            assertTrue(MetadataIndexAppliedReceipt.delete(root, tx))
            assertFalse(file.exists())
            assertNull(MetadataIndexAppliedReceipt.read(root, tx))
        } finally { root.deleteRecursively() }
    }

    @Test fun tamperedProofIsRejected() {
        val root = Files.createTempDirectory("me13-applied-tamper").toFile()
        try {
            assertTrue(MetadataIndexAppliedReceipt.write(root, receipt))
            val file = MetadataIndexAppliedReceipt.file(root, tx)
            val bytes = file.readBytes().also {
                it[it.lastIndex - 5] = (it[it.lastIndex - 5] + 1).toByte()
            }
            file.writeBytes(bytes)
            assertNull(MetadataIndexAppliedReceipt.read(root, tx))
        } finally { root.deleteRecursively() }
    }

    @Test fun symlinkCannotStandInForTheProof() {
        val root = Files.createTempDirectory("me13-applied-symlink").toFile()
        try {
            val outside = File(root.parentFile, "me13-applied-outside-${System.nanoTime()}.bin")
            outside.writeBytes(receipt.encode())
            try {
                val target = MetadataIndexAppliedReceipt.file(root, tx)
                Files.createSymbolicLink(target.toPath(), outside.toPath())
                assertNull(MetadataIndexAppliedReceipt.read(root, tx))
                assertFalse(MetadataIndexAppliedReceipt.write(root, receipt))
            } finally { outside.delete() }
        } finally { root.deleteRecursively() }
    }
}
