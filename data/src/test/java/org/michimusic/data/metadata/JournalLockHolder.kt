package org.michimusic.data.metadata

import java.io.File
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardOpenOption

/**
 * ME-11R cross-process witness. This child JVM takes the real OS lock on
 * `journal-v1.lock` and keeps it until its stdin receives one byte. It exists so a
 * test can prove that exclusion is not limited to a single process (a per-process
 * `Mutex` alone would let two processes edit the same journal).
 *
 * It uses the JDK channel directly and never the journal class: the lock must be
 * held by a foreign owner for the evidence to mean anything.
 */
object JournalLockHolder {
    @JvmStatic
    fun main(args: Array<String>) {
        val directory = File(args[0])
        Files.createDirectories(directory.toPath())
        val lockFile = File(directory, "journal-v1.lock")
        FileChannel.open(
            lockFile.toPath(),
            StandardOpenOption.CREATE,
            StandardOpenOption.WRITE,
            LinkOption.NOFOLLOW_LINKS,
        ).use { channel ->
            channel.lock().use {
                println("LOCKED")
                System.out.flush()
                System.`in`.read()
            }
        }
        println("RELEASED")
        System.out.flush()
    }
}
