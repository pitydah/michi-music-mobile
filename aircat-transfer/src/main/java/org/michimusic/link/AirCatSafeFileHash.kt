package org.michimusic.link

import java.io.File
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest

/**
 * A50: hash the exact opened, non-symlink file descriptor with bounded memory.
 * A cached source must never make a symlink to a different music file look
 * like an authenticated original. Fail closed if the filesystem cannot provide
 * a stable file identity or if length/path ownership changes during hashing.
 * Called only for explicit downloads, caches or recovery; no resident worker.
 */
internal object AirCatSafeFileHash {
    fun sha256(file: File): String = runCatching {
        val path = file.toPath()
        val parent = file.parentFile?.toPath() ?: return@runCatching ""
        if (Files.isSymbolicLink(parent) ||
            !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) return@runCatching ""
        val first = Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        val firstKey = first.fileKey() ?: return@runCatching ""
        if (!first.isRegularFile || first.size() < 0L) return@runCatching ""
        val digest = MessageDigest.getInstance("SHA-256")
        FileChannel.open(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { channel ->
            val size = channel.size()
            if (size != first.size()) return@runCatching ""
            val buffer = ByteBuffer.allocate(64 * 1024)
            var readBytes = 0L
            while (true) {
                buffer.clear()
                val read = channel.read(buffer)
                if (read < 0) break
                if (read == 0) return@runCatching ""
                readBytes = Math.addExact(readBytes, read.toLong())
                if (readBytes > size) return@runCatching ""
                digest.update(buffer.array(), 0, read)
            }
            if (readBytes != size || channel.size() != size) return@runCatching ""
        }
        val last = Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        if (!last.isRegularFile || last.size() != first.size() ||
            last.fileKey() != firstKey || Files.isSymbolicLink(parent)) return@runCatching ""
        digest.digest().joinToString("") { "%02x".format(it) }
    }.getOrDefault("")
}
