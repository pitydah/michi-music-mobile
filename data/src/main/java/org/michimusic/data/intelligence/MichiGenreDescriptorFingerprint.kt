package org.michimusic.data.intelligence

import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.security.MessageDigest

/**
 * MI-29: the last destructive-write gate hashes the SAME opened descriptor that
 * will be truncated. Reopening a URI for a fingerprint is insufficient: providers
 * can return different backing files for two concurrent openFileDescriptor calls.
 *
 * A pipe, a read-restricted FD, unexpected EOF, unknown size or any mismatch
 * fails closed. This method never modifies file contents and never starts work
 * outside a user-approved metadata edit / explicit restoration.
 */
internal object MichiGenreDescriptorFingerprint {
    fun matchesOpenedDescriptor(
        readable: FileChannel,
        expected: Pair<Long, String>,
        maxBytes: Long,
    ): Boolean {
        val expectedSize = expected.first
        if (expectedSize < 0 || expectedSize > maxBytes || maxBytes < 0 ||
            !Regex("[0-9a-f]{64}").matches(expected.second)) return false
        return try {
            val initialSize = readable.size()
            if (initialSize != expectedSize) return false
            readable.position(0)
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteBuffer.allocate(64 * 1024)
            var consumed = 0L
            while (consumed < expectedSize) {
                buffer.clear()
                buffer.limit(minOf(buffer.capacity().toLong(), expectedSize - consumed).toInt())
                val count = readable.read(buffer)
                if (count <= 0) return false
                consumed += count
                buffer.flip()
                digest.update(buffer)
            }
            val actual = digest.digest().joinToString("") { byte ->
                "%02x".format(byte.toInt() and 0xff)
            }
            readable.size() == expectedSize && actual == expected.second
        } catch (_: Exception) {
            false
        } finally {
            // Both FileInputStream and FileOutputStream wrap the SAME PFD. The
            // output channel explicitly resets its own position before truncate.
            try { readable.position(0) } catch (_: Exception) { }
        }
    }
}
