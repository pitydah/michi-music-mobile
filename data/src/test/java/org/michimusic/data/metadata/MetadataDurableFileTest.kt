package org.michimusic.data.metadata

import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MetadataDurableFileTest {
    private val bytes = ByteArray(1024) { (it % 239).toByte() }

    private fun sha(value: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(value)
            .joinToString("") { "%02x".format(it.toInt() and 255) }

    @Test fun copiesExactBytesAndForcesTheFile() {
        val root = Files.createTempDirectory("me14-durable-copy-").toFile()
        try {
            val source = File(root, "source.bin").apply { writeBytes(bytes) }
            val target = File(root, "target.bin")
            assertTrue(MetadataDurableFile.copyExact(
                source, target, bytes.size.toLong(), sha(bytes)))
            assertArrayEquals(bytes, target.readBytes())
            assertTrue(MetadataDurableFile.forceExact(target))
        } finally { root.deleteRecursively() }
    }

    @Test fun refusesAnExistingTargetWithoutChangingIt() {
        val root = Files.createTempDirectory("me14-durable-existing-").toFile()
        try {
            val source = File(root, "source.bin").apply { writeBytes(bytes) }
            val target = File(root, "target.bin").apply { writeBytes(byteArrayOf(9)) }
            assertFalse(MetadataDurableFile.copyExact(
                source, target, bytes.size.toLong(), sha(bytes)))
            assertArrayEquals(byteArrayOf(9), target.readBytes())
        } finally { root.deleteRecursively() }
    }

    @Test fun wrongDigestNeverPublishesAPartialTarget() {
        val root = Files.createTempDirectory("me14-durable-digest-").toFile()
        try {
            val source = File(root, "source.bin").apply { writeBytes(bytes) }
            val target = File(root, "target.bin")
            val wrong = if (sha(bytes) == "0".repeat(64)) "1".repeat(64)
                else "0".repeat(64)
            assertFalse(MetadataDurableFile.copyExact(
                source, target, bytes.size.toLong(), wrong))
            assertFalse(target.exists())
        } finally { root.deleteRecursively() }
    }
}
