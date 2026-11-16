package org.michimusic.link

import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.nio.channels.Channels
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.OpenOption
import java.nio.file.StandardOpenOption

/**
 * A48: on-demand AirCat .part write boundary. Unlike FileOutputStream(path),
 * this never follows a symlink to truncate or append to an unrelated file.
 * A verified resume also binds the opened descriptor's size to the checkpoint.
 * The owner retains its existing single-flight/lease/continuity discipline.
 */
internal object AirCatPartOutput {
    @Throws(IOException::class)
    fun open(part: File, append: Boolean, expectedOffset: Long): OutputStream {
        if (expectedOffset < 0L || (append && expectedOffset == 0L) ||
            (!append && expectedOffset != 0L)) throw IOException("AIRC_PART_OFFSET_INVALID")
        val directory = part.parentFile ?: throw IOException("AIRC_PART_PARENT_MISSING")
        if (!Files.isDirectory(directory.toPath(), LinkOption.NOFOLLOW_LINKS) ||
            Files.isSymbolicLink(directory.toPath())) throw IOException("AIRC_PART_PARENT_UNSAFE")

        val path = part.toPath()
        val occupied = Files.exists(path, LinkOption.NOFOLLOW_LINKS)
        if (occupied && !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
            throw IOException("AIRC_PART_NOT_REGULAR")
        if (append && !occupied) throw IOException("AIRC_PART_RESUME_MISSING")

        val options = mutableSetOf<OpenOption>(StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)
        when {
            append -> options.add(StandardOpenOption.APPEND)
            occupied -> options.add(StandardOpenOption.TRUNCATE_EXISTING)
            else -> options.add(StandardOpenOption.CREATE_NEW)
        }
        // NOFOLLOW is enforced at open() too, not merely by a preliminary check.
        val channel = FileChannel.open(path, options)
        try {
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) ||
                (append && channel.size() != expectedOffset))
                throw IOException("AIRC_PART_RESUME_CHANGED")
            return Channels.newOutputStream(channel)
        } catch (failure: Exception) {
            channel.close()
            throw failure
        }
    }
}
