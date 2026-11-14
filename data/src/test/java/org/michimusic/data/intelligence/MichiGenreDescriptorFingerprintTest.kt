package org.michimusic.data.intelligence

import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Test

class MichiGenreDescriptorFingerprintTest {
    private fun withFile(block: (File) -> Unit) {
        val f = Files.createTempFile("mi29-pfd", ".flac").toFile()
        try { block(f) } finally { f.delete() }
    }
    private fun fingerprint(bytes: ByteArray): Pair<Long, String> {
        val hash = MessageDigest.getInstance("SHA-256")
            .digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
        return bytes.size.toLong() to hash
    }
    @Test fun sameOpenedDescriptorMatchesAndRewinds() = withFile { f ->
        val contents = ByteArray(131_071) { (it % 251).toByte() }
        f.writeBytes(contents)
        RandomAccessFile(f, "rw").use { raf ->
            raf.channel.position(23)
            assertTrue(MichiGenreDescriptorFingerprint.matchesOpenedDescriptor(
                raf.channel, fingerprint(contents), 200_000))
            assertEquals(0L, raf.channel.position())
            assertEquals(contents.size.toLong(), raf.channel.size())
        }
        assertArrayEquals(contents, f.readBytes())
    }
    @Test fun sameLengthExternalReplacementIsRejected() = withFile { f ->
        val original = "ORIGINAL-TAG-AUDIO".toByteArray()
        f.writeBytes(original)
        val approved = fingerprint(original)
        f.writeBytes("UNEXPECTED-AUDIO!!".toByteArray())
        assertEquals(original.size.toLong(), f.length())
        RandomAccessFile(f, "rw").use { raf ->
            assertFalse(MichiGenreDescriptorFingerprint.matchesOpenedDescriptor(
                raf.channel, approved, 200_000))
        }
    }
    @Test fun mismatchedSizeAndSizeLimitAreRejected() = withFile { f ->
        val original = "some music".toByteArray()
        f.writeBytes(original)
        RandomAccessFile(f, "rw").use { raf ->
            assertFalse(MichiGenreDescriptorFingerprint.matchesOpenedDescriptor(
                raf.channel, fingerprint(original).copy(first=20), 200_000))
            assertFalse(MichiGenreDescriptorFingerprint.matchesOpenedDescriptor(
                raf.channel, fingerprint(original), 5))
        }
    }
    @Test fun emptyRecoveryTargetIsValidOnlyForExactEmptyDigest() = withFile { f ->
        f.writeBytes(byteArrayOf())
        RandomAccessFile(f, "rw").use { raf ->
            assertTrue(MichiGenreDescriptorFingerprint.matchesOpenedDescriptor(
                raf.channel, fingerprint(byteArrayOf()), 100))
            assertFalse(MichiGenreDescriptorFingerprint.matchesOpenedDescriptor(
                raf.channel, 0L to "0".repeat(64), 100))
        }
    }
    @Test fun cannotReadWriteOnlyChannelRejectsWithoutDamage() = withFile { f ->
        val contents = "preserve all bytes".toByteArray()
        f.writeBytes(contents)
        java.io.FileOutputStream(f, true).use { stream ->
            assertFalse(MichiGenreDescriptorFingerprint.matchesOpenedDescriptor(
                stream.channel, fingerprint(contents), 200_000))
        }
        assertArrayEquals(contents, f.readBytes())
    }
}
