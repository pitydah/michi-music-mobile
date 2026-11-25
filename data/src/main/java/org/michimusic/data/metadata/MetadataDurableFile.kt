package org.michimusic.data.metadata

import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest

/**
 * Durable private-file boundary for the recovery journal. A successful return
 * means the exact bytes are on the file and the parent directory entry has been
 * forced where the platform permits it, not merely that a stream accepted them.
 */
internal object MetadataDurableFile {
    private const val MAX_BYTES = 1024L * 1024 * 1024
    private const val CHUNK = 64 * 1024

    fun copyExact(
        source: File,
        target: File,
        expectedBytes: Long,
        expectedSha256: String,
    ): Boolean {
        if (expectedBytes !in 12..MAX_BYTES ||
            !SHA.matches(expectedSha256) ||
            !source.isFile || source.length() != expectedBytes
        ) return false
        val parent = target.parentFile ?: return false
        if (!parent.isDirectory || Files.isSymbolicLink(parent.toPath()) ||
            target.exists() || Files.isSymbolicLink(target.toPath())
        ) return false
        var complete = false
        try {
            FileChannel.open(
                target.toPath(),
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE,
                LinkOption.NOFOLLOW_LINKS,
            ).use { output ->
                FileInputStream(source).use { input ->
                    val buffer = ByteArray(CHUNK)
                    var total = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        if (read == 0) continue
                        val wrapped = ByteBuffer.wrap(buffer, 0, read)
                        while (wrapped.hasRemaining()) output.write(wrapped)
                        total += read
                    }
                    if (total != expectedBytes) return false
                }
                output.force(true)
            }
            if (target.length() != expectedBytes || sha(target) != expectedSha256)
                return false
            forceDirectory(parent)
            complete = true
            return true
        } catch (_: Exception) {
            return false
        } finally {
            if (!complete) runCatching { Files.deleteIfExists(target.toPath()) }
        }
    }

    fun forceExact(file: File): Boolean = try {
        if (!file.isFile || Files.isSymbolicLink(file.toPath())) false
        else {
            FileChannel.open(file.toPath(), StandardOpenOption.WRITE).use {
                it.force(true)
            }
            forceDirectory(file.parentFile)
            true
        }
    } catch (_: Exception) {
        false
    }

    private fun forceDirectory(directory: File?) {
        if (directory == null) return
        runCatching {
            FileChannel.open(directory.toPath(), StandardOpenOption.READ).use {
                it.force(true)
            }
        }
    }

    private fun sha(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(CHUNK)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read > 0) digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") {
            (it.toInt() and 255).toString(16).padStart(2, '0')
        }
    }

    private val SHA = Regex("[0-9a-f]{64}")
}
