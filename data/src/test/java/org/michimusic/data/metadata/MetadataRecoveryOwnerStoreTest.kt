package org.michimusic.data.metadata

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.michimusic.core.metadata.MetadataRecoveryPhase
import org.michimusic.core.metadata.MetadataTarget

class MetadataRecoveryOwnerStoreTest {
    private val tx = "a999a999-1234-4abc-8abc-999a999a9999"
    private val entry = MetadataRecoveryJournalV1.Entry(
        tx, MetadataRecoveryPhase.MUTATING, "lib_device_local", "track-1",
        "content://media/external/audio/media/7", 4096, 98765,
    )
    private val target = MetadataTarget(
        "lib_device_local", "track-1",
        "content://media/external/audio/media/7", "me04:owner-token",
    )

    @Test fun roundTripIsIdentityBoundAndIdempotent() {
        val root = Files.createTempDirectory("me13-owner-").toFile()
        try {
            val store = MetadataRecoveryOwnerStore(root)
            assertTrue(store.write(entry, target))
            assertTrue(store.write(entry, target))
            val receipt = store.read(entry)!!
            assertEquals(tx, receipt.transactionId)
            assertEquals("lib_device_local", receipt.sourceId)
            assertEquals("me04:owner-token", receipt.evidenceToken)
            assertTrue(receipt.ownerSha256.matches(Regex("[a-f0-9]{64}")))
            assertEquals(1, root.listFiles()!!.count { it.name.startsWith("owner-$tx") && it.extension == "bin" })
        } finally { root.deleteRecursively() }
    }

    @Test fun foreignTargetCannotWriteTheReceipt() {
        val root = Files.createTempDirectory("me13-owner-").toFile()
        try {
            val store = MetadataRecoveryOwnerStore(root)
            assertEquals(false, store.write(entry, target.copy(trackId = "other")))
            assertNull(store.read(entry))
        } finally { root.deleteRecursively() }
    }

    @Test fun tamperedReceiptIsRejectedAndCanBeDeleted() {
        val root = Files.createTempDirectory("me13-owner-").toFile()
        try {
            val store = MetadataRecoveryOwnerStore(root)
            assertTrue(store.write(entry, target))
            val file = store.file(tx)
            val bytes = file.readBytes()
            bytes[bytes.size / 2] = (bytes[bytes.size / 2].toInt() xor 0x40).toByte()
            file.writeBytes(bytes)
            assertNull(store.read(entry))
            assertTrue(store.delete(entry))
            assertNull(store.read(entry))
        } finally { root.deleteRecursively() }
    }
}
